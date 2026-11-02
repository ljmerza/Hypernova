package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DebugLevel
import com.prudnikweb.hypernova.data.DebugLog
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.platform
import com.prudnikweb.hypernova.desktop.DesktopRig.render
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Settings on this computer: Debug logging through the real screen, the real logger in the real folder, and Send over
 * real mutual TLS on loopback, against [FakeDaemon]. A file manager and a clipboard can't be had in a test: [Stand] is them.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private class Stand(var opens: Boolean = true) : DeskActions {
        val opened = mutableListOf<File>()
        val copied = mutableListOf<String>()
        override fun openFolder(folder: File): Boolean {
            opened += folder
            return opens
        }
        override fun copy(text: String) {
            copied += text
        }
    }

    private val stand = Stand()
    private val daemons = mutableListOf<FakeDaemon>()
    private val logger get() = DebugLog.logger!!

    private fun daemon(name: String = "atlas") = FakeDaemon(name, lan = true).also {
        it.snapshot = snapshot(name)
        daemons += it
    }

    @Before fun fresh() {
        DesktopRig.reset()
        shell.desk = stand
        shell.settings = false
        DebugLog.logger?.let { it.turnOff(); it.flush(); it.clear() }
    }

    @After fun tearDown() {
        DesktopRig.reset()
        shell.settings = false
        daemons.forEach { it.close() }
        DebugLog.logger?.let { it.turnOff(); it.flush(); it.clear() }
    }

    private fun ComposeUiTest.open() {
        setContent { App(shell, reduced = true) }
        onNodeWithText(Ui.desk_settings.text()).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.theSwitch() = onNode(hasText(Ui.debuglog_switch.text()), useUnmergedTree = false)

    private fun ComposeUiTest.logALine() {
        logger.turnOn()
        DebugLog.i("test") { "a line" }
        assertTrue(logger.flush(10_000))
        logger.refresh()
        waitForIdle()
    }

    private val saved = JSONObject().put("log", JSONObject().put("path", "/srv/hypernova/client-logs/dev/2026-11-02T14-03-21-482Z-a1b2c3.log").put("name", "x.log").put("size", 10).put("at", 1L))

    @Test fun itsReachedFromThePairingScreenTooOffItWritesNothing() = runDesktopComposeUiTest(900, 900) {
        open()
        theSwitch().assertIsOff()
        onNodeWithText(Ui.debuglog_off.text(), useUnmergedTree = true).assertExists()
        onNodeWithText(Ui.desk_open_folder.text()).assertIsNotEnabled()
        // "this phone" reads as this computer here, in what it says it holds.
        val holds = Ui.debuglog_holds.text()
        assertTrue(holds, holds.contains("on this computer"))
        assertTrue(holds, holds.contains("session or project names"))
        assertFalse("no folder by looking", logger.folder.exists() && logger.folder.list()!!.isNotEmpty())
        render("settings_debug_off")
        onNodeWithText(Ui.back.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.desk_pair_field.text().uppercase()).assertExists()
    }

    @Test fun theSwitchTurnsItOnSaysUntilWhenAndTheFilesAreItsOwners() = runDesktopComposeUiTest(900, 900) {
        open()
        theSwitch().performClick()
        waitForIdle()
        assertTrue(logger.isOn)
        assertTrue(DebugLog.on)
        theSwitch().assertIsOn()
        // Through the seam, as the data layer logs.
        platform.log.w("Hypernova", "through the seam")
        assertTrue(logger.flush(10_000))
        val log = File(logger.folder, "debug.log")
        val text = log.readText()
        assertTrue(text, text.startsWith("# Hypernova debug log\n# App: version "))
        assertTrue(text, text.contains("desktop app"))
        assertTrue(text, text.contains(" W hypernova through the seam"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(log.toPath())))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(log.parentFile.toPath())))
        theSwitch().performClick()
        waitForIdle()
        assertFalse(logger.isOn)
        assertFalse(DebugLog.on)
    }

    @Test fun openFolderAndCopyPath() = runDesktopComposeUiTest(900, 900) {
        open()
        logALine()
        onNodeWithText(Ui.desk_open_folder.text()).performClick()
        until("the folder asked for") { stand.opened.isNotEmpty() }
        assertEquals(logger.folder, stand.opened.single())
        onNodeWithText(Ui.debuglog_copy_path.text()).performClick()
        waitForIdle()
        assertEquals(listOf(logger.folder.path), stand.copied)
        assertEquals(Ui.desk_folder_copied.text(), shell.toast)
        // A system that can't open a folder says so.
        stand.opens = false
        onNodeWithText(Ui.desk_open_folder.text()).performClick()
        until("it to say so") { shell.toast == Ui.desk_folder_failed.text() }
    }

    @Test fun sendGoesOverMutualTlsAndSaysWhere() = runDesktopComposeUiTest(900, 900) {
        setContent { App(shell, reduced = true) }
        val d = daemon()
        pairedWith(d)
        d.ok("POST", "/v1/client-logs", saved)
        onNodeWithText(Ui.desk_settings.text()).performClick()
        waitForIdle()
        logALine()
        logger.crashed(Thread.currentThread(), IllegalStateException("x"))
        render("settings_debug_on")
        onNodeWithText(Ui.debuglog_send.text()).performClick()
        until("it to land") { d.count("POST", "/v1/client-logs") == 1 && onAllNodes(hasText(saved.getJSONObject("log").getString("path"))).fetchSemanticsNodes().isNotEmpty() }
        val req = d.calls("POST", "/v1/client-logs").single()
        assertTrue("over the listener", req.overLan)
        assertEquals("with this computer's certificate", true, req.clientCert?.encoded?.contentEquals(d.issued.last().encoded))
        assertTrue(req.body, req.body.contains("a line"))
        assertTrue(req.body, req.body.contains("=== Crash"))
        render("settings_debug_sent")
        // The folder's and the sent log's.
        assertEquals(2, onAllNodes(hasText(Ui.debuglog_copy_path.text())).fetchSemanticsNodes().size)
        onAllNodes(hasText(Ui.debuglog_copy_path.text()))[1].performClick()
        waitForIdle()
        assertEquals(saved.getJSONObject("log").getString("path"), stand.copied.last())
    }

    @Test fun anOlderServerSaysItNeedsUpdatingAndTheFolderStillOpens() = runDesktopComposeUiTest(900, 900) {
        setContent { App(shell, reduced = true) }
        val d = daemon()
        pairedWith(d)
        onNodeWithText(Ui.desk_settings.text()).performClick()
        waitForIdle()
        logALine()
        onNodeWithText(Ui.debuglog_send.text()).performClick()
        until("it to say so") { onAllNodes(hasText(Ui.debuglog_too_old.format(d.name))).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(Ui.desk_open_folder.text()).performClick()
        until("the folder asked for") { stand.opened.isNotEmpty() }
    }

    @Test fun twoServersItAsksWhich() = runDesktopComposeUiTest(900, 900) {
        setContent { App(shell, reduced = true) }
        val a = daemon("atlas")
        val b = daemon("juniper")
        pairedWith(a)
        pairedWith(b)
        a.ok("POST", "/v1/client-logs", saved)
        b.on("POST", "/v1/client-logs") { refusal(429, "too_many_logs", "That's a lot of logs in an hour.") }
        onNodeWithText(Ui.desk_settings.text()).performClick()
        waitForIdle()
        logALine()
        onNodeWithText(Ui.debuglog_send.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.debuglog_send_to.text()).assertExists()
        assertEquals(0, a.count("POST", "/v1/client-logs") + b.count("POST", "/v1/client-logs"))
        onNodeWithText("juniper").performClick()
        until("it to say why") { onAllNodes(hasText("That's a lot of logs in an hour.")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, a.count("POST", "/v1/client-logs"))
    }

    @Test fun clearAsksFirst() = runDesktopComposeUiTest(900, 900) {
        open()
        logALine()
        logger.crashed(Thread.currentThread(), IllegalStateException("x"))
        logger.put(DebugLevel.I, "t", "OLD", null)
        assertTrue(logger.flush(10_000))
        logger.refresh()
        waitForIdle()
        onNodeWithText(Ui.debuglog_clear.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.debuglog_clear_q.text()).assertExists()
        assertTrue(File(logger.folder, "crashes.log").exists())
        onAllNodes(hasText(Ui.debuglog_clear.text()))[1].performClick()
        until("it to go") { shell.toast == Ui.debuglog_cleared.text() }
        assertFalse(File(logger.folder, "crashes.log").exists())
        assertFalse(File(logger.folder, "debug.log").readText().contains("OLD"))
    }

    /** The core is one per process (this class has its own): the handler went in at the start, and a dying thread's trace is kept with the switch off. */
    @Test fun aCrashIsKeptWithTheSwitchOffAndNothingElseIs() {
        assertTrue(Core.platform === platform || DesktopRig.shell === shell)
        assertFalse(logger.isOn)
        val t = Thread({ throw IllegalStateException("PLANTED") }, "dying")
        t.start()
        t.join()
        val crash = File(logger.folder, "crashes.log")
        assertTrue(crash.readText(), crash.readText().contains("thread dying"))
        assertFalse(crash.readText(), crash.readText().contains("PLANTED"))
        assertFalse(File(crash.parentFile, "debug.log").exists() && File(crash.parentFile, "debug.log").readText().contains("dying"))
    }
}
