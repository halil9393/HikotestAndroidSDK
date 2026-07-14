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

object Hikotest {

    private const val TAG = "HikotestSDK"

    private val runner = WasmRunner()
    private var fetcher: WasmFetcher? = null
    private var config: HikotestConfig? = null

    private val _initState = MutableStateFlow<HikotestInitState>(HikotestInitState.Idle)
    val initState: StateFlow<HikotestInitState> = _initState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var updateJob: Job? = null

    /**
     * Must be called before [initialize]. Calling again replaces the config and
     * resets the fetcher so the next [initialize] picks up the new settings.
     */
    fun configure(config: HikotestConfig) {
        this.config = config
        fetcher = null
        _initState.value = HikotestInitState.Idle
    }

    /**
     * Downloads (or loads from cache) the WASM binary, initializes the runtime,
     * and starts a background job that polls for updates.
     * Safe to call from any coroutine context. Calling again restarts the update loop.
     */
    suspend fun initialize(context: Context) {
        val cfg = checkNotNull(config) {
            "Call Hikotest.configure(config) before initialize()."
        }
        _initState.value = HikotestInitState.Loading
        try {
            val appContext = context.applicationContext
            if (fetcher == null) {
                fetcher = WasmFetcher(appContext, cfg)
            }
            val bytes = fetcher!!.getWasmBytes()
            withContext(Dispatchers.Default) {
                runner.load(bytes)
            }
            startUpdateLoop(cfg.updateIntervalMs)
            _initState.value = HikotestInitState.Ready
        } catch (e: Exception) {
            _initState.value = HikotestInitState.Error(e.message ?: "Unknown error")
            throw e
        }
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
        checkInitialized()
        return runner.call("executeLogic", longArrayOf(a.toLong(), b.toLong()))[0].toInt()
    }

    /**
     * Signature-aware call. [signature] must match the function's signature as
     * configured in the Hikotest panel (int/float/string/boolean). Strings travel
     * through the Hikotest WASM ABI (UTF-8 over linear memory) transparently.
     *
     * Example for a (string, int) -> string function:
     * `Hikotest.execute(HikoSignature(HikoType.STRING, HikoType.INT, HikoType.STRING), "ab", 3) as String`
     */
    fun execute(signature: HikoSignature, a: Any, b: Any): Any {
        checkInitialized()
        return runner.callTyped(signature, a, b)
    }

    /**
     * Calls [functionName] in a multi-function bundle (each deployed function is
     * exported under its own name in the live release.wasm).
     *
     * Example: `Hikotest.execute("checkCoupon", HikoSignature(HikoType.STRING, HikoType.INT, HikoType.BOOLEAN), "HIKO20", 250)`
     */
    fun execute(functionName: String, signature: HikoSignature, a: Any, b: Any): Any {
        checkInitialized()
        return runner.callTyped(signature, a, b, functionName)
    }

    // --- Internal ---

    private fun startUpdateLoop(intervalMs: Long) {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    val newBytes = fetcher?.checkForUpdate()
                    if (newBytes != null) {
                        withContext(Dispatchers.Default) {
                            runner.load(newBytes)
                        }
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

    private fun checkInitialized() {
        check(runner.isLoaded) { "Hikotest is not initialized. Call initialize() first." }
    }
}
