package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DebugLog
import com.prudnikweb.hypernova.data.DeviceCertStore
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.main
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.platform
import com.prudnikweb.hypernova.desktop.DesktopRig.render
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

/**
 * A pairing with the key in a keychain (a stand-in), through the real screen, the real hub and real mutual TLS on
 * loopback against [FakeDaemon]. This class has its own process, so its platform is the only one that has this keychain.
 */
@OptIn(ExperimentalTestApi::class)
class KeychainPairingTest {
    /** The key's item is [stand]; the servers' tokens each have their own on [shelf], which is the same way a real one behaves. */
    private class Stand : Keychain {
        var held: String? = null
        var locked = false
        override fun read() = if (locked) KeychainRead.Down else held?.let { KeychainRead.Found(it) } ?: KeychainRead.Missing
        override fun write(text: String): Boolean {
            if (locked) return false
            held = text
            return true
        }
        override fun delete(): Boolean {
            held = null
            return true
        }
    }

    private companion object {
        val stand = Stand()
        val tokens = java.util.concurrent.ConcurrentHashMap<String, String>()

        init {
            DesktopRig.keychain = KeychainChoice(stand) { name ->
                object : Keychain {
                    override fun read() = if (stand.locked) KeychainRead.Down else tokens[name]?.let { KeychainRead.Found(it) } ?: KeychainRead.Missing
                    override fun write(text: String): Boolean {
                        if (stand.locked) return false
                        tokens[name] = text
                        return true
                    }
                    override fun delete(): Boolean {
                        if (stand.locked) return false
                        tokens.remove(name)
                        return true
                    }
                }
            }
        }
    }

    @Test fun itPairsOnAKeyInTheKeychainWithNothingOfItOnDiskOrInTheLog() = runDesktopComposeUiTest(900, 900) {
        val d = FakeDaemon("z2", lan = true)
        d.snapshot = snapshot("z2")
        shell // Core up: the logger is its.
        val logger = DebugLog.logger!!
        logger.turnOn()
        try {
            setContent { com.prudnikweb.hypernova.desktop.ui.App(shell, reduced = true) }
            main { shell.settings = true }
            waitForIdle()
            onNodeWithText(Ui.desk_key_none.text()).assertExists()
            main { shell.settings = false }
            waitForIdle()

            pairedWith(d)

            // The server's certificate is for this key, and the handshake just used it.
            val held = checkNotNull(stand.held)
            val secret = JSONObject(held).getString("private")
            assertEquals(JSONObject(held).getString("public"), Base64.getEncoder().encodeToString(d.issued.last().publicKey.encoded))
            assertNotNull(DeviceCertStore(platform.storage, platform.deviceKeys, Core.activeId!!).identity())
            assertEquals(KeyPlace.InKeychain, platform.keys.place)
            assertFalse("no file", Files.exists(platform.keyFile))

            // Nothing on disk holds it: every file in this app's folder, the debug log that Send logs would send among them.
            assertTrue(logger.flush(10_000))
            platform.folder.flush()
            val files = Files.walk(DesktopRig.home).use { w -> w.filter { Files.isRegularFile(it) }.toList() }
            assertTrue("the debug log's there to read", files.any { it.toString().contains("debug-logs") })
            for (f in files) assertFalse("$f holds the key", String(Files.readAllBytes(f)).contains(secret))
            for (f in files) assertFalse("$f holds a piece of it", String(Files.readAllBytes(f)).contains(secret.substring(40, 80)))

            // Nor the server's token: only its item, with the settings file saying where it is.
            assertEquals("device-token-z2", tokens[Keychains.tokenItem(Core.activeId!!)])
            TokenDisk.noFileHolds(DesktopRig.home, "device-token-z2")
            assertEquals(TokenPlace.InKeychain, platform.tokens.place)

            main { shell.settings = true }
            waitForIdle()
            onNodeWithText(Ui.desk_key_keychain.text()).assertExists()
            onNodeWithText(Ui.desk_token_keychain.text()).assertExists()
            render("settings_key_in_keychain")
            main { shell.settings = false }
            waitForIdle()

            // Unpaired: the last server's key goes with it.
            DesktopRig.reset()
            assertEquals(null, stand.held)
            platform.folder.flush()
            assertTrue("and its token's item", tokens.isEmpty())

            // Paired with the keychain locked: it connects all the same, on the file, and says so in plain words.
            stand.locked = true
            d.issued.clear()
            pairedWith(d)
            assertEquals(KeyPlace.InFile(FileWhy.Down), platform.keys.place)
            assertTrue("the file holds it", Files.exists(platform.keyFile))
            platform.folder.flush()
            assertEquals(TokenPlace.InFile(FileWhy.Down), platform.tokens.place)
            assertEquals("the token's in its private file", "device-token-z2", TokenDisk.servers(DesktopRig.home).single().getString("token"))
            main { shell.settings = true }
            waitForIdle()
            onNodeWithText(Ui.desk_key_file_down.text()).assertExists()
            onNodeWithText(Ui.desk_key_file_note.text()).assertExists()
            onNodeWithText(Ui.desk_token_file_down.text()).assertExists()
            onNodeWithText(Ui.desk_token_file_note.text()).assertExists()
            render("settings_key_in_file")

            // Unlocked, and Try again in that same section: both move across with no restart, and it stays connected.
            stand.locked = false
            onNodeWithText(Ui.desk_token_retry.text()).performClick()
            until("moved") { platform.tokens.place == TokenPlace.InKeychain && platform.keys.place == KeyPlace.InKeychain }
            onNodeWithText(Ui.desk_token_keychain.text()).assertExists()
            TokenDisk.noFileHolds(DesktopRig.home, "device-token-z2")
            assertFalse(Files.exists(platform.keyFile))
            main { shell.settings = false }
        } finally {
            logger.turnOff()
            DesktopRig.reset()
            d.close()
        }
        assertFalse("unpairing takes the file too", Files.exists(platform.keyFile))
    }
}
