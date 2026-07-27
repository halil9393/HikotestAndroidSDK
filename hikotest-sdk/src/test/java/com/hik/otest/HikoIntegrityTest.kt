package com.hik.otest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/**
 * HikoIntegrity.verify()/evaluate() birim testleri (docs/WASM_INTEGRITY.md §6).
 * Gerçek imzalama anahtarı yalnız panelin Vercel env'inde (WASM_SIGNING_KEY) —
 * burada test amaçlı KENDİ Ed25519 anahtar çiftimizi üretip verify()'a
 * publicKeys override olarak veriyoruz (gömülü PUBLIC_SIGNING_KEYS'i değiştirmeden).
 * Web SDK'daki test/integrity.test.mjs ile aynı senaryolar (T1/T2/T3 + Layer 1/2).
 */
class HikoIntegrityTest {

    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val testKeyId = "test-key-1"
    private val publicKeys = mapOf(testKeyId to pemOf(keyPair.public.encoded))

    private fun pemOf(der: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(der)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val wasmBytes = "fake wasm bytes".toByteArray(Charsets.UTF_8)
    private val manifestBytes = """{"abi":1,"functions":[]}""".toByteArray(Charsets.UTF_8)
    private val bundleVersion = "1.2.3"
    private val wasmSha256 = sha256Hex(wasmBytes)
    private val manifestSha256 = sha256Hex(manifestBytes)

    private fun signingMessage(keyId: String) = listOf(
        "hikotest.integrity.v1",
        "bundleVersion=$bundleVersion",
        "wasm.sha256=$wasmSha256",
        "manifest.sha256=$manifestSha256",
        "keyId=$keyId",
    ).joinToString("\n")

    private fun sign(message: String): String {
        val sig = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(message.toByteArray(Charsets.UTF_8))
            sign()
        }
        return Base64.getEncoder().encodeToString(sig)
    }

    private fun signedIntegrityJson(keyId: String = testKeyId, signature: String? = sign(signingMessage(testKeyId))): String {
        val sigField = if (signature == null) "null" else "\"$signature\""
        return """
            {"integrity":1,"bundleVersion":"$bundleVersion","wasm":{"sha256":"$wasmSha256"},
             "manifest":{"sha256":"$manifestSha256"},"alg":"ed25519","keyId":"$keyId","signature":$sigField}
        """.trimIndent()
    }

    @Test
    fun `gecerli Layer 2 imza - ok ve verified`() {
        val result = HikoIntegrity.verify(signedIntegrityJson(), wasmBytes, manifestBytes, publicKeys)
        assertTrue(result.ok)
        assertTrue(result.verified)
    }

    @Test
    fun `Layer 1 - imzasiz ama hash tutarli - ok verified false`() {
        val result = HikoIntegrity.verify(signedIntegrityJson(signature = null), wasmBytes, manifestBytes, publicKeys)
        assertTrue(result.ok)
        assertFalse(result.verified)
        assertTrue(result.reason!!.contains("Layer 1"))
    }

    @Test
    fun `T1 - wasm tamper - RED`() {
        val tampered = "tampered wasm bytes".toByteArray(Charsets.UTF_8)
        val result = HikoIntegrity.verify(signedIntegrityJson(), tampered, manifestBytes, publicKeys)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("wasm.sha256 mismatch"))
    }

    @Test
    fun `T3 - manifest tamper OTA kilidi baypasi - RED`() {
        val tampered = """{"abi":1,"functions":[],"x":1}""".toByteArray(Charsets.UTF_8)
        val result = HikoIntegrity.verify(signedIntegrityJson(), wasmBytes, tampered, publicKeys)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("manifest.sha256 mismatch"))
    }

    @Test
    fun `T2 - bilinmeyen keyId - RED`() {
        val result = HikoIntegrity.verify(signedIntegrityJson(keyId = "unknown-key"), wasmBytes, manifestBytes, publicKeys)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("unknown/invalid keyId"))
    }

    @Test
    fun `bozuk imza - Ed25519 dogrulama RED`() {
        val real = sign(signingMessage(testKeyId))
        val flipped = real.dropLast(2) + (if (real.takeLast(2) == "AA") "BB" else "AA")
        val result = HikoIntegrity.verify(signedIntegrityJson(signature = flipped), wasmBytes, manifestBytes, publicKeys)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("Ed25519 signature failed"))
    }

    @Test
    fun `integrity json eksik veya desteklenmeyen surum - RED`() {
        assertFalse(HikoIntegrity.verify("not json", wasmBytes, manifestBytes, publicKeys).ok)
        assertFalse(HikoIntegrity.verify("""{"integrity":2}""", wasmBytes, manifestBytes, publicKeys).ok)
    }

    @Test
    fun `ham manifest bayti yoksa - RED`() {
        val result = HikoIntegrity.verify(signedIntegrityJson(), wasmBytes, null, publicKeys)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("manifest.json bytes not available"))
    }

    // ─── evaluate() mode dispatch (§6 off/warn/enforce) ───────────────────────

    @Test
    fun `evaluate - OFF hicbir zaman kontrol etmez`() {
        assertEquals(null, HikoIntegrity.evaluate(VerifyMode.OFF, null, wasmBytes, manifestBytes, publicKeys))
        assertEquals(null, HikoIntegrity.evaluate(VerifyMode.OFF, "garbage", wasmBytes, manifestBytes, publicKeys))
    }

    @Test
    fun `evaluate - WARN plus integrity yok - sessizce gecer`() {
        assertEquals(null, HikoIntegrity.evaluate(VerifyMode.WARN, null, wasmBytes, manifestBytes, publicKeys))
    }

    @Test(expected = IllegalStateException::class)
    fun `evaluate - ENFORCE plus integrity yok - throws`() {
        HikoIntegrity.evaluate(VerifyMode.ENFORCE, null, wasmBytes, manifestBytes, publicKeys)
    }

    @Test
    fun `evaluate - WARN plus dogrulama basarisiz - loglanabilir sonuc doner throw yok`() {
        val tampered = "tampered".toByteArray(Charsets.UTF_8)
        val result = HikoIntegrity.evaluate(VerifyMode.WARN, signedIntegrityJson(), tampered, manifestBytes, publicKeys)
        assertFalse(result!!.ok)
    }

    @Test(expected = IllegalStateException::class)
    fun `evaluate - ENFORCE plus dogrulama basarisiz - throws`() {
        val tampered = "tampered".toByteArray(Charsets.UTF_8)
        HikoIntegrity.evaluate(VerifyMode.ENFORCE, signedIntegrityJson(), tampered, manifestBytes, publicKeys)
    }

    @Test
    fun `evaluate - ENFORCE plus gecerli imza - RED yok sonuc doner`() {
        val result = HikoIntegrity.evaluate(VerifyMode.ENFORCE, signedIntegrityJson(), wasmBytes, manifestBytes, publicKeys)
        assertTrue(result!!.ok)
        assertTrue(result.verified)
    }
}
