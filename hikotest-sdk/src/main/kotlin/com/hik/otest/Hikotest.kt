package com.hik.otest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class HikotestInitState {
    object Idle : HikotestInitState()
    object Loading : HikotestInitState()
    object Ready : HikotestInitState()
    data class Error(val message: String) : HikotestInitState()
}

/**
 * Hikotest OTA SDK entry point.
 *
 * ## Hybrid / offline mode (OTA lock, device-side guarantee)
 *
 * Embed a release you trust (release.wasm + manifest.json from the panel,
 * shipped in your app's `assets/`) and pass it at configure time:
 *
 * ```kotlin
 * Hikotest.configure(HikotestConfig.Builder()
 *     .githubToken(token).repoOwner("acme").repoName("hikotest-build")
 *     .localBundle(assets.open("release.wasm").readBytes(),
 *                  assets.open("manifest.json").bufferedReader().readText())
 *     .lockedFunctions("applyPayment") // device-side lock list (optional)
 *     .build())
 * ```
 *
 * Call routing: a function runs from the LOCAL pinned bundle when the live
 * manifest marks it `ota:false` OR it is in `lockedFunctions`; everything else
 * runs from the live OTA bundle (hot-reloaded as usual). The union is one-way —
 * the device list wins, the panel cannot remotely unlock a device-listed
 * function. A locked function missing from the local bundle is a hard error
 * (no silent OTA fallback).
 *
 * Fully offline mode is the degenerate case: configure ONLY `localBundle`
 * (no repo) — the SDK never touches the network.
 */
object Hikotest {

    private const val TAG = "HikotestSDK"

    private var runner = WasmRunner()
    private var pinnedRunner = WasmRunner()
    private var fetcher: WasmFetcher? = null
    private var config: HikotestConfig? = null

    private var deviceLocked: Set<String> = emptySet()

    // Canlı manifest'in ota:false bayrakları — hot-reload'da güncellenir.
    @Volatile
    private var manifestLocked: Set<String> = emptySet()

    private val _initState = MutableStateFlow<HikotestInitState>(HikotestInitState.Idle)
    val initState: StateFlow<HikotestInitState> = _initState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var updateJob: Job? = null

    /**
     * Must be called before [initialize]. Calling again replaces the config and
     * resets the fetcher/runtimes so the next [initialize] picks up the new settings.
     */
    fun configure(config: HikotestConfig) {
        this.config = config
        fetcher = null
        runner = WasmRunner()
        pinnedRunner = WasmRunner()
        deviceLocked = config.lockedFunctions
        manifestLocked = emptySet()
        _initState.value = HikotestInitState.Idle
    }

    /**
     * Loads the embedded local bundle (if configured), then downloads (or loads
     * from cache) the live WASM binary, initializes the runtime, and starts a
     * background job that polls for updates. Safe to call from any coroutine
     * context. Calling again restarts the update loop.
     */
    suspend fun initialize(context: Context) = doInitialize(context)

    /**
     * Fully offline initialization — only valid when no repo is configured
     * (localBundle-only config). Never touches the network or the disk cache.
     */
    suspend fun initialize() = doInitialize(null)

