package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.main
import com.prudnikweb.hypernova.desktop.DesktopRig.render
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.Root
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keychain locked at the start, one server's token inside it and another's in the file: through the real window, the real
 * hubs and real mutual TLS on loopback. This class has its own process, so its platform is the only one with this keychain.
 */
@OptIn(ExperimentalTestApi::class)
class TokenWaitTest {
    private companion object {
        val shelf = StandShelf()

        init {
            DesktopRig.keychain = shelf.choice()
        }
    }

    @Test fun theOtherServerConnectsAndTheLockedOneComesBackOnTryAgain() = runDesktopComposeUiTest(900, 1000) {
        val inKeychain = FakeDaemon("locked-one", lan = true).apply { snapshot = snapshot("locked-one") }
        val inFile = FakeDaemon("plain-one", lan = true).apply { snapshot = snapshot("plain-one") }
        // As an earlier run left them: the locked one first, on screen when it closed.
        shelf.held[Keychains.tokenItem("aaa111")] = "hn_lockedOneToken"
        shelf.locked = true
        TokenDisk.seed(
            DesktopRig.home,
            TokenDisk.server("aaa111", "https://127.0.0.1:${inKeychain.lanPort}", "locked-one", null, inKeychain.fingerprint, inKeychain.lanPort),
            TokenDisk.server("bbb222", "https://127.0.0.1:${inFile.lanPort}", "plain-one", "hn_plainOneToken", inFile.fingerprint, inFile.lanPort),
        )
        shell // Core up, before the window, as Main.kt does.
        try {
            setContent { Root(shell, DesktopRig.platform.tokens, reduced = true) }
            val tokens = DesktopRig.platform.tokens

            // The other one connects, with no pairing and no prompt, and the window says what's wrong.
            assertEquals(listOf("bbb222"), Core.servers.map { it.id })
            until("the plain one linked") { Core.live?.link?.state == LinkState.Connected }
            waitForIdle()
            onNodeWithText(Ui.desk_wait_title.text().uppercase()).assertExists()
            onNodeWithText(Ui.desk_wait_locked.format("locked-one")).assertExists()
            render("token_wait_bar")

            // Nothing about it has been said to its server, as nothing could be: no token, no connection, no wrong one.
            assertTrue(inKeychain.requests.none { it.auth != null })
            assertEquals("its item is left alone", "hn_lockedOneToken", shelf.token("aaa111"))

            // Settings says it too, in the key's section.
            main { shell.settings = true }
            waitForIdle()
            onNodeWithText(Ui.desk_key_title.text().uppercase()).assertExists()
            render("settings_token_waiting")
            main { shell.settings = false }
            waitForIdle()

            // Saves meanwhile don't lose it.
            main { Core.switchTo("bbb222") }
            DesktopRig.platform.folder.flush()
            assertEquals(listOf("aaa111", "bbb222"), TokenDisk.servers(DesktopRig.home).map { it.getString("id") })
            assertTrue(TokenDisk.servers(DesktopRig.home)[0].getBoolean("tokenInKeychain"))

            // Try again, still locked: the same words.
            onNodeWithText(Ui.desk_token_retry.text()).performClick()
            until("asked") { !tokens.retrying }
            onNodeWithText(Ui.desk_wait_locked.format("locked-one")).assertExists()

            // Unlocked, and try again: back, with its own token, on its own certificate. Nobody paired.
            shelf.locked = false
            onNodeWithText(Ui.desk_token_retry.text()).performClick()
            until("back") { tokens.waiting.isEmpty() && Core.servers.size == 2 }
            assertEquals("in its place", listOf("aaa111", "bbb222"), Core.servers.map { it.id })
            until("the locked one linked") { inKeychain.issued.isNotEmpty() && inKeychain.calls("GET", "/v1/ws").any { it.overLan && it.clientCert != null } }
            assertEquals("Bearer hn_lockedOneToken", inKeychain.calls("POST", "/v1/device/cert").first().auth)
            assertEquals("each its own", "Bearer hn_plainOneToken", inFile.calls("POST", "/v1/device/cert").first().auth)
            waitForIdle()
            onNodeWithText(Ui.desk_wait_title.text().uppercase()).assertDoesNotExist()

            // And what was in the file moved across, so no file holds either.
            DesktopRig.platform.folder.flush()
            assertEquals("hn_plainOneToken", shelf.token("bbb222"))
            assertEquals(TokenPlace.InKeychain, tokens.place)
            TokenDisk.noFileHolds(DesktopRig.home, "hn_lockedOneToken", "hn_plainOneToken")
            assertEquals(listOf("aaa111", "bbb222"), TokenDisk.servers(DesktopRig.home).map { it.getString("id") })

            // Forget one: only its item goes.
            main { Core.forget("aaa111") }
            DesktopRig.platform.folder.flush()
            assertNull(shelf.token("aaa111"))
            assertEquals("hn_plainOneToken", shelf.token("bbb222"))
            assertEquals(listOf("bbb222"), TokenDisk.servers(DesktopRig.home).map { it.getString("id") })
            assertFalse("no key lost", shelf.held["device-key"].isNullOrEmpty())
        } finally {
            DesktopRig.reset()
            inKeychain.close()
            inFile.close()
        }
        DesktopRig.platform.folder.flush()
        assertTrue("the last one's item went with it", shelf.tokenItems.isEmpty())
        assertNull("and the key with the last one", shelf.held["device-key"])
    }
}
