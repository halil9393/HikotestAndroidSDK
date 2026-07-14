package com.hik.otest

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType

// Chicory 1.x API: ExportFunction.apply(long... args) -> long[]
internal class WasmRunner {

    // @Volatile ensures the new Instance is visible to all threads immediately after hot-reload.
    // Reference assignment is atomic on JVM, so in-flight calls safely complete on the old instance.
    @Volatile
    private var instance: Instance? = null

    fun load(wasmBytes: ByteArray) {
        // AssemblyScript modules may import env.abort (string/assert code paths).
        // Providing it unconditionally is safe: unused host imports are ignored.
        val abort = HostFunction(
            "env",
            "abort",
            FunctionType.of(
                listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                emptyList(),
            ),
        ) { _, _ -> throw IllegalStateException("WASM module aborted") }

        val newInstance = Instance.builder(Parser.parse(wasmBytes))
            .withImportValues(ImportValues.builder().addFunction(abort).build())
            .build()
        instance = newInstance
    }

    fun call(name: String, args: LongArray): LongArray {
        return current().export(name).apply(*args)
    }

    /**
     * Signature-aware call following the Hikotest WASM ABI (see [WasmAbi]):
     * strings travel through linear memory, numerics/booleans directly.
     * [functionName] selects the export in a multi-function bundle; the default
     * targets legacy single-function modules.
     */
    fun callTyped(
        signature: HikoSignature,
        a: Any,
        b: Any,
        functionName: String = "executeLogic",
    ): Any {
        return WasmAbi.call(current(), functionName, signature, a, b)
    }

    val isLoaded: Boolean get() = instance != null

    private fun current(): Instance =
        requireNotNull(instance) { "call load() before invoking functions" }
}