    private suspend fun doInitialize(context: Context?) {
        val cfg = checkNotNull(config) {
            "Call Hikotest.configure(config) before initialize()."
        }
        _initState.value = HikotestInitState.Loading
        try {
            // Local first: locked functions stay callable even if the OTA fetch fails.
            val localBytes = cfg.localWasmBytes
            if (localBytes != null && !pinnedRunner.isLoaded) {
                withContext(Dispatchers.Default) { pinnedRunner.load(localBytes) }
            }
            if (cfg.hasRemote) {
                val appContext = checkNotNull(context) {
                    "initialize(context) is required when a repo is configured (OTA mode)."
                }.applicationContext
                if (fetcher == null) {
                    fetcher = WasmFetcher(appContext, cfg)
                }
                val bundle = fetcher!!.getBundle()
                verifyRelease(bundle, cfg.verify)
                withContext(Dispatchers.Default) {
                    runner.load(bundle.wasmBytes)
                }
                manifestLocked = HikoManifest.lockedFunctions(bundle.manifestJson)
                startUpdateLoop(cfg.updateIntervalMs)
            }
            _initState.value = HikotestInitState.Ready
        } catch (e: Exception) {
            _initState.value = HikotestInitState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Loads a LIVE bundle from bytes you provide (tests, offline cache, or a
     * backend proxy in front of a private repo). Skips the GitHub fetcher
     * entirely; hybrid routing still applies against this bundle's manifest.
     */
    suspend fun loadBundle(wasmBytes: ByteArray, manifestJson: String? = null) {
        withContext(Dispatchers.Default) { runner.load(wasmBytes) }
        manifestLocked = HikoManifest.lockedFunctions(manifestJson)
        _initState.value = HikotestInitState.Ready
    }

    /**
     * Cancels the background update loop. Call in Application.onTerminate().
     */
    fun shutdown() {
        updateJob?.cancel()
        updateJob = null
    }

    // --- Public API ---

    fun getSumOf(a: Int, b: Int): Int {
        return runnerFor("executeLogic").call("executeLogic", longArrayOf(a.toLong(), b.toLong()))[0].toInt()
    }

    /** True when calls to [functionName] are served from the embedded local bundle. */
    fun isLocallyPinned(functionName: String): Boolean {
        if (!pinnedRunner.isLoaded) return false
        if (!runner.isLoaded) return true // offline: everything is local
        return functionName in deviceLocked || functionName in manifestLocked
    }

    /**
     * Signature-aware call. [signature] must match the function's signature as
     * configured in the Hikotest panel (int/float/string/boolean, 1..5 positional
     * parameters). Strings travel through the Hikotest WASM ABI (UTF-8 over
     * linear memory) transparently.
     *
     * Example for a (string, int) -> string function:
     * `Hikotest.execute(HikoSignature(HikoType.STRING, HikoType.INT, HikoType.STRING), "ab", 3) as String`
     */
    fun execute(signature: HikoSignature, vararg args: Any): Any {
        return runnerFor("executeLogic").callTyped(signature, args.toList())
    }

    /**
     * Calls [functionName] in a multi-function bundle (each deployed function is
     * exported under its own name in the live release.wasm).
     *
     * Example: `Hikotest.execute("checkCoupon", HikoSignature(HikoType.STRING, HikoType.INT, HikoType.BOOLEAN), "HIKO20", 250)`
     */
    fun execute(functionName: String, signature: HikoSignature, vararg args: Any): Any {
        return runnerFor(functionName).callTyped(signature, args.toList(), functionName)
    }

    /**
     * Token form used by the panel-generated wrappers (sdk/kotlin/HikotestFunctions.kt):
     * [signature] is the compact type list, e.g. "(float,string,boolean)->float".
     *
     * Example: `Hikotest.execute("applyCampaign", "(float,string,boolean)->float", 1000.0, "VIP30", true) as Double`
     */
    fun execute(functionName: String, signature: String, vararg args: Any): Any {
        return runnerFor(functionName).callTyped(HikoSignature.parse(signature), args.toList(), functionName)
    }

    // --- Internal ---

    /** Routes a call: device lock list + live manifest `ota:false` → local pinned module. */
    private fun runnerFor(functionName: String): WasmRunner {
        if (!pinnedRunner.isLoaded) {
            // No hybrid configured — plain OTA behaviour.
            check(runner.isLoaded) { "Hikotest is not initialized. Call initialize() first." }
            return runner
        }
        if (!runner.isLoaded) return pinnedRunner // offline mode (or OTA not loaded yet)
        if (functionName !in deviceLocked && functionName !in manifestLocked) return runner
        check(pinnedRunner.hasExport(functionName)) {
            "\"$functionName\" is OTA-locked but missing from the embedded local bundle — " +
                "ship an app update embedding a release that contains it (no silent OTA fallback)."
        }
        return pinnedRunner
    }

    /** docs/WASM_INTEGRITY.md §6: off = skip; warn = log + run; enforce = throw. */
    private fun verifyRelease(bundle: FetchedBundle, mode: VerifyMode) {
        val manifestBytes = bundle.manifestJson?.toByteArray(Charsets.UTF_8)
        val result = HikoIntegrity.evaluate(mode, bundle.integrityJson, bundle.wasmBytes, manifestBytes) ?: return
        if (!result.ok) {
            Log.w(TAG, "Hikotest integrity check failed: ${result.reason} (verify=warn → running anyway)")
        }
    }

    private fun startUpdateLoop(intervalMs: Long) {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    val bundle = fetcher?.checkForUpdate()
                    if (bundle != null) {
                        verifyRelease(bundle, config?.verify ?: VerifyMode.OFF)
                        withContext(Dispatchers.Default) {
                            runner.load(bundle.wasmBytes)
                        }
                        manifestLocked = HikoManifest.lockedFunctions(bundle.manifestJson)
                        Log.i(TAG, "WASM hot-reloaded with new version")
                    } else {
                        Log.d(TAG, "Update check: no new version")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Update check failed: ${e.message}")
                }
            }
        }
    }
}
