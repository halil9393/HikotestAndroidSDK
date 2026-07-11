package com.hik.otest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Hikotest WASM ABI glue — no emulator needed (Chicory is
 * pure JVM). Fixtures under src/test/resources are compiled from AssemblyScript
 * with the SAME wrapper codegen the panel uses at deploy time
 * (web repo: scripts/build-android-fixtures.mjs).
 */
class WasmAbiTest {

    private fun runnerFor(fixture: String): WasmRunner {
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream(fixture)) {
            "fixture not found: $fixture (run scripts/build-android-fixtures.mjs in the web repo)"
        }.readBytes()
        return WasmRunner().apply { load(bytes) }
    }

    @Test
    fun `numeric int path stays intact`() {
        val runner = runnerFor("add.wasm")
        // legacy raw call (getSumOf path)
        assertEquals(9L, runner.call("executeLogic", longArrayOf(7, 2))[0])
        // typed call, same module
        val sig = HikoSignature(HikoType.INT, HikoType.INT, HikoType.INT)
        assertEquals(9, runner.callTyped(sig, 7, 2))
    }

    @Test
    fun `float args and result use f64 raw bits`() {
        val runner = runnerFor("div.wasm")
        val sig = HikoSignature(HikoType.FLOAT, HikoType.FLOAT, HikoType.FLOAT)
        assertEquals(3.5, runner.callTyped(sig, 7.0, 2.0) as Double, 1e-9)
        assertEquals(0.5, runner.callTyped(sig, 0.1, 0.2) as Double, 1e-9)
    }

    @Test
    fun `string param and string result round-trip through linear memory`() {
        val runner = runnerFor("repeat.wasm")
        val sig = HikoSignature(HikoType.STRING, HikoType.INT, HikoType.STRING)
        assertEquals("ababab", runner.callTyped(sig, "ab", 3))
    }

    @Test
    fun `multi-byte utf8 survives the round-trip`() {
        val runner = runnerFor("repeat.wasm")
        val sig = HikoSignature(HikoType.STRING, HikoType.INT, HikoType.STRING)
        assertEquals(
            "Merhaba Dünya ✓Merhaba Dünya ✓",
            runner.callTyped(sig, "Merhaba Dünya ✓", 2),
        )
    }

    @Test
    fun `two string params returning boolean`() {
        val runner = runnerFor("coupon.wasm")
        val sig = HikoSignature(HikoType.STRING, HikoType.STRING, HikoType.BOOLEAN)
        assertEquals(true, runner.callTyped(sig, "kupon-2026", "kupon-2026"))
        assertEquals(false, runner.callTyped(sig, "kupon-2026", "kupon-2025"))
    }

    @Test
    fun `mixed float and string params returning string`() {
        val runner = runnerFor("fmt.wasm")
        val sig = HikoSignature(HikoType.FLOAT, HikoType.STRING, HikoType.STRING)
        assertEquals("TRY 118.51", runner.callTyped(sig, 118.505, "TRY"))
    }

    @Test
    fun `missing wrapper export gives an actionable error`() {
        val runner = runnerFor("add.wasm") // no hiko_run in this module
        val sig = HikoSignature(HikoType.STRING, HikoType.INT, HikoType.STRING)
        val err = runCatching { runner.callTyped(sig, "ab", 3) }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
        assertTrue(err!!.message!!.contains("Redeploy"))
    }
}
