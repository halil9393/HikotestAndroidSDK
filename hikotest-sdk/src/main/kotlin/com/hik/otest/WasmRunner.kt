package com.hik.otest

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser

// Chicory 1.x API: ExportFunction.apply(long... args) -> long[]
internal class WasmRunner {

    // @Volatile ensures the new Instance is visible to all threads immediately after hot-reload.
    // Reference assignment is atomic on JVM, so in-flight calls safely complete on the old instance.
    @Volatile
    private var instance: Instance? = null

    fun load(wasmBytes: ByteArray) {
        val newInstance = Instance.builder(Parser.parse(wasmBytes)).build()
        instance = newInstance
    }

    fun call(name: String, args: LongArray): LongArray {
        return requireNotNull(instance) { "call load() before invoking functions" }
            .export(name)
            .apply(*args)
    }

    val isLoaded: Boolean get() = instance != null
}
