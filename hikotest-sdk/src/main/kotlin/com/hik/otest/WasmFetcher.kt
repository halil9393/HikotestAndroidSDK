package com.hik.otest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** One fetched release: the wasm binary plus its manifest.json (best-effort) and
 *  detached integrity.json (best-effort — older releases predating the §8
 *  rollout won't have one; verification mode decides how that's treated). */
internal data class FetchedBundle(val wasmBytes: ByteArray, val manifestJson: String?, val integrityJson: String? = null)

internal class WasmFetcher(context: Context, private val config: HikotestConfig) {

    private val client = OkHttpClient()
    private val cacheDir = File(context.filesDir, "hikotest_cache").also { it.mkdirs() }
    private val wasmFile = File(cacheDir, config.wasmAssetName)
    private val manifestFile = File(cacheDir, config.manifestAssetName)
    private val integrityFile = File(cacheDir, config.integrityAssetName)
    private val tagFile = File(cacheDir, "tag.txt")

    // Initial load: use cache if up-to-date, otherwise download.
    suspend fun getBundle(): FetchedBundle = withContext(Dispatchers.IO) {
        val release = runCatching { fetchLatestRelease() }.getOrNull()

        if (release != null) {
            val cachedTag = cachedTag()
            if (!wasmFile.exists() || release.tag != cachedTag) {
                val bytes = downloadAsset(release.wasmAssetId)
                    ?: error("Failed to download release.wasm (assetId=${release.wasmAssetId})")
                persist(bytes, downloadManifest(release), downloadIntegrity(release), release.tag)
            }
        }

        check(wasmFile.exists()) {
            "No cached WASM found and network download failed. Check token and internet connection."
        }
        FetchedBundle(wasmFile.readBytes(), cachedManifest(), cachedIntegrity())
    }

    // Background poll: returns the new bundle only when the remote tag differs from
    // the cached one. Returns null if there is no update or if the network call fails.
    suspend fun checkForUpdate(): FetchedBundle? = withContext(Dispatchers.IO) {
        val release = runCatching { fetchLatestRelease() }.getOrNull() ?: return@withContext null
        if (release.tag == cachedTag()) return@withContext null

        val bytes = runCatching { downloadAsset(release.wasmAssetId) }.getOrNull()
            ?: return@withContext null
        val manifestJson = downloadManifest(release)
        val integrityJson = downloadIntegrity(release)
        persist(bytes, manifestJson, integrityJson, release.tag)
        FetchedBundle(bytes, manifestJson, integrityJson)
    }

    private fun cachedTag(): String = if (tagFile.exists()) tagFile.readText().trim() else ""

    private fun cachedManifest(): String? = if (manifestFile.exists()) manifestFile.readText() else null

    private fun cachedIntegrity(): String? = if (integrityFile.exists()) integrityFile.readText() else null

    private fun persist(bytes: ByteArray, manifestJson: String?, integrityJson: String?, tag: String) {
        wasmFile.writeBytes(bytes)
        if (manifestJson != null) manifestFile.writeText(manifestJson) else manifestFile.delete()
        if (integrityJson != null) integrityFile.writeText(integrityJson) else integrityFile.delete()
        tagFile.writeText(tag)
    }

    // Manifest is best-effort: OTA lock routing needs it, plain calls work without it.
    private fun downloadManifest(release: ReleaseInfo): String? {
        val id = release.manifestAssetId ?: return null
        return runCatching { downloadAsset(id)?.toString(Charsets.UTF_8) }.getOrNull()
    }

    // integrity.json is best-effort too: older releases (predating the §8 rollout) won't have it.
    private fun downloadIntegrity(release: ReleaseInfo): String? {
        val id = release.integrityAssetId ?: return null
        return runCatching { downloadAsset(id)?.toString(Charsets.UTF_8) }.getOrNull()
    }

    private data class ReleaseInfo(
        val tag: String,
        val wasmAssetId: Long,
        val manifestAssetId: Long?,
        val integrityAssetId: Long?,
    )

    private fun fetchLatestRelease(): ReleaseInfo? {
        val endpoint = if (config.isBeta) "releases" else "releases/latest"
        val request = Request.Builder()
            .url("https://api.github.com/repos/${config.repoOwner}/${config.repoName}/$endpoint")
            .addHeader("Authorization", "Bearer ${config.githubToken}")
            .addHeader("Accept", "application/vnd.github+json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = if (config.isBeta) {
                // /releases returns an array; pick the first entry (most recent, including pre-releases)
                val array = org.json.JSONArray(body)
                if (array.length() == 0) return null
                array.getJSONObject(0)
            } else {
                JSONObject(body)
            }
            val tag = json.optString("tag_name").takeIf { it.isNotEmpty() } ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            var wasmAssetId: Long? = null
            var manifestAssetId: Long? = null
            var integrityAssetId: Long? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                when (asset.getString("name")) {
                    config.wasmAssetName -> wasmAssetId = asset.getLong("id")
                    config.manifestAssetName -> manifestAssetId = asset.getLong("id")
                    config.integrityAssetName -> integrityAssetId = asset.getLong("id")
                }
            }
            wasmAssetId?.let { ReleaseInfo(tag, it, manifestAssetId, integrityAssetId) }
        }
    }

    private fun downloadAsset(assetId: Long): ByteArray? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${config.repoOwner}/${config.repoName}/releases/assets/$assetId")
            .addHeader("Authorization", "Bearer ${config.githubToken}")
            .addHeader("Accept", "application/octet-stream")
            .build()

        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }
}
