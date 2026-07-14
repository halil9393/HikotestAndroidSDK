package com.hik.otest

import com.dylibso.chicory.runtime.Instance

/** Value types of the Hikotest function signature, as configured in the panel. */
enum class HikoType { INT, FLOAT, STRING, BOOLEAN }

/**
 * Signature of executeLogic: two parameters and a return type.
 * Must match the signature configured for the function in the Hikotest panel.
 */
data class HikoSignature(val a: HikoType, val b: HikoType, val returns: HikoType) {
    internal val hasString: Boolean
        get() = a == HikoType.STRING || b == HikoType.STRING || returns == HikoType.STRING
}

/**
 * Hikotest WASM ABI — the panel contract (web repo: src/lib/wasm/abi-core.mjs) in Kotlin.
 *
 * - int -> i32, float -> f64 (raw bits), boolean -> i32 0/1: passed directly.
 * - Strings travel through linear memory: call hiko_alloc(size) -> ptr, write UTF-8
 *   bytes, pass (ptr, len) as two args. A string RETURN is a ptr whose first 4 bytes
 *   are the little-endian byte length, followed by the UTF-8 bytes.
 * - Functions whose signature contains a string are called through the hiko_run
 *   wrapper (the panel appends it to the source automatically at deploy time);
 *   purely numeric functions keep calling executeLogic directly.
 */
internal object WasmAbi {

    /** Bundle'da string imzalı fonksiyonun sarmalayıcı export adı. */
    private fun wrapperName(functionName: String): String =
        if (functionName == "executeLogic") "hiko_run" else "hiko_run_$functionName"

    fun call(
        instance: Instance,
        functionName: String,
        signature: HikoSignature,
        a: Any,
        b: Any,
    ): Any {
        if (!signature.hasString) {
            val raw = export(instance, functionName)
                .apply(toArg(signature.a, a), toArg(signature.b, b))[0]
            return fromResult(signature.returns, raw)
        }

        val memory = instance.memory()
        val alloc = export(instance, "hiko_alloc")
        val args = ArrayList<Long>(4)
        for ((type, value) in listOf(signature.a to a, signature.b to b)) {
            if (type == HikoType.STRING) {
                val bytes = (value as? String ?: value.toString()).toByteArray(Charsets.UTF_8)
                val ptr = alloc.apply(bytes.size.toLong())[0]
                memory.write(ptr.toInt(), bytes)
                args.add(ptr)
                args.add(bytes.size.toLong())
            } else {
                args.add(toArg(type, value))
            }
        }

        val raw = export(instance, wrapperName(functionName)).apply(*args.toLongArray())[0]
        if (signature.returns == HikoType.STRING) {
            val ptr = raw.toInt()
            val len = memory.readInt(ptr) // wasm memory is little-endian
            return String(memory.readBytes(ptr + 4, len), Charsets.UTF_8)
        }
        return fromResult(signature.returns, raw)
    }

    private fun export(instance: Instance, name: String) = try {
        instance.export(name)
    } catch (e: Exception) {
        throw IllegalStateException(
            "WASM module does not export '$name'. Redeploy the function from the " +
                "Hikotest panel (the string ABI wrapper is added at deploy time).",
            e,
        )
    }

    private fun toArg(type: HikoType, v: Any): Long = when (type) {
        HikoType.INT -> (v as Number).toLong()
        HikoType.FLOAT -> java.lang.Double.doubleToRawLongBits((v as Number).toDouble())
        HikoType.BOOLEAN -> if (v == true) 1L else 0L
        HikoType.STRING -> error("string args are passed through memory, not directly")
    }

    private fun fromResult(type: HikoType, raw: Long): Any = when (type) {
        HikoType.INT -> raw.toInt()
        HikoType.FLOAT -> java.lang.Double.longBitsToDouble(raw)
        HikoType.BOOLEAN -> raw != 0L
        HikoType.STRING -> error("string results are read from memory by the caller")
    }
}
