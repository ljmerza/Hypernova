package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.toAwtImage
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.desktop.ui.LIST_TAG
import com.prudnikweb.hypernova.desktop.ui.PANE_TAG
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * The real [DesktopPlatform], in a temp folder, under the real [Core], for one test class ([Core] is one per
 * process; build.gradle.kts forks a process per class). Files, keys, the TLS and the socket are all real.
 * What's on the other side is a [FakeDaemon] on loopback, or the demo server in [DemoServerTest].
 */
class StandTray(override var available: Boolean = true) : AlertSurface {
    val shown = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    override var clicked: () -> Unit = {}
    override fun show(title: String, text: String) {
        shown += title to text
    }
}

object DesktopRig {
    val home = Files.createTempDirectory("hypernova-desktop-test")
    /** A test has no tray: what would have shown is kept here. */
    val alerts = StandTray()
    /** A file, unless a test class says otherwise before its first use of [platform]. */
    var keychain: KeychainChoice = KeychainChoice.OFF
    val platform: DesktopPlatform by lazy { DesktopPlatform(home, surface = alerts, keychain = keychain) }
    val shell: Shell by lazy { startCore(platform) }

    fun main(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    /** Back to nothing paired. */
    fun reset() {
        main {
            for (s in Core.servers.map { it.id }) Core.forget(s)
            shell.cancelAdding()
            shell.dismissToast()
        }
    }

    /** The link `hypernova pair` prints for this daemon's listener. */
    fun linkOf(d: FakeDaemon, code: String = "c0de-1234", fp: String? = d.fingerprint, base: String = "https://127.0.0.1:${d.lanPort}"): String =
        "hypernova://pair?v=1&u=$base&c=$code&n=${d.name}" + if (fp != null) "&fp=$fp&lan=127.0.0.1&lp=${d.lanPort}" else ""

    /** The daemon's answer to a good code. */
    fun FakeDaemon.takesCode() = ok(
        "POST", "/v1/pair",
        JSONObject().put("token", "device-token-$name").put("deviceId", "dev-$name").put("mac", JSONObject().put("id", "m-$name").put("name", name)),
    )

    /** Paired for real through the shell, and linked over the listener with this computer's certificate. */
    @OptIn(ExperimentalTestApi::class)
    fun ComposeUiTest.pairedWith(d: FakeDaemon) {
        d.takesCode()
        main {
            shell.adding = Core.live != null
            shell.link = linkOf(d)
            shell.submit()
        }
        until("linked over mutual TLS") {
            Core.live?.link?.state == LinkState.Connected && d.issued.isNotEmpty() &&
                d.calls("GET", "/v1/ws").any { it.overLan && it.clientCert?.encoded?.contentEquals(d.issued.last().encoded) == true }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    fun ComposeUiTest.until(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) = waitUntil(what, timeoutMs) { cond() }

    /** The list's row with this name. */
    @OptIn(ExperimentalTestApi::class)
    fun ComposeUiTest.row(name: String): SemanticsNodeInteraction = onNode(hasText(name) and hasAnyAncestor(hasTestTag(LIST_TAG)))

    /** This text in the pane beside the list. */
    @OptIn(ExperimentalTestApi::class)
    fun ComposeUiTest.inPane(text: String, substring: Boolean = false): SemanticsNodeInteraction =
        onNode(hasText(text, substring = substring) and hasAnyAncestor(hasTestTag(PANE_TAG)))

    /** The mouse on a row, as a person chooses a session. */
    @OptIn(ExperimentalTestApi::class)
    fun ComposeUiTest.choose(name: String) {
        row(name).performClick()
        armPills()
    }

    /** Long enough for new Deny and Allow pills to take a click (SessionPane.kt, ASK_ARM_MS). */
    @OptIn(ExperimentalTestApi::class)
    fun ComposeUiTest.armPills() {
        waitForIdle()
        mainClock.advanceTimeBy(com.prudnikweb.hypernova.desktop.ui.ASK_ARM_MS + 100)
        waitForIdle()
    }

    /** The window's picture so far, in build/renders for someone to look at. */
    @OptIn(ExperimentalTestApi::class)
    fun ComposeUiTest.render(name: String): File {
        waitForIdle()
        val dir = File(System.getProperty("hypernova.renders") ?: "build/renders").apply { mkdirs() }
        val out = File(dir, "$name.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
        return out
    }
}
