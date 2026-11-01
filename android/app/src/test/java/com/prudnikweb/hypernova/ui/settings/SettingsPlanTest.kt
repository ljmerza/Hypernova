package com.prudnikweb.hypernova.ui.settings

import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.ui.settings.SettingsPlan.ServerFacts
import com.prudnikweb.hypernova.ui.settings.SettingsPlan.ServerPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' parts (ui/settings/SettingsPlan.kt): one server as it always was; All servers one block per server, then the phone's. */
class SettingsPlanTest {
    private val z2 = ServerFacts("z2", LinkState.Connected, readAloud = true, files = true)
    private val shed = ServerFacts("shed", LinkState.Connected, readAloud = false, files = true)

    private fun keys(all: Boolean, servers: List<ServerFacts>, testing: Boolean = false) = SettingsPlan.parts(all, servers, testing).map { it.key }

    @Test fun oneServerKeepsItsPartsAndOrder() {
        assertEquals(
            listOf("mac", "providers", "voice", "files", "newSessions", "display", "cardSwipes", "topBar", "typing", "background", "qa", "termEngine", "about"),
            keys(false, listOf(z2), testing = true),
        )
        // Read aloud follows Voice in its item, from a server that reads aloud.
        assertEquals(SettingsPlan.Voice("z2"), SettingsPlan.parts(false, listOf(z2), false).single { it is SettingsPlan.Voice })
        assertEquals(SettingsPlan.Voice(null), SettingsPlan.parts(false, listOf(shed), false).single { it is SettingsPlan.Voice })
        // No Files from a server without the setting; no checklist until it's unlocked.
        assertEquals(
            listOf("mac", "providers", "voice", "newSessions", "display", "cardSwipes", "topBar", "typing", "background", "about"),
            keys(false, listOf(z2.copy(files = false))),
        )
        // An offline server on screen keeps its parts, as before.
        assertEquals(keys(false, listOf(z2)), keys(false, listOf(z2.copy(link = LinkState.Offline))))
    }

    @Test fun notPairedIsTheCardAndThePhonesParts() {
        assertEquals(listOf("mac", "newSessions", "display", "cardSwipes", "topBar", "typing", "termEngine", "about"), keys(false, emptyList(), testing = true))
        assertEquals(listOf("mac", "newSessions", "display", "cardSwipes", "topBar", "typing", "about"), keys(false, emptyList()))
    }

    @Test fun allServersIsOneBlockPerServerThenThePhone() {
        assertEquals(
            listOf(
                "server:z2", "providers:z2", "readAloud:z2", "files:z2", "qa:z2",
                "server:shed", "providers:shed", "files:shed", "qa:shed",
                "phone", "voice", "newSessions", "display", "cardSwipes", "topBar", "typing", "background", "connectionLog", "termEngine", "about",
            ),
            keys(true, listOf(z2, shed), testing = true),
        )
    }

    @Test fun allServersBindsEveryServerPartToItsOwnServer() {
        val parts = SettingsPlan.parts(true, listOf(z2, shed), testing = true)
        val bound = parts.filterIsInstance<ServerPart>()
        for (p in bound) assertTrue("${p.key} names its server", p.key.endsWith(":${p.server}"))
        assertEquals(setOf("z2", "shed"), bound.map { it.server }.toSet())
        // Nothing of the phone's names a server; Voice carries no Read aloud (each server has its own).
        assertEquals(SettingsPlan.Voice(null), parts.single { it is SettingsPlan.Voice })
        // Keys are unique: two servers' parts never share an item.
        assertEquals(parts.size, parts.map { it.key }.toSet().size)
    }

    @Test fun aServerThatIsntConnectedShowsItsCardAloneAndSaysSo() {
        for (state in listOf(LinkState.Offline, LinkState.Reconnecting, LinkState.Connecting, LinkState.Unpaired)) {
            val parts = SettingsPlan.parts(true, listOf(z2, shed.copy(link = state)), testing = true)
            assertEquals(listOf(SettingsPlan.Server("shed", "server:shed", waiting = true)), parts.filterIsInstance<ServerPart>().filter { it.server == "shed" })
            assertEquals(SettingsPlan.Server("z2", "server:z2", waiting = false), parts.first())
        }
    }

    @Test fun keysForAJump() {
        assertEquals("files", SettingsPlan.keyOf(SettingsJump.FILES, "z2", all = false))
        assertEquals("files:z2", SettingsPlan.keyOf(SettingsJump.FILES, "z2", all = true))
        // A failed update jumps to the server's card, which has another key with All servers.
        assertEquals("mac", SettingsPlan.jumpKey(SettingsJump.SERVER, "z2", all = false))
        assertEquals("server:z2", SettingsPlan.jumpKey(SettingsJump.SERVER, "z2", all = true))
        assertEquals("files:z2", SettingsPlan.jumpKey(SettingsJump.FILES, "z2", all = true))
        assertTrue("files:z2" in keys(true, listOf(z2, shed)))
    }
}
