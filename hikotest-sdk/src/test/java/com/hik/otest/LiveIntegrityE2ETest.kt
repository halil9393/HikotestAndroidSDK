package com.hik.otest

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Android SDK bütünlük doğrulama — CANLI PROD E2E (docs/WASM_INTEGRITY.md §6/§8,
 * panel reposu). Web SDK'daki scripts/verify-web-sdk-integrity-live.mjs'in Bölüm B
 * eşleniği: gerçek `halil9393/hikotest-p-79f64c7a` release'inin (release.wasm +
 * manifest.json + integrity.json) GERÇEK baytlarını indirir ve HikoIntegrity.verify()'ı
 * — override YOK — gömülü `hk-2026-07` public key'e karşı doğrudan çalıştırır. Bu,
 * standalone HikoWasm.kt'nin (public/get-started/standalone/) hiçbir zaman canlı
 * çalıştırılmamış olan portundan farklı olarak, SDK'ya taşınan Kotlin kriptosunun
 * gerçek bir üretim imzasını doğruladığını fiilen kanıtlar.
 *
 * Ağ + GITHUB_TOKEN gerektirir (bu repoda .env.local yok; kardeş panel reposundakini
 * kullanır, aynı geliştiricinin makinesi/GitHub hesabı). Token/ağ yoksa test SESSİZCE
 * atlanır (assumeTrue) — CI'da veya token'sız makinelerde build'i kırmaz.
 *
 * Çalıştır: ./gradlew :hikotest-sdk:test --tests "com.hik.otest.LiveIntegrityE2ETest"
 */
class LiveIntegrityE2ETest {

    private val repoOwner = "halil9393"
    private val repoName = "hikotest-p-79f64c7a"
    private val client = OkHttpClient()

    private var token: String? = null
    private var wasmBytes: ByteArray? = null
    private var manifestBytes: ByteArray? = null
    private var integrityJson: String? = null

    @Before
    fun setUp() {
        token = System.getenv("GITHUB_TOKEN") ?: readTokenFromSiblingEnvFiles()
        assumeTrue("GITHUB_TOKEN not available (no env var, no sibling .env.local) — skipping live E2E", token != null)

        val release = ghGet("/repos/$repoOwner/$repoName/releases/latest", "application/vnd.github+json")
        val json = JSONObject(release)
        val assets = json.getJSONArray("assets")
        var wasmId: Long? = null
        var manifestId: Long? = null
        var integrityId: Long? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            when (a.getString("name")) {
                "release.wasm" -> wasmId = a.getLong("id")
                "manifest.json" -> manifestId = a.getLong("id")
                "integrity.json" -> integrityId = a.getLong("id")
            }
        }
        assumeTrue("live release has no integrity.json yet — skipping", integrityId != null)
        wasmBytes = ghDownload(wasmId!!)
        manifestBytes = ghDownload(manifestId!!)
        integrityJson = ghDownload(integrityId!!).toString(Charsets.UTF_8)
    }

    private fun readTokenFromSiblingEnvFiles(): String? {
        val candidates = listOf(
            "../../hikotest/.env.local",
            "../hikotest/.env.local",
            "../../../hikotest/.env.local",
        )
        for (path in candidates) {
            val file = File(path)
            if (!file.exists()) continue
            val line = file.readLines().firstOrNull { it.startsWith("GITHUB_TOKEN=") } ?: continue
            return line.substringAfter("=").trim().trim('"', '\'')
        }
        return null
    }

    private fun ghGet(path: String, accept: String): String {
        val request = Request.Builder()
            .url("https://api.github.com$path")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", accept)
            .build()
        return client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "GitHub API failed: HTTP ${resp.code}" }
            resp.body!!.string()
        }
    }

    private fun ghDownload(assetId: Long): ByteArray {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repoOwner/$repoName/releases/assets/$assetId")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/octet-stream")
            .build()
        return client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Asset download failed: HTTP ${resp.code}" }
            resp.body!!.bytes()
        }
    }

    @Test
    fun `gomulu public key gercek uretim imzasini dogruluyor`() {
        val integrity = JSONObject(integrityJson!!)
        assertTrue("release should be Layer 2 signed", integrity.has("signature") && !integrity.isNull("signature"))
        assertTrue("keyId should be the currently embedded key", integrity.optString("keyId") == "hk-2026-07")

        // No publicKeys override here — this exercises the SAME embedded map
        // HikotestConfig/WasmFetcher use in production.
        val result = HikoIntegrity.verify(integrityJson!!, wasmBytes!!, manifestBytes!!)
        assertTrue("ok: ${result.reason}", result.ok)
        assertTrue("verified: ${result.reason}", result.verified)
    }

    @Test
    fun `T1 gercek wasm 1 bayt bozulunca RED`() {
        val tampered = wasmBytes!!.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xff).toByte()
        val result = HikoIntegrity.verify(integrityJson!!, tampered, manifestBytes!!)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("wasm.sha256 mismatch"))
    }

    @Test
    fun `T3 gercek manifest tamper OTA kilidi baypasi RED`() {
        val tampered = manifestBytes!! + " ".toByteArray(Charsets.UTF_8)
        val result = HikoIntegrity.verify(integrityJson!!, wasmBytes!!, tampered)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("manifest.sha256 mismatch"))
    }

    @Test
    fun `T2 bilinmeyen keyId RED`() {
        val integrity = JSONObject(integrityJson!!)
        integrity.put("keyId", "hk-9999-99")
        val result = HikoIntegrity.verify(integrity.toString(), wasmBytes!!, manifestBytes!!)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("unknown/invalid keyId"))
    }

    @Test
    fun `bozuk imza gercek release uzerinde Ed25519 RED`() {
        val integrity = JSONObject(integrityJson!!)
        val sig = integrity.getString("signature")
        val flipped = sig.dropLast(2) + (if (sig.takeLast(2) == "AA") "BB" else "AA")
        integrity.put("signature", flipped)
        val result = HikoIntegrity.verify(integrity.toString(), wasmBytes!!, manifestBytes!!)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("Ed25519 signature failed"))
    }

    @Test
    fun `imza kaldirilinca Layer 1 hash tutarli ama dogrulanmadi`() {
        val integrity = JSONObject(integrityJson!!)
        integrity.put("signature", JSONObject.NULL)
        val result = HikoIntegrity.verify(integrity.toString(), wasmBytes!!, manifestBytes!!)
        assertTrue(result.ok)
        assertFalse(result.verified)
    }

    @Test
    fun `evaluate ENFORCE gercek release ile RED atmadan geciyor`() {
        val result = HikoIntegrity.evaluate(VerifyMode.ENFORCE, integrityJson, wasmBytes!!, manifestBytes)
        assertTrue(result!!.ok)
        assertTrue(result.verified)
    }

    @Test(expected = IllegalStateException::class)
    fun `evaluate ENFORCE integrity yokmus gibi simule edilince throws`() {
        HikoIntegrity.evaluate(VerifyMode.ENFORCE, null, wasmBytes!!, manifestBytes)
    }
}
