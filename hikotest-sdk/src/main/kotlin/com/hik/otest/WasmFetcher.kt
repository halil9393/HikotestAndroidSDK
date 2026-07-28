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

    private val isPanel = config.hasPanel

    // Initial load: use cache if up-to-date, otherwise download.
    suspend fun getBundle(): FetchedBundle = withContext(Dispatchers.IO) {
        val release = runCatching { fetchLatestRelease(ifNoneMatchTag = null) }.getOrNull()

        if (release != null) {
            val cachedTag = cachedTag()
            if (!wasmFile.exists() || release.tag != cachedTag) {
                val bytes = downloadAsset(release.wasmUrl)
                    ?: error("Failed to download release.wasm (${release.wasmUrl})")
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
    // On the panel path an `If-None-Match: "<tag>"` short-circuits to a bodyless 304
    // (edge-core `latestEtag`), saving bandwidth on every idle poll.
    suspend fun checkForUpdate(): FetchedBundle? = withContext(Dispatchers.IO) {
        val release = runCatching { fetchLatestRelease(ifNoneMatchTag = cachedTag()) }.getOrNull()
            ?: return@withContext null
        if (release.tag == cachedTag()) return@withContext null

        val bytes = runCatching { downloadAsset(release.wasmUrl) }.getOrNull()
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
        val url = release.manifestUrl ?: return null
        return runCatching { downloadAsset(url)?.toString(Charsets.UTF_8) }.getOrNull()
    }

    // integrity.json is best-effort too: older releases (predating the §8 rollout) won't have it.
    private fun downloadIntegrity(release: ReleaseInfo): String? {
        val url = release.integrityUrl ?: return null
        return runCatching { downloadAsset(url)?.toString(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * One resolved release, source-agnostic: the asset URLs are already fully
     * qualified download endpoints (panel proxy URLs, or GitHub asset-id URLs).
     * [downloadAsset] adds the GitHub Bearer only on the legacy path.
     */
    private data class ReleaseInfo(
        val tag: String,
        val wasmUrl: String,
        val manifestUrl: String?,
        val integrityUrl: String?,
    )

    private fun fetchLatestRelease(ifNoneMatchTag: String?): ReleaseInfo? =
        if (isPanel) fetchLatestPanel(ifNoneMatchTag) else fetchLatestGithub()

    // ─── Panel source (preferred) ───────────────────────────────────────────────

    private fun fetchLatestPanel(ifNoneMatchTag: String?): ReleaseInfo? {
        val builder = Request.Builder()
            .url("${config.panelBaseUrl}/api/ota/${config.projectId}/latest")
            .addHeader("Accept", "application/json")
        // Content-addressed on the release tag (edge-core `latestEtag`): a matching
        // If-None-Match short-circuits to a bodyless 304, so an idle poll transfers nothing.
        if (!ifNoneMatchTag.isNullOrEmpty()) builder.addHeader("If-None-Match", "\"$ifNoneMatchTag\"")
        // No Authorization header ever leaves the device on this path — the panel proxies
        // release assets server-side with its own token.
        return client.newCall(builder.build()).execute().use { response ->
            if (response.code == 304) return null
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val tag = json.optString("tag").takeIf { it.isNotEmpty() } ?: return null
            val assets = json.optJSONObject("assets") ?: return null
            val wasmUrl = assets.optJSONObject("wasm")?.optString("url")?.takeIf { it.isNotEmpty() }
                ?: return null
            val manifestUrl = assets.optJSONObject("manifest")?.optString("url")?.takeIf { it.isNotEmpty() }
            val integrityUrl = assets.optJSONObject("integrity")?.optString("url")?.takeIf { it.isNotEmpty() }
            ReleaseInfo(
                tag,
                absolutePanelUrl(wasmUrl),
                manifestUrl?.let { absolutePanelUrl(it) },
                integrityUrl?.let { absolutePanelUrl(it) },
            )
        }
    }

    /** Panel `/latest` may return relative asset URLs; join them onto the configured origin. */
    private fun absolutePanelUrl(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url
        else config.panelBaseUrl + (if (url.startsWith("/")) url else "/$url")

    // ─── GitHub source (legacy, deprecated) ─────────────────────────────────────

    private fun fetchLatestGithub(): ReleaseInfo? {
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
            var wasmUrl: String? = null
            var manifestUrl: String? = null
            var integrityUrl: String? = null
            val base = "https://api.github.com/repos/${config.repoOwner}/${config.repoName}/releases/assets"
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = "$base/${asset.getLong("id")}"
                when (asset.getString("name")) {
                    config.wasmAssetName -> wasmUrl = url
                    config.manifestAssetName -> manifestUrl = url
                    config.integrityAssetName -> integrityUrl = url
                }
            }
            wasmUrl?.let { ReleaseInfo(tag, it, manifestUrl, integrityUrl) }
        }
    }

    private fun downloadAsset(url: String): ByteArray? {
        val builder = Request.Builder().url(url)
        if (!isPanel) {
            // GitHub asset download needs the octet-stream Accept + Bearer to return binary.
            builder.addHeader("Authorization", "Bearer ${config.githubToken}")
                .addHeader("Accept", "application/octet-stream")
        }
        return client.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }
}
