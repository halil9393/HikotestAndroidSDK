package com.hik.otest

class HikotestConfig private constructor(
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

    /** True when a GitHub repo is configured (OTA active); false = fully offline. */
    val hasRemote: Boolean get() = repoOwner.isNotBlank()

    class Builder {
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

        fun githubToken(token: String) = apply { this.githubToken = token }
        fun repoOwner(owner: String) = apply { this.repoOwner = owner }
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
            val hasRepo = repoOwner.isNotBlank() || repoName.isNotBlank() || githubToken.isNotBlank()
            if (hasRepo) {
                require(githubToken.isNotBlank()) { "HikotestConfig: githubToken must not be empty" }
                require(repoOwner.isNotBlank()) { "HikotestConfig: repoOwner must not be empty" }
                require(repoName.isNotBlank()) { "HikotestConfig: repoName must not be empty" }
            } else {
                require(localWasmBytes != null) {
                    "HikotestConfig: configure a repo (OTA), localBundle (offline), or both (hybrid)"
                }
            }
            require(lockedFunctions.isEmpty() || localWasmBytes != null) {
                "HikotestConfig: lockedFunctions requires localBundle (locked functions run from the embedded release)"
            }
            require(updateIntervalMs >= 10_000L) { "HikotestConfig: updateIntervalMs must be at least 10 000 ms" }
            return HikotestConfig(
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
