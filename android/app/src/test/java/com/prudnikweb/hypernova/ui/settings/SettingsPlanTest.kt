package com.prudnikweb.hypernova.ui.settings

import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.ui.settings.SettingsPlan.PhonePage
import com.prudnikweb.hypernova.ui.settings.SettingsPlan.ServerFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' parts (ui/settings/SettingsPlan.kt): one tab, a row per server, and each server's own page. */
class SettingsPlanTest {
    private val z2 = ServerFacts("z2", LinkState.Connected, readAloud = true, files = true)
    private val shed = ServerFacts("shed", LinkState.Connected, readAloud = false, files = true)

    private fun tab(servers: List<String>, testing: Boolean = false) = SettingsPlan.parts(servers, testing).map { it.key }
    private fun page(s: ServerFacts, all: Boolean = false, testing: Boolean = false) = SettingsPlan.serverParts(s, all, testing).map { it.key }

    @Test fun theTabIsTheServersThenThePhone() {
        assertEquals(listOf("servers", "phone", "newSessions", "display", "about"), tab(listOf("z2")))
        // The Terminal engine trial only once the testing tools are unlocked, just before About.
        assertEquals(listOf("servers", "phone", "newSessions", "display", "termEngine", "about"), tab(listOf("z2"), testing = true))
    }

    @Test fun aRowPerServerInTheOrderGiven() {
        val rows = SettingsPlan.parts(listOf("z2", "shed", "gym"), false).filterIsInstance<SettingsPlan.Servers>().single()
        assertEquals(listOf("z2", "shed", "gym"), rows.ids)
    }

    @Test fun oneServerIsTheSameTabWithOneRow() {
        assertEquals(tab(listOf("z2", "shed")), tab(listOf("z2")))
    }

    @Test fun thePhonesSubPages() {
        fun pages(servers: List<String>) = SettingsPlan.parts(servers, false).filterIsInstance<SettingsPlan.Phone>().single().pages
        assertEquals(listOf(PhonePage.Notifications, PhonePage.TopBar, PhonePage.CardSwipes, PhonePage.TypingAndVoice, PhonePage.Logs), pages(listOf("z2")))
        // No server paired: no Notifications to have, as before.
        assertEquals(listOf(PhonePage.TopBar, PhonePage.CardSwipes, PhonePage.TypingAndVoice, PhonePage.Logs), pages(emptyList()))
        assertEquals(emptyList<String>(), SettingsPlan.parts(emptyList(), false).filterIsInstance<SettingsPlan.Servers>().single().ids)
    }

    @Test fun aServersPageHoldsItsOwnParts() {
        assertEquals(listOf("mac", "providers", "readAloud", "files", "qa"), page(z2, testing = true))
        // No Read aloud from a server without voices, no Files without the setting, no checklist until it's unlocked.
        assertEquals(listOf("mac", "providers", "files"), page(shed))
        assertEquals(listOf("mac", "providers"), page(shed.copy(files = false)))
        // The page is the same whichever way it was opened, for a server that's connected.
        assertEquals(page(z2, all = false, testing = true), page(z2, all = true, testing = true))
    }

    @Test fun everyPartOfAPageIsBoundToItsServer() {
        val parts = SettingsPlan.serverParts(z2, all = true, testing = true)
        for (p in parts) assertEquals(p.key, "z2", p.server)
        assertEquals(parts.size, parts.map { it.key }.toSet().size)
    }

    @Test fun withAllServersOneThatIsntConnectedShowsItsCardAloneAndSaysSo() {
        for (state in listOf(LinkState.Offline, LinkState.Reconnecting, LinkState.Connecting, LinkState.Unpaired)) {
            assertEquals(listOf(SettingsPlan.Server("shed", waiting = true)), SettingsPlan.serverParts(shed.copy(link = state), all = true, testing = true))
        }
        assertEquals(SettingsPlan.Server("z2", waiting = false), SettingsPlan.serverParts(z2, all = true, testing = true).first())
    }

    @Test fun theOneServerOnScreenKeepsItsPartsWhileItCantBeReached() {
        assertEquals(page(z2), page(z2.copy(link = LinkState.Offline)))
        assertEquals(SettingsPlan.Server("z2", waiting = false), SettingsPlan.serverParts(z2.copy(link = LinkState.Offline), all = false, testing = false).first())
    }

    @Test fun whatAJumpAsksForIsAPartOfAServersPage() {
        assertTrue(SettingsJump.FILES in page(z2))
        assertTrue(SettingsJump.SERVER in page(z2))
        assertTrue("a server that isn't connected still has its card", SettingsJump.SERVER in page(z2.copy(link = LinkState.Offline), all = true))
    }
}
