package com.hik.otest

class HikotestConfig private constructor(
    val githubToken: String,
    val repoOwner: String,
    val repoName: String,
    val environment: String,
    val isBeta: Boolean,
    val updateIntervalMs: Long,
    val wasmAssetName: String,
) {

    class Builder {
        private var githubToken: String = ""
        private var repoOwner: String = ""
        private var repoName: String = ""
        private var environment: String = "production"
        private var isBeta: Boolean = false
        private var updateIntervalMs: Long = 60_000L
        private var wasmAssetName: String = "release.wasm"

        fun githubToken(token: String) = apply { this.githubToken = token }
        fun repoOwner(owner: String) = apply { this.repoOwner = owner }
        fun repoName(name: String) = apply { this.repoName = name }
        fun environment(env: String) = apply { this.environment = env }
        fun isBeta(beta: Boolean) = apply { this.isBeta = beta }
        fun updateIntervalMs(ms: Long) = apply { this.updateIntervalMs = ms }
        fun wasmAssetName(name: String) = apply { this.wasmAssetName = name }

        fun build(): HikotestConfig {
            require(githubToken.isNotBlank()) { "HikotestConfig: githubToken must not be empty" }
            require(repoOwner.isNotBlank()) { "HikotestConfig: repoOwner must not be empty" }
            require(repoName.isNotBlank()) { "HikotestConfig: repoName must not be empty" }
            require(updateIntervalMs >= 10_000L) { "HikotestConfig: updateIntervalMs must be at least 10 000 ms" }
            return HikotestConfig(
                githubToken = githubToken,
                repoOwner = repoOwner,
                repoName = repoName,
                environment = environment,
                isBeta = isBeta,
                updateIntervalMs = updateIntervalMs,
                wasmAssetName = wasmAssetName,
            )
        }
    }
}
