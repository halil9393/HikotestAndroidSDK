package com.hik.otest

import com.dylibso.chicory.runtime.Instance

/** Value types of the Hikotest function signature, as configured in the panel. */
enum class HikoType { INT, FLOAT, STRING, BOOLEAN }

/**
 * Signature of a Hikotest function: 1..5 named parameters and a return type.
 * Must match the signature configured for the function in the Hikotest panel
 * (parameter NAMES live in the panel/manifest; calls are positional, so only
 * the types matter here).
 */
data class HikoSignature(val params: List<HikoType>, val returns: HikoType) {

    init {
        require(params.size in 1..MAX_PARAMS) {
            "HikoSignature supports 1..$MAX_PARAMS parameters, got ${params.size}"
        }
    }

    /** Legacy two-parameter form, e.g. HikoSignature(INT, INT, INT). */
    constructor(a: HikoType, b: HikoType, returns: HikoType) : this(listOf(a, b), returns)

    internal val hasString: Boolean
        get() = params.contains(HikoType.STRING) || returns == HikoType.STRING

    companion object {
        const val MAX_PARAMS = 5

        /**
         * Parses the compact signature token the panel embeds in generated
         * wrappers (sdk/kotlin/HikotestFunctions.kt), e.g. "(float,string,boolean)->float".
         */
        fun parse(token: String): HikoSignature {
            val match = requireNotNull(Regex("""^\(([a-z,]+)\)->([a-z]+)$""").matchEntire(token.trim())) {
                "Invalid signature token: \"$token\" (expected e.g. \"(int,string)->boolean\")"
            }
            val (paramsPart, returnsPart) = match.destructured
            return HikoSignature(paramsPart.split(',').map(::typeOf), typeOf(returnsPart))
        }

        private fun typeOf(name: String): HikoType = when (name.trim()) {
            "int" -> HikoType.INT
            "float" -> HikoType.FLOAT
            "string" -> HikoType.STRING
            "boolean" -> HikoType.BOOLEAN
            else -> throw IllegalArgumentException("Unknown Hikotest type: \"$name\"")
        }
    }
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
        values: List<Any>,
    ): Any {
        require(values.size == signature.params.size) {
            "Expected ${signature.params.size} argument(s) for $functionName, got ${values.size}"
        }

        if (!signature.hasString) {
            val raw = export(instance, functionName)
                .apply(*LongArray(values.size) { i -> toArg(signature.params[i], values[i]) })[0]
            return fromResult(signature.returns, raw)
        }

        val memory = instance.memory()
        val alloc = export(instance, "hiko_alloc")
        val args = ArrayList<Long>(values.size * 2)
        for ((type, value) in signature.params.zip(values)) {
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
