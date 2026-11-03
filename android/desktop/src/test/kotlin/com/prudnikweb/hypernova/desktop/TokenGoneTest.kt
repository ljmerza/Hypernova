package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.platform
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.Root
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A keychain that answers and has nothing for this server (reset, or this folder moved): Try again changes nothing, and
 * Forget, the owner's own click, leads to the pairing field, where the same computer pairs again. Its own process, for [Core].
 */
@OptIn(ExperimentalTestApi::class)
class TokenGoneTest {
    private companion object {
        val shelf = StandShelf()

        init {
            DesktopRig.keychain = shelf.choice()
        }
    }

    @Test fun itsTheOwnerWhoEndsTheWaitAndThenItPairsAgain() = runDesktopComposeUiTest(900, 900) {
        val d = FakeDaemon("gone-one", lan = true).apply { snapshot = snapshot("gone-one") }
        TokenDisk.seed(DesktopRig.home, TokenDisk.server("aaa111", "https://127.0.0.1:${d.lanPort}", "gone-one", null, d.fingerprint, d.lanPort))
        shell // Core up, before the window, as Main.kt does.
        try {
            setContent { Root(shell, platform.tokens, reduced = true) }
            waitForIdle()
            onNodeWithText(Ui.desk_wait_gone.format("gone-one")).assertExists()
            onNodeWithText(Ui.desk_token_retry.text()).performClick()
            until("asked") { !platform.tokens.retrying }
            onNodeWithText(Ui.desk_wait_gone.format("gone-one")).assertExists()
            assertTrue(TokenDisk.servers(DesktopRig.home).single().getBoolean("tokenInKeychain"))

            onNodeWithText(Ui.desk_wait_forget.format("gone-one")).performClick()
            until("forgotten") { platform.tokens.waiting.isEmpty() }
            waitForIdle()
            onNodeWithText(Ui.desk_pair_field.text().uppercase()).assertExists()
            platform.folder.flush()
            assertTrue(TokenDisk.servers(DesktopRig.home).isEmpty())

            pairedWith(d)
            assertEquals("Bearer device-token-gone-one", d.calls("POST", "/v1/device/cert").first().auth)
            platform.folder.flush()
            assertEquals("device-token-gone-one", shelf.token(Core.activeId!!))
            TokenDisk.noFileHolds(DesktopRig.home, "device-token-gone-one")
            assertNull(shelf.token("aaa111"))
        } finally {
            DesktopRig.reset()
            d.close()
        }
    }
}
