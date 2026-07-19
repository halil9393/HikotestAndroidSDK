package com.hik.otest

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hibrit/offline mod testleri (panel repo: docs/OTA_LOCK.md katman c).
 * Lokal pinli bundle ile canlı OTA bundle'ı FARKLI davranışta iki gerçek
 * wasm'dır (add=int toplama, div=float bölme; ikisi de executeLogic export
 * eder) — yönlendirme sonucu sayıdan okunur: 12 = lokal (10+2), 5 = canlı (10/2).
 * Web SDK'daki hybrid.test.mjs ile aynı senaryolar.
 */
class HybridRoutingTest {

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "fixture not found: $name (run scripts/build-android-fixtures.mjs in the web repo)"
        }.readBytes()

    private val intManifest = """
        {"abi":1,"manifest":2,"bundleVersion":"pinned-local","functions":[
          {"name":"executeLogic","signature":{"params":[{"name":"a","type":"int"},{"name":"b","type":"int"}],"returns":"int"}}
        ]}
    """.trimIndent()

    private fun liveManifest(otaLocked: Boolean) = """
        {"abi":1,"manifest":2,"bundleVersion":"live-ota","functions":[
          {"name":"executeLogic","signature":{"params":[{"name":"a","type":"float"},{"name":"b","type":"float"}],"returns":"float"}${if (otaLocked) ""","ota":false""" else ""}}
        ]}
    """.trimIndent()

    @Test
    fun `config validation - repo OR localBundle required, lockedFunctions needs localBundle`() {
        assertTrue(runCatching { HikotestConfig.Builder().build() }
            .exceptionOrNull()!!.message!!.contains("localBundle"))
        assertTrue(runCatching { HikotestConfig.Builder().repoOwner("acme").build() }
            .exceptionOrNull()!!.message!!.contains("githubToken"))
        assertTrue(runCatching {
            HikotestConfig.Builder()
                .githubToken("t").repoOwner("a").repoName("b")
                .lockedFunctions("x")
                .build()
        }.exceptionOrNull()!!.message!!.contains("lockedFunctions requires localBundle"))
    }

    @Test
    fun `fully offline mode - localBundle only, no network, every call local`() = runBlocking {
        Hikotest.configure(
            HikotestConfig.Builder()
                .localBundle(fixture("bundle.wasm"))
                .build(),
        )
        Hikotest.initialize() // context'siz: ağa ve diske hiç çıkılmaz
        assertEquals(HikotestInitState.Ready, Hikotest.initState.value)
        assertEquals(118, Hikotest.execute("calculateTax", "(int,int)->int", 100, 18))
        assertEquals("EXPRESS", Hikotest.execute("shippingLabel", "(int,boolean)->string", 5, true))
        assertTrue(Hikotest.isLocallyPinned("calculateTax"))
    }

    @Test
    fun `device list WINS - stays local even when live manifest has no lock`() = runBlocking {
        Hikotest.configure(
            HikotestConfig.Builder()
                .localBundle(fixture("add.wasm"), intManifest)
                .lockedFunctions("executeLogic")
                .build(),
        )
        Hikotest.initialize()
        // Canlı bundle geldi ve manifest'inde ota:false YOK (panel kilidi açmış olsun):
        Hikotest.loadBundle(fixture("div.wasm"), liveManifest(otaLocked = false))
        // Cihaz listesi geçerli kalır → lokal pinned modül (int toplama), canlı değil.
        assertEquals(12, Hikotest.execute("executeLogic", "(int,int)->int", 10, 2))
        assertTrue(Hikotest.isLocallyPinned("executeLogic"))
    }

    @Test
    fun `manifest ota-false routes local - flag removed reactivates OTA`() = runBlocking {
        Hikotest.configure(
            HikotestConfig.Builder()
                .localBundle(fixture("add.wasm"), intManifest)
                .build(),
        )
        Hikotest.initialize()

        Hikotest.loadBundle(fixture("div.wasm"), liveManifest(otaLocked = true))
        assertEquals(12, Hikotest.execute("executeLogic", "(int,int)->int", 10, 2)) // kilitli → lokal (10+2)
        assertTrue(Hikotest.isLocallyPinned("executeLogic"))

        Hikotest.loadBundle(fixture("div.wasm"), liveManifest(otaLocked = false))
        assertEquals(
            5.0,
            Hikotest.execute("executeLogic", "(float,float)->float", 10.0, 2.0) as Double,
            1e-9,
        ) // kilit kalktı → canlı (10/2)
        assertFalse(Hikotest.isLocallyPinned("executeLogic"))
    }

    @Test
    fun `locked function missing from local bundle - hard error, no silent OTA fallback`() = runBlocking {
        Hikotest.configure(
            HikotestConfig.Builder()
                .localBundle(fixture("add.wasm"), intManifest)
                .build(),
        )
        Hikotest.initialize()
        // Canlıda calculateTax ota:false; gömülü eski bundle'da (add.wasm) yok.
        val live = """
            {"abi":1,"manifest":2,"functions":[
              {"name":"calculateTax","signature":{"params":[{"name":"a","type":"int"},{"name":"b","type":"int"}],"returns":"int"},"ota":false},
              {"name":"checkCoupon","signature":{"params":[{"name":"a","type":"string"},{"name":"b","type":"int"}],"returns":"boolean"}}
            ]}
        """.trimIndent()
        Hikotest.loadBundle(fixture("bundle.wasm"), live)

        val err = runCatching {
            Hikotest.execute("calculateTax", "(int,int)->int", 100, 18)
        }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
        assertTrue(err!!.message!!.contains("no silent OTA fallback"))
        // Kilitsiz kardeş fonksiyon canlıdan çalışmaya devam eder.
        assertEquals(true, Hikotest.execute("checkCoupon", "(string,int)->boolean", "HIKO20", 250))
    }

    @Test
    fun `no hybrid configured - plain OTA behaviour unchanged`() = runBlocking {
        Hikotest.configure(
            HikotestConfig.Builder()
                .githubToken("t").repoOwner("acme").repoName("demo")
                .build(),
        )
        Hikotest.loadBundle(fixture("bundle.wasm"))
        assertEquals(118, Hikotest.execute("calculateTax", "(int,int)->int", 100, 18))
        assertFalse(Hikotest.isLocallyPinned("calculateTax"))
    }
}
