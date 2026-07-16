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
        // typed call, same module (legacy 2-param constructor still works)
        val sig = HikoSignature(HikoType.INT, HikoType.INT, HikoType.INT)
        assertEquals(9, runner.callTyped(sig, listOf(7, 2)))
    }

    @Test
    fun `float args and result use f64 raw bits`() {
        val runner = runnerFor("div.wasm")
        val sig = HikoSignature(HikoType.FLOAT, HikoType.FLOAT, HikoType.FLOAT)
        assertEquals(3.5, runner.callTyped(sig, listOf(7.0, 2.0)) as Double, 1e-9)
        assertEquals(0.5, runner.callTyped(sig, listOf(0.1, 0.2)) as Double, 1e-9)
    }

    @Test
    fun `string param and string result round-trip through linear memory`() {
        val runner = runnerFor("repeat.wasm")
        val sig = HikoSignature(HikoType.STRING, HikoType.INT, HikoType.STRING)
        assertEquals("ababab", runner.callTyped(sig, listOf("ab", 3)))
    }

    @Test
    fun `multi-byte utf8 survives the round-trip`() {
        val runner = runnerFor("repeat.wasm")
        val sig = HikoSignature(HikoType.STRING, HikoType.INT, HikoType.STRING)
        assertEquals(
            "Merhaba Dünya ✓Merhaba Dünya ✓",
            runner.callTyped(sig, listOf("Merhaba Dünya ✓", 2)),
        )
    }

    @Test
    fun `two string params returning boolean`() {
        val runner = runnerFor("coupon.wasm")
        val sig = HikoSignature(HikoType.STRING, HikoType.STRING, HikoType.BOOLEAN)
        assertEquals(true, runner.callTyped(sig, listOf("kupon-2026", "kupon-2026")))
        assertEquals(false, runner.callTyped(sig, listOf("kupon-2026", "kupon-2025")))
    }

    @Test
    fun `mixed float and string params returning string`() {
        val runner = runnerFor("fmt.wasm")
        val sig = HikoSignature(HikoType.FLOAT, HikoType.STRING, HikoType.STRING)
        assertEquals("TRY 118.51", runner.callTyped(sig, listOf(118.505, "TRY")))
    }

    @Test
    fun `signature token parses into the panel signature`() {
        assertEquals(
            HikoSignature(listOf(HikoType.FLOAT, HikoType.STRING, HikoType.BOOLEAN), HikoType.FLOAT),
            HikoSignature.parse("(float,string,boolean)->float"),
        )
        assertEquals(
            HikoSignature(listOf(HikoType.INT), HikoType.INT),
            HikoSignature.parse("(int)->int"),
        )
        val err = runCatching { HikoSignature.parse("float->float") }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
    }

    @Test
    fun `bundle exposes every function under its own name`() {
        // bundle.wasm = demo fonksiyon paketi — canlı release.wasm ile aynı üretici
        val runner = runnerFor("bundle.wasm")

        val tax = HikoSignature(HikoType.INT, HikoType.INT, HikoType.INT)
        assertEquals(118, runner.callTyped(tax, listOf(100, 18), "calculateTax"))

        val coupon = HikoSignature(HikoType.STRING, HikoType.INT, HikoType.BOOLEAN)
        assertEquals(true, runner.callTyped(coupon, listOf("HIKO20", 250), "checkCoupon"))
        assertEquals(false, runner.callTyped(coupon, listOf("HIKO20", 100), "checkCoupon"))

        val shipping = HikoSignature(HikoType.INT, HikoType.BOOLEAN, HikoType.STRING)
        assertEquals("EXPRESS", runner.callTyped(shipping, listOf(5, true), "shippingLabel"))
        assertEquals("PALLET", runner.callTyped(shipping, listOf(40, false), "shippingLabel"))

        val discount = HikoSignature(HikoType.FLOAT, HikoType.INT, HikoType.FLOAT)
        assertEquals(800.0, runner.callTyped(discount, listOf(1000.0, 3), "calculateDiscount") as Double, 1e-9)

        val loyalty = HikoSignature(HikoType.INT, HikoType.BOOLEAN, HikoType.INT)
        assertEquals(400, runner.callTyped(loyalty, listOf(1000, true), "loyaltyPoints"))
    }

    @Test
    fun `three-param function with a string in the middle`() {
        // applyCampaign(price: float, campaignCode: string, vip: boolean) -> float —
        // değişken sayılı imzanın (arity > 2) uçtan uca kanıtı; imza panel token'ından.
        val runner = runnerFor("bundle.wasm")
        val sig = HikoSignature.parse("(float,string,boolean)->float")
        assertEquals(700.0, runner.callTyped(sig, listOf(1000.0, "VIP30", true), "applyCampaign") as Double, 1e-9)
        assertEquals(180.0, runner.callTyped(sig, listOf(200.0, "SAVE10", false), "applyCampaign") as Double, 1e-9)
        assertEquals(100.0, runner.callTyped(sig, listOf(100.0, "YOK", false), "applyCampaign") as Double, 1e-9)
    }

    @Test
    fun `missing wrapper export gives an actionable error`() {
        val runner = runnerFor("add.wasm") // no hiko_run in this module
        val sig = HikoSignature(HikoType.STRING, HikoType.INT, HikoType.STRING)
        val err = runCatching { runner.callTyped(sig, listOf("ab", 3)) }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
        assertTrue(err!!.message!!.contains("Redeploy"))
    }
}
