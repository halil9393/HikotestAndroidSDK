package com.hik.otest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

internal class WasmFetcher(context: Context, private val config: HikotestConfig) {

    private val client = OkHttpClient()
    private val cacheDir = File(context.filesDir, "hikotest_cache").also { it.mkdirs() }
    private val wasmFile = File(cacheDir, config.wasmAssetName)
    private val tagFile = File(cacheDir, "tag.txt")

    // Initial load: use cache if up-to-date, otherwise download.
    suspend fun getWasmBytes(): ByteArray = withContext(Dispatchers.IO) {
        val release = runCatching { fetchLatestRelease() }.getOrNull()

        if (release != null) {
            val cachedTag = cachedTag()
            if (!wasmFile.exists() || release.tag != cachedTag) {
                val bytes = downloadAsset(release.assetId)
                    ?: error("Failed to download release.wasm (assetId=${release.assetId})")
                persist(bytes, release.tag)
            }
        }

        check(wasmFile.exists()) {
            "No cached WASM found and network download failed. Check token and internet connection."
        }
        wasmFile.readBytes()
    }

    // Background poll: returns new bytes only when the remote tag differs from the cached one.
    // Returns null if there is no update or if the network call fails.
    suspend fun checkForUpdate(): ByteArray? = withContext(Dispatchers.IO) {
        val release = runCatching { fetchLatestRelease() }.getOrNull() ?: return@withContext null
        if (release.tag == cachedTag()) return@withContext null

        val bytes = runCatching { downloadAsset(release.assetId) }.getOrNull()?.also {
            persist(it, release.tag)
        }
        bytes
    }

    private fun cachedTag(): String = if (tagFile.exists()) tagFile.readText().trim() else ""

    private fun persist(bytes: ByteArray, tag: String) {
        wasmFile.writeBytes(bytes)
        tagFile.writeText(tag)
    }

    private data class ReleaseInfo(val tag: String, val assetId: Long)

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
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == config.wasmAssetName) {
                    return ReleaseInfo(tag, asset.getLong("id"))
                }
            }
            null
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
