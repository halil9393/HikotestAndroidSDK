package com.hik.otest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HikotestConfig doğrulama testleri — Dilim B panel yönlendirmesi (additive).
 * Web SDK'daki config.test.mjs ile aynı senaryolar: panel yolu (önerilen) ile
 * legacy github yolu ayrık; ikisi birlikte verilince reddedilir; panel çifti
 * (panelBaseUrl + projectId) birlikte zorunlu; panelBaseUrl sondaki '/' kırpılır.
 */
class HikotestConfigTest {

    @Test
    fun panelPathResolves() {
        val cfg = HikotestConfig.Builder()
            .panelBaseUrl("https://hikotest.app")
            .projectId("79f64c7a-0000-0000-0000-000000000000")
            .build()
        assertTrue(cfg.hasPanel)
        assertTrue(cfg.hasRemote)
        assertEquals("https://hikotest.app", cfg.panelBaseUrl)
        assertEquals("79f64c7a-0000-0000-0000-000000000000", cfg.projectId)
        assertTrue("legacy github alanları boş kalmalı", cfg.repoOwner.isEmpty())
    }

    @Test
    fun panelBaseUrlTrailingSlashTrimmed() {
        val cfg = HikotestConfig.Builder()
            .panelBaseUrl("https://hikotest.app///")
            .projectId("p1")
            .build()
        assertEquals("https://hikotest.app", cfg.panelBaseUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun panelBaseUrlWithoutProjectIdRejected() {
        HikotestConfig.Builder().panelBaseUrl("https://hikotest.app").build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun projectIdWithoutPanelBaseUrlRejected() {
        HikotestConfig.Builder().projectId("p1").build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun panelAndGithubTogetherRejected() {
        HikotestConfig.Builder()
            .panelBaseUrl("https://hikotest.app")
            .projectId("p1")
            .githubToken("t")
            .repoOwner("acme")
            .repoName("build")
            .build()
    }

    @Test
    fun legacyGithubPathStillResolves() {
        val cfg = HikotestConfig.Builder()
            .githubToken("t")
            .repoOwner("acme")
            .repoName("build")
            .build()
        assertFalse(cfg.hasPanel)
        assertTrue(cfg.hasRemote)
        assertEquals("acme", cfg.repoOwner)
    }

    @Test
    fun offlineLocalBundleHasNoRemote() {
        val cfg = HikotestConfig.Builder()
            .localBundle(ByteArray(4))
            .build()
        assertFalse(cfg.hasPanel)
        assertFalse(cfg.hasRemote)
    }
}
