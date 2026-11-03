package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DebugLog
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.main
import com.prudnikweb.hypernova.desktop.DesktopRig.platform
import com.prudnikweb.hypernova.desktop.DesktopRig.render
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.Root
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The only server's token in a keychain that's locked at the start: the whole window says so, never the pairing field. This
 * class has its own process, so its platform is the only one with this keychain.
 */
@OptIn(ExperimentalTestApi::class)
class TokenGateTest {
    private companion object {
        val shelf = StandShelf()

        init {
            DesktopRig.keychain = shelf.choice()
        }
    }

    @Test fun lockedItsTheWholeScreenThenItConnectsWithoutAPairingThenForgetIsTheOwnersChoice() = runDesktopComposeUiTest(900, 900) {
        val d = FakeDaemon("only-one", lan = true).apply { snapshot = snapshot("only-one") }
        shelf.held[Keychains.tokenItem("aaa111")] = "hn_onlyOneToken"
        shelf.locked = true
        TokenDisk.seed(DesktopRig.home, TokenDisk.server("aaa111", "https://127.0.0.1:${d.lanPort}", "only-one", null, d.fingerprint, d.lanPort))
        val before = Files.readString(DesktopRig.home.resolve("prefs/hypernova.json"))
        shell // Core up: the logger is its.
        val logger = DebugLog.logger!!
        logger.turnOn()
        try {
            setContent { Root(shell, platform.tokens, reduced = true) }
            waitForIdle()

            // What's wrong, and Try again. Not the pairing field, and nothing's been sent or written.
            onNodeWithText(Ui.desk_wait_locked.format("only-one")).assertExists()
            onNodeWithText(Ui.desk_token_retry.text()).assertExists()
            onNodeWithText(Ui.desk_wait_forget.format("only-one")).assertExists()
            onNodeWithText(Ui.desk_pair_field.text().uppercase()).assertDoesNotExist()
            render("token_wait_screen")
            assertTrue(Core.servers.isEmpty())
            assertEquals(LinkState.Unpaired, Core.live?.link?.state ?: LinkState.Unpaired)
            assertEquals(before, Files.readString(DesktopRig.home.resolve("prefs/hypernova.json")))
            assertTrue(d.requests.isEmpty())
            assertEquals("hn_onlyOneToken", shelf.token("aaa111"))

            // Unlocked: connects, nobody having paired, on a key made for it now.
            shelf.locked = false
            onNodeWithText(Ui.desk_token_retry.text()).performClick()
            until("linked") { Core.live?.link?.state == LinkState.Connected && d.issued.isNotEmpty() }
            assertEquals("Bearer hn_onlyOneToken", d.calls("POST", "/v1/device/cert").first().auth)
            assertEquals(TokenPlace.InKeychain, platform.tokens.place)
            assertEquals(KeyPlace.InKeychain, platform.keys.place)

            // Nothing of it anywhere: every file of the folder, and the debug log that Send logs would send among them.
            assertTrue(logger.flush(10_000))
            platform.folder.flush()
            val files = TokenDisk.allFiles(DesktopRig.home)
            assertTrue("the debug log's there to read", files.any { it.toString().contains("debug-logs") })
            TokenDisk.noFileHolds(DesktopRig.home, "hn_onlyOneToken")

            main { shell.settings = true }
            waitForIdle()
            onNodeWithText(Ui.desk_token_keychain.text()).assertExists()
            render("settings_token_in_keychain")
            main { shell.settings = false }
            waitForIdle()

            // The last server unpaired: its item goes, and the key, as ever.
            main { Core.clear() }
            platform.folder.flush()
            assertNull(shelf.token("aaa111"))
            assertNull(shelf.held["device-key"])
            waitForIdle()
            onNodeWithText(Ui.desk_pair_field.text().uppercase()).assertExists()
        } finally {
            logger.turnOff()
            DesktopRig.reset()
            runCatching { d.close() }
        }
        assertFalse(Files.exists(platform.keyFile))
    }
}
