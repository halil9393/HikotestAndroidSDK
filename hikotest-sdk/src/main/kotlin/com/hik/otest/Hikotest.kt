package com.hik.otest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Hikotest {

    private const val TAG = "HikotestSDK"
    private const val UPDATE_INTERVAL_MS = 60_000L

    private val runner = WasmRunner()
    private var fetcher: WasmFetcher? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var updateJob: Job? = null

    /**
     * Downloads (or loads from cache) the WASM binary, initializes the runtime,
     * and starts a background job that polls for updates every minute.
     * Safe to call from any coroutine context. Calling again restarts the update loop.
     */
    suspend fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (fetcher == null) {
            fetcher = WasmFetcher(appContext)
        }
        val bytes = fetcher!!.getWasmBytes()
        withContext(Dispatchers.Default) {
            runner.load(bytes)
        }
        startUpdateLoop()
    }

    /**
     * Cancels the background update loop. Call when the SDK is no longer needed
     * (e.g., in Activity.onDestroy or Application.onTerminate).
     */
    fun shutdown() {
        updateJob?.cancel()
        updateJob = null
    }

    // --- Public API ---
    // Each function maps directly to a named WASM export.
    // Add new functions here as the WASM module grows.

    fun getSumOf(a: Int, b: Int): Int {
        checkInitialized()
        return runner.call("executeLogic", longArrayOf(a.toLong(), b.toLong()))[0].toInt()
    }

    // --- Internal ---

    private fun startUpdateLoop() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (isActive) {
                delay(UPDATE_INTERVAL_MS)
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
