package com.hik.otest

class HikotestConfig private constructor(
    val panelBaseUrl: String,
    val projectId: String,
    val githubToken: String,
    val repoOwner: String,
    val repoName: String,
    val environment: String,
    val isBeta: Boolean,
    val updateIntervalMs: Long,
    val wasmAssetName: String,
    val manifestAssetName: String,
    val localWasmBytes: ByteArray?,
    val localManifestJson: String?,
    val lockedFunctions: Set<String>,
    val verify: VerifyMode,
    val integrityAssetName: String,
) {

    /** True when the panel edge OTA is configured (preferred over direct GitHub). */
    val hasPanel: Boolean get() = panelBaseUrl.isNotBlank()

    /**
     * True when an OTA source (panel or GitHub repo) is configured; false = fully
     * offline (localBundle only). The panel edge path is preferred (docs
     * WASM_INTEGRITY.md §4.13): devices poll the panel's edge-cached
     * `/api/ota/:projectId/latest` instead of hammering GitHub's 5 000/h rate
     * limit, and no token ships to the client.
     */
    val hasRemote: Boolean get() = panelBaseUrl.isNotBlank() || repoOwner.isNotBlank()

    class Builder {
        private var panelBaseUrl: String = ""
        private var projectId: String = ""
        private var githubToken: String = ""
        private var repoOwner: String = ""
        private var repoName: String = ""
        private var environment: String = "production"
        private var isBeta: Boolean = false
        private var updateIntervalMs: Long = 60_000L
        private var wasmAssetName: String = "release.wasm"
        private var manifestAssetName: String = "manifest.json"
        private var localWasmBytes: ByteArray? = null
        private var localManifestJson: String? = null
        private var lockedFunctions: Set<String> = emptySet()
        private var verify: VerifyMode = VerifyMode.OFF
        private var integrityAssetName: String = "integrity.json"

        /**
         * Panel OTA base URL, e.g. "https://hikotest.app" (origin only, no trailing
         * path). PREFERRED over [repoOwner]/[repoName]/[githubToken]: devices poll the
         * panel's edge-cached `/api/ota/:projectId/latest` instead of
         * `api.github.com/releases/latest` (docs WASM_INTEGRITY.md §4.13). N devices no
         * longer burn GitHub's 5 000/h rate limit (the edge caches per project, not per
         * device) and NO token ships to the client — the panel proxies release assets
         * server-side. Requires [projectId].
         */
        fun panelBaseUrl(url: String) = apply { this.panelBaseUrl = url }

        /**
         * Hikotest project UUID (the panel's `projects.id`). Required with [panelBaseUrl].
         * This is the panel's device-facing identifier; it is not derivable from a repo
         * name, so a device moving onto the panel path must be configured with it.
         */
        fun projectId(id: String) = apply { this.projectId = id }

        @Deprecated("Prefer panelBaseUrl + projectId (no token on the client, edge-cached).")
        fun githubToken(token: String) = apply { this.githubToken = token }

        @Deprecated("Prefer panelBaseUrl + projectId (the panel proxies GitHub server-side).")
        fun repoOwner(owner: String) = apply { this.repoOwner = owner }

        @Deprecated("Prefer panelBaseUrl + projectId (the panel proxies GitHub server-side).")
        fun repoName(name: String) = apply { this.repoName = name }
        fun environment(env: String) = apply { this.environment = env }
        fun isBeta(beta: Boolean) = apply { this.isBeta = beta }
        fun updateIntervalMs(ms: Long) = apply { this.updateIntervalMs = ms }
        fun wasmAssetName(name: String) = apply { this.wasmAssetName = name }
        fun manifestAssetName(name: String) = apply { this.manifestAssetName = name }

        /**
         * Detached bundle-integrity check (docs/WASM_INTEGRITY.md §6). `OFF`
         * (default) = today's behavior, no verification. `WARN` = verify when
         * `integrity.json` is present, log on mismatch but still run; skip
         * silently if absent. `ENFORCE` = reject the release if `integrity.json`
         * is absent OR verification fails.
         */
        fun verify(mode: VerifyMode) = apply { this.verify = mode }

        /** integrity.json asset name, as published by the panel's build workflow. */
        fun integrityAssetName(name: String) = apply { this.integrityAssetName = name }

        /**
         * Embedded release for the hybrid/offline mode (OTA lock): ship the
         * panel's release.wasm (+ manifest.json) in your app's assets and pass
         * the bytes here. Locked functions always run from this bundle, never
         * from OTA. With no repo configured the SDK runs fully offline.
         */
        fun localBundle(wasmBytes: ByteArray, manifestJson: String? = null) = apply {
            this.localWasmBytes = wasmBytes
            this.localManifestJson = manifestJson
        }

        /**
         * Device-side lock list: these functions ALWAYS run from the local
         * bundle, even if the panel unlocks them remotely (device wins —
         * one-way union with the manifest's `ota:false` flags). Requires [localBundle].
         */
        fun lockedFunctions(vararg names: String) = apply { this.lockedFunctions = names.toSet() }

        fun build(): HikotestConfig {
            val hasPanel = panelBaseUrl.isNotBlank() || projectId.isNotBlank()
            val hasRepo = repoOwner.isNotBlank() || repoName.isNotBlank() || githubToken.isNotBlank()
            if (hasPanel) {
                require(panelBaseUrl.isNotBlank()) { "HikotestConfig: panelBaseUrl must not be empty" }
                require(projectId.isNotBlank()) { "HikotestConfig: projectId must not be empty" }
            }
            if (hasRepo) {
                require(githubToken.isNotBlank()) { "HikotestConfig: githubToken must not be empty" }
                require(repoOwner.isNotBlank()) { "HikotestConfig: repoOwner must not be empty" }
                require(repoName.isNotBlank()) { "HikotestConfig: repoName must not be empty" }
            }
            require(!(hasPanel && hasRepo)) {
                "HikotestConfig: set EITHER panelBaseUrl/projectId (panel OTA, preferred) OR repoOwner/repoName (legacy GitHub), not both"
            }
            if (!hasPanel && !hasRepo) {
                require(localWasmBytes != null) {
                    "HikotestConfig: configure panelBaseUrl/projectId (OTA), a repo (legacy OTA), localBundle (offline), or both (hybrid)"
                }
            }
            require(lockedFunctions.isEmpty() || localWasmBytes != null) {
                "HikotestConfig: lockedFunctions requires localBundle (locked functions run from the embedded release)"
            }
            require(updateIntervalMs >= 10_000L) { "HikotestConfig: updateIntervalMs must be at least 10 000 ms" }
            return HikotestConfig(
                // Trailing slash normalized away so `${panelBaseUrl}/api/ota/...` stays single-slash.
                panelBaseUrl = panelBaseUrl.trimEnd('/'),
                projectId = projectId,
                githubToken = githubToken,
                repoOwner = repoOwner,
                repoName = repoName,
                environment = environment,
                isBeta = isBeta,
                updateIntervalMs = updateIntervalMs,
                wasmAssetName = wasmAssetName,
                manifestAssetName = manifestAssetName,
                localWasmBytes = localWasmBytes,
                localManifestJson = localManifestJson,
                lockedFunctions = lockedFunctions,
                verify = verify,
                integrityAssetName = integrityAssetName,
            )
        }
    }
}
