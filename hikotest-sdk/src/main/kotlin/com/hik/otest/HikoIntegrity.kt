package com.hik.otest

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * WASM release bütünlük doğrulama modu (docs/WASM_INTEGRITY.md §6, panel reposu).
 * `OFF` (varsayılan) = bugünkü davranış, doğrulama yok. `WARN` = integrity.json
 * varsa doğrula, uyuşmazlıkta logla ama çalışmaya devam et; yoksa sessizce geç.
 * `ENFORCE` = integrity.json yoksa VEYA doğrulama başarısızsa release'i reddet.
 */
enum class VerifyMode { OFF, WARN, ENFORCE }

internal data class IntegrityResult(val ok: Boolean, val verified: Boolean, val reason: String? = null)

/**
 * Panel reposunun standalone Kotlin yükleyicisindeki
 * (public/get-started/standalone/HikoWasm.kt) kripto mantığının SDK'ya taşınmış
 * hali — WasmFetcher'ın indirdiği canlı release üzerinde çalışır (assets/
 * dosyası yerine). Aynı sabit-format imza mesajı ve gömülü public key haritası
 * Web SDK (src/integrity.ts) ve iOS SDK ile birebir aynıdır.
 *
 * NOT: Ed25519 için java.security (KeyFactory/Signature) Android API 33+
 * gerektirir (standalone loader'daki aynı not geçerli); daha eski cihazlar için
 * bir Conscrypt/BouncyCastle provider eklemek uygulamanın sorumluluğundadır.
 */
internal object HikoIntegrity {

    /** Public key map — keyId → SPKI PEM. Mirror of src/lib/wasm/signing-keys.mjs
     *  (panel repo). PUBLIC keys only; safe to ship in the app. Rotation: add the
     *  new keyId here (keep the old one during the grace period). */
    private val PUBLIC_SIGNING_KEYS = mapOf(
        "hk-2026-07" to """
            -----BEGIN PUBLIC KEY-----
            MCowBQYDK2VwAyEAogmXuGBXvXMzYXnvBnBYgE8vpjJNTXe0Fcz6RDxoCD4=
            -----END PUBLIC KEY-----
        """.trimIndent(),
    )

    /** Fixed-format signing message — identical across all SDKs + loaders. */
    private const val SIGN_MESSAGE_PREFIX = "hikotest.integrity.v1"

    /**
     * `mode` dispatch (§6): `OFF` → null (nothing to evaluate/log). `WARN` with no
     * integrity.json → null (silent skip). `ENFORCE` with no integrity.json, or a
     * failing [verify], throws. Otherwise returns the [IntegrityResult] so the
     * caller can log a warning when `!result.ok` under `WARN`.
     */
    fun evaluate(
        mode: VerifyMode,
        integrityJson: String?,
        wasmBytes: ByteArray,
        manifestBytes: ByteArray?,
        publicKeys: Map<String, String> = PUBLIC_SIGNING_KEYS,
    ): IntegrityResult? {
        if (mode == VerifyMode.OFF) return null
        if (integrityJson == null) {
            check(mode != VerifyMode.ENFORCE) {
                "Hikotest: integrity.json required in enforce mode but release has none"
            }
            return null
        }
        val result = verify(integrityJson, wasmBytes, manifestBytes, publicKeys)
        if (!result.ok && mode == VerifyMode.ENFORCE) {
            error("Hikotest integrity check failed: ${result.reason}")
        }
        return result
    }

    /** Mirror of integrity-core.mjs verifyIntegrity (client side, JCA).
     *  `publicKeys` defaults to the embedded map; tests pass their own to exercise
     *  the verified=true path without the real signing key. */
    fun verify(
        integrityJson: String,
        wasmBytes: ByteArray,
        manifestBytes: ByteArray?,
        publicKeys: Map<String, String> = PUBLIC_SIGNING_KEYS,
    ): IntegrityResult {
        val integrity = runCatching { JSONObject(integrityJson) }.getOrNull()
            ?: return IntegrityResult(false, false, "integrity.json missing or unsupported version")
        if (integrity.optInt("integrity") != 1) {
            return IntegrityResult(false, false, "integrity.json missing or unsupported version")
        }
        if (manifestBytes == null) {
            return IntegrityResult(false, false, "raw manifest.json bytes not available")
        }
        if (sha256Hex(wasmBytes) != integrity.optJSONObject("wasm")?.optString("sha256")) {
            return IntegrityResult(false, false, "wasm.sha256 mismatch (transit corruption / tamper)")
        }
        if (sha256Hex(manifestBytes) != integrity.optJSONObject("manifest")?.optString("sha256")) {
            return IntegrityResult(false, false, "manifest.sha256 mismatch (tamper)")
        }
        if (!integrity.has("signature") || integrity.isNull("signature")) {
            return IntegrityResult(true, false, "unsigned (Layer 1 — hash only)")
        }
        val keyId = integrity.optString("keyId")
        val pem = publicKeys[keyId]
            ?: return IntegrityResult(false, false, "unknown/invalid keyId: $keyId")
        if (!verifyEd25519(buildSigningMessage(integrity), integrity.getString("signature"), pem)) {
            return IntegrityResult(false, false, "Ed25519 signature failed (forged source?)")
        }
        return IntegrityResult(true, true)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun buildSigningMessage(i: JSONObject): String = listOf(
        SIGN_MESSAGE_PREFIX,
        "bundleVersion=${i.optString("bundleVersion")}",
        "wasm.sha256=${i.optJSONObject("wasm")?.optString("sha256")}",
        "manifest.sha256=${i.optJSONObject("manifest")?.optString("sha256")}",
        "keyId=${i.optString("keyId")}",
    ).joinToString("\n")

    private fun verifyEd25519(message: String, signatureB64: String, spkiPem: String): Boolean = try {
        val der = Base64.getDecoder().decode(
            spkiPem.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), ""),
        )
        val pub = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
        Signature.getInstance("Ed25519").run {
            initVerify(pub)
            update(message.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(signatureB64))
        }
    } catch (e: Exception) {
        false
    }
}
