package com.prudnikweb.hypernova.ui.screens

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ClientLogSaved
import com.prudnikweb.hypernova.data.ClientLogTooOld
import com.prudnikweb.hypernova.data.DebugHead
import com.prudnikweb.hypernova.data.DebugLevel
import com.prudnikweb.hypernova.data.DebugLogger
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.HubException
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.Upload
import com.prudnikweb.hypernova.data.storage
import com.prudnikweb.hypernova.ui.settings.DebugLogSection
import com.prudnikweb.hypernova.ui.settings.DebugShare
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone

/** Settings → Debug logging (ui/settings/DebugLogSection.kt): the switch and its words, the size, Send, Share, Clear. */
@RunWith(RobolectricTestRunner::class)
class DebugLogSectionTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val zone = TimeZone.getDefault()
    /** A fixed moment: what the switch says is in a picture. Sat 2027-01-16 09:30 UTC is 24 hours on. */
    private val moment = 1_800_000_000_000L - 1_800_000_000_000L % 86_400_000L + 9 * 3_600_000L + 30 * 60_000L
    private lateinit var logger: DebugLogger
    private val folder: File get() = File(context.filesDir, "debug-logs-test")

    @Before fun setUp() {
        resetPrefs()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        folder.deleteRecursively()
        logger = DebugLogger(folder, context.storage.prefs("debuglog-test"), DebugHead("1.2.3 (45)", "debug build", "Android 15 (SDK 35)", "Google Pixel 9"), clock = { moment })
    }

    @After fun tearDown() {
        logger.turnOff()
        logger.flush()
        folder.deleteRecursively()
        DebugShare.clean(context)
        TimeZone.setDefault(zone)
    }

    /** A server that answers Send with [answer]; what it got is in [got]. */
    private inner class LogHub(val answer: () -> Result<ClientLogSaved>, private val base: Hub = FakeHub()) : Hub by base {
        var got: String? = null
        override suspend fun sendClientLog(file: Upload): Result<ClientLogSaved> {
            got = ByteArrayOutputStream().also(file.write).toString()
            return answer()
        }
    }

    private fun status(name: String) = ServerStatus(name, name, LinkState.Connected, 0, false)

    private fun show(vararg hubs: Pair<String, Hub>) {
        rule.setContent {
            Harness(spy) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    DebugLogSection(logger, { hubs.map { (name, h) -> status(name) to h } })
                }
            }
        }
        rule.waitForIdle()
    }

    private fun theSwitch() = rule.onNode(hasText(str(R.string.debuglog_switch)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    /** Something in the log, and Settings told so: what the background thread and the size take a moment to say. */
    private fun withALine(crash: Boolean = false) {
        logger.turnOn()
        logger.put(DebugLevel.I, "t", "a line", null)
        if (crash) logger.crashed(Thread.currentThread(), IllegalStateException("x"))
        logger.flush()
        logger.refresh()
    }

    private fun settled(cond: () -> Boolean) = rule.waitUntil(10_000) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        cond()
    }

    private fun gone(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()

    @Test fun offItSaysSoWhatItHoldsAndThereIsNothingToTakeOut() {
        show("z2" to LogHub({ error("not asked") }))
        theSwitch().assertIsOff()
        rule.onNodeWithText(str(R.string.debuglog_off), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(str(R.string.debuglog_none)).assertExists()
        rule.onNodeWithText(str(R.string.debuglog_send)).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.debuglog_share)).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.debuglog_clear)).assertIsNotEnabled()
        // Owner, 2026-11-02: session and project names count as content, and the words say what a log holds.
        val holds = str(R.string.debuglog_holds)
        for (word in listOf("prompts", "replies", "commands", "file contents", "tool arguments", "session or project names", "tokens", "keys", "pairing codes")) {
            assertTrue(word, holds.contains(word))
        }
        assertFalse("no file is made by looking", folder.exists())
        rule.snap("debug_log_off")
    }

    @Test fun onItSaysUntilWhenAndHowBig() {
        withALine()
        show("z2" to LogHub({ error("not asked") }))
        theSwitch().assertIsOn()
        // Fixed clock, UTC: 24 hours from the moment.
        rule.onNodeWithText(str(R.string.debuglog_on_until, java.text.SimpleDateFormat(com.prudnikweb.hypernova.data.AndroidStrings(context, context.resources).timePattern(true), context.resources.configuration.locales[0]).format(java.util.Date(moment + DebugLogger.DURATION_MS))), useUnmergedTree = true).assertExists()
        val size = logger.sizeBytes
        assertTrue(size > 0)
        rule.onNodeWithText(str(R.string.debuglog_size, com.prudnikweb.hypernova.data.DebugLog.size(size))).assertExists()
        rule.onNodeWithText(str(R.string.debuglog_send)).assertIsEnabled()
    }

    @Test fun theSwitchTurnsItOnAndOff() {
        show()
        theSwitch().performClick()
        rule.waitForIdle()
        assertTrue(logger.isOn)
        assertEquals(moment + DebugLogger.DURATION_MS, logger.until)
        theSwitch().assertIsOn()
        theSwitch().performClick()
        rule.waitForIdle()
        assertFalse(logger.isOn)
        theSwitch().assertIsOff()
    }

    @Test fun withNoServerThereIsNoSendAndShareStillWorks() {
        withALine()
        show()
        assertTrue(gone(str(R.string.debuglog_send)))
        rule.onNodeWithText(str(R.string.debuglog_share)).assertIsEnabled()
    }

    @Test fun aCrashSaysItIsThere() {
        withALine(crash = true)
        show()
        rule.onNodeWithText(str(R.string.debuglog_size_crash, com.prudnikweb.hypernova.data.DebugLog.size(logger.sizeBytes))).assertExists()
    }

    @Test fun sendGoesToTheOneServerAndSaysWhereAndOffersToCopyIt() {
        withALine()
        val path = "/home/me/.local/share/hypernova/client-logs/dev1/2026-11-02T14-03-21-482Z-a1b2c3.log"
        val hub = LogHub({ Result.success(ClientLogSaved(path, "x.log", 10, 1)) })
        show("z2" to hub)
        rule.onNodeWithText(str(R.string.debuglog_send)).performClick()
        settled { hub.got != null && !gone(path) }
        assertTrue(hub.got!!, hub.got!!.contains("a line"))
        rule.onNodeWithText(str(R.string.debuglog_sent, "z2")).assertExists()
        rule.snap("debug_log_sent")
        rule.onNodeWithText(str(R.string.debuglog_copy_path)).performClick()
        rule.waitForIdle()
        assertEquals(path, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertEquals(listOf(str(R.string.debuglog_copied)), spy.toasts)
    }

    @Test fun aSavedCrashGoesAlong() {
        withALine(crash = true)
        val hub = LogHub({ Result.success(ClientLogSaved("/x.log", "x.log", 10, 1)) })
        show("z2" to hub)
        rule.onNodeWithText(str(R.string.debuglog_send)).performClick()
        settled { hub.got != null }
        assertTrue(hub.got!!, hub.got!!.contains("=== Crash"))
    }

    @Test fun withTwoServersItAsksWhichAndOnlyThatOneGetsIt() {
        withALine()
        val z2 = LogHub({ Result.success(ClientLogSaved("/z2", "x", 1, 1)) })
        val shed = LogHub({ Result.success(ClientLogSaved("/shed/x.log", "x", 1, 1)) })
        show("z2" to z2, "shed" to shed)
        rule.onNodeWithText(str(R.string.debuglog_send)).performClick()
        rule.onNodeWithText(str(R.string.debuglog_send_to)).assertExists()
        assertNull(z2.got)
        assertNull(shed.got)
        rule.snap("debug_log_choosing")
        rule.onNodeWithText("shed").performClick()
        settled { !gone("/shed/x.log") }
        assertNotNull(shed.got)
        assertNull(z2.got)
    }

    @Test fun cancellingTheChoiceSendsNothing() {
        withALine()
        val z2 = LogHub({ error("not asked") })
        show("z2" to z2, "shed" to LogHub({ error("not asked") }))
        rule.onNodeWithText(str(R.string.debuglog_send)).performClick()
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        rule.waitForIdle()
        assertTrue(gone(str(R.string.debuglog_send_to)))
        assertNull(z2.got)
    }

    @Test fun anOlderServerNeedsUpdatingAndShareStillWorks() {
        withALine()
        show("z2" to LogHub({ Result.failure(ClientLogTooOld()) }))
        rule.onNodeWithText(str(R.string.debuglog_send)).performClick()
        settled { !gone(str(R.string.debuglog_too_old, "z2")) }
        rule.onNodeWithText(str(R.string.debuglog_share)).assertIsEnabled()
        rule.snap("debug_log_too_old")
    }

    @Test fun anotherFailureSaysWhy() {
        withALine()
        show("z2" to LogHub({ Result.failure(HubException("That's a lot of logs in an hour.")) }))
        rule.onNodeWithText(str(R.string.debuglog_send)).performClick()
        settled { !gone("That's a lot of logs in an hour.") }
    }

    @Test fun shareOpensTheSheetWithAGrantForThatOneFile() {
        withALine()
        show("z2" to LogHub({ error("not asked") }))
        rule.onNodeWithText(str(R.string.debuglog_share)).performClick()
        var chooser: Intent? = null
        settled {
            chooser = shadowOf(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
            chooser != null
        }
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        val send = chooser!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // Reads only; nothing persistable and nothing by prefix, which would reach beyond the one file.
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("${context.packageName}.files", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertTrue(uri.toString(), uri.lastPathSegment!!.matches(Regex("""hypernova-debug-\d{8}-\d{6}\.log""")))
        // What it opens is the gathered log.
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
        assertTrue(text, text.contains("a line"))
    }

    @Test fun sharingExposesOneFileAndTheLogsOwnFolderHasNoUriAtAll() {
        withALine()
        val first = DebugShare.prepare(context, logger)
        Thread.sleep(1_100)
        val second = DebugShare.prepare(context, logger)
        assertFalse("the one before goes", first.exists() && first != second)
        assertEquals(listOf(second.name), second.parentFile!!.list()!!.toList())
        val authority = "${context.packageName}.files"
        assertNotNull(FileProvider.getUriForFile(context, authority, second))
        // The log's live files and the app's private folder are out of the provider's reach.
        for (f in listOf(File(folder, "debug.log"), File(context.filesDir, "debug-logs/debug.log"), File(context.filesDir, "x"))) {
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, authority, f) }
        }
    }

    @Test fun clearAsksFirstAndTakesEverything() {
        withALine(crash = true)
        show()
        val shared = DebugShare.prepare(context, logger)
        rule.onNodeWithText(str(R.string.debuglog_clear)).performClick()
        rule.waitForIdle()
        val asked = spy.confirms.single()
        assertEquals(str(R.string.debuglog_clear_q), asked.title)
        assertTrue(asked.danger)
        // Nothing has gone before the yes.
        assertTrue(File(folder, "crashes.log").exists())
        asked.onConfirm()
        settled { spy.toasts.contains(str(R.string.debuglog_cleared)) }
        assertFalse(File(folder, "crashes.log").exists())
        assertFalse(shared.exists())
    }
}
