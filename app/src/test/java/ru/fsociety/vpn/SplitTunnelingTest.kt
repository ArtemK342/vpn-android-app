package ru.fsociety.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор ввода в Правила → Сайты и логика режимов (без Android). */
class SplitTunnelingTest {

    private fun n(s: String) = SplitTunnelingManager.normalizeSite(s)

    @Test fun domainsAndLinks() {
        assertEquals("example.com", n("example.com"))
        assertEquals("example.com", n("  Example.COM "))
        assertEquals("example.com", n("https://www.example.com/path?q=1#x"))
        assertEquals("example.com", n("http://user@example.com:8080/"))
        assertEquals("example.com", n("*.example.com"))
        assertEquals("example.com", n(".example.com."))
        assertEquals("m.youtube.com", n("m.youtube.com"))
    }

    @Test fun cyrillicAndZones() {
        val cyr = n("ндфл.рф")!!                            // кириллица → punycode и обратно
        assertTrue(cyr.startsWith("xn--") && cyr.endsWith(".xn--p1ai"))
        assertEquals("ндфл.рф", SplitTunnelingManager.displaySite(cyr))
        assertEquals("ru", n(".ru"))
        assertEquals("ru", n("*.ru"))
        assertEquals("xn--p1ai", n(".рф"))
        assertEquals(".рф", SplitTunnelingManager.displaySite("xn--p1ai"))
        assertEquals(".ru", SplitTunnelingManager.displaySite("ru"))
    }

    @Test fun ipsAndSubnets() {
        assertEquals("1.2.3.4", n("1.2.3.4"))
        assertEquals("1.2.3.4", n("1.2.3.4/32"))
        assertEquals("91.108.4.0/22", n("91.108.4.0/22"))
        assertEquals("1.2.3.4", n("http://1.2.3.4/path"))   // ссылка на IP — путь отбрасывается
        assertTrue(SplitTunnelingManager.isIpRule("91.108.4.0/22"))
        assertFalse(SplitTunnelingManager.isIpRule("example.com"))
        assertNull(n("300.1.1.1"))
        assertNull(n("1.2.3.4/40"))
    }

    @Test fun garbage() {
        assertNull(n(""))
        assertNull(n("   "))
        assertNull(n("not a site"))
        assertNull(n("exa_mple.com"))
        assertNull(n("-bad.com"))
        assertNull(n("12345"))
    }

    @Test fun modesKeepTheirOwnLists() {
        var s = SplitTunnelingSettings(sitesEnabled = true)
        s = s.withSites(setOf("a.com"))
        assertEquals(setOf("a.com"), s.sitesExcluded)
        s = s.copy(sitesMode = SplitMode.INCLUDE)
        assertTrue(s.sites.isEmpty())
        assertFalse(s.sitesActive)                       // пустой «только эти» не действует
        s = s.withSites(setOf("b.com"))
        assertEquals(setOf("a.com"), s.sitesExcluded)    // список другого режима не тронут
        assertEquals(setOf("b.com"), s.sitesIncluded)
        assertTrue(s.sitesActive)
    }

    @Test fun fingerprintTracksOnlyActiveRules() {
        val off = SplitTunnelingSettings(sitesEnabled = false, sitesExcluded = setOf("a.com"))
        assertEquals(SplitTunnelingSettings().fingerprint(), off.fingerprint())
        val on = off.copy(sitesEnabled = true)
        assertTrue(on.fingerprint() != off.fingerprint())
        assertEquals(on.fingerprint(), on.copy(sitesExcluded = setOf("a.com")).fingerprint())
    }

    @Test fun wgAppRules() {
        val cfg = "[Interface]\nPrivateKey = x\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = y\n"
        val s = SplitTunnelingSettings(appsEnabled = true, appsMode = SplitMode.INCLUDE,
            appsIncluded = setOf("org.telegram.messenger", "gone.app"))
        val out = SplitTunnelingManager.applyAppRulesToWg(cfg, s) { it != "gone.app" }
        assertTrue(out.contains("IncludedApplications = org.telegram.messenger"))
        assertFalse(out.contains("gone.app"))
        // правила выключены — конфиг как есть
        assertEquals(cfg, SplitTunnelingManager.applyAppRulesToWg(cfg, s.copy(appsEnabled = false)) { true })
    }
}
