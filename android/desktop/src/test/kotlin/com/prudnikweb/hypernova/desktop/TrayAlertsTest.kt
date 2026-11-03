package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Log
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.NotificationPrefs
import com.prudnikweb.hypernova.data.NotificationPrefsStore
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.alerts
import com.prudnikweb.hypernova.desktop.DesktopRig.main
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.platform
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The tray alert, from the daemon's message to what's said, through the real hub over real mutual TLS on loopback
 * ([FakeDaemon]). The tray itself is [StandTray]: a test has no desktop. AWT's own, under Xvfb with a tray and
 * without one, was run by hand in a container (docs/DECISIONS.md, "Tray alerts on the desktop client").
 */
@OptIn(ExperimentalTestApi::class)
class TrayAlertsTest {
    private lateinit var d: FakeDaemon

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
        alerts.shown.clear()
        alerts.available = true
    }

    @After fun tearDown() {
        main { Core.setVisible(true) }
        DesktopRig.reset()
        NotificationPrefsStore.set(platform.storage, NotificationPrefs())
        d.close()
    }

    private val secret = "curl -u admin:hunter2-do-not-show https://example.test/x"

    private fun ask(id: String, sessionId: String = "s1", kind: String = "tool", more: JSONObject.() -> Unit = {}) =
        approval(id, sessionId, summary = "Bash: $secret", kind = kind) { put("input", JSONObject().put("command", secret)); more() }

    private fun oneSession() = session("s1", state = "needs_you", title = "Migrate the payments table")

    private fun looking(on: Boolean) = main { Core.setVisible(on) }

    private fun lastSaid() = alerts.shown.last()

    /** Long enough for a message that would have alerted to have arrived, the socket being loopback. */
    private fun settle() = Thread.sleep(400)

    @Test fun anApprovalWithTheWindowAwayAlertsOnceSayingWhoAndNeverWhat() = runDesktopComposeUiTest(900, 700) {
        d.snapshot = snapshot("z2", sessions = listOf(oneSession()))
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        looking(false)
        d.push(msg("approval") { put("approval", ask("a1")) })
        until("the alert") { alerts.shown.size == 1 }
        assertEquals(Ui.notif_tool.format("Migrate the payments table"), lastSaid().first)
        assertEquals(Ui.desk_alert_body.text(), lastSaid().second)
        assertFalse("the command never leaves the window", (lastSaid().first + lastSaid().second).contains("hunter2"))
        assertFalse("nor does its summary", (lastSaid().first + lastSaid().second).contains("curl"))

        // The same request again: over the socket, then in the snapshot of a new connection.
        d.push(msg("approval") { put("approval", ask("a1")) })
        settle()
        assertEquals(1, alerts.shown.size)
        d.snapshot = snapshot("z2", sessions = listOf(oneSession()), approvals = listOf(ask("a1")))
        d.closeSocket()
        until("back, with its snapshot") { Core.live?.pendingApprovals?.any { it.id == "a1" } == true && d.count("GET", "/v1/snapshot") >= 2 }
        settle()
        assertEquals("not twice for one request", 1, alerts.shown.size)

        // Another request is another alert.
        d.push(msg("approval") { put("approval", ask("a2")) })
        until("the second alert") { alerts.shown.size == 2 }
    }

    @Test fun withTheWindowInFrontThereIsNoAlertAndWhatItShowedStaysSeen() = runDesktopComposeUiTest(900, 700) {
        d.snapshot = snapshot("z2", sessions = listOf(oneSession()))
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        d.push(msg("approval") { put("approval", ask("a1")) })
        until("it's on the list") { Core.live?.pendingApprovals?.any { it.id == "a1" } == true }
        settle()
        assertEquals("on the card, not in the tray", 0, alerts.shown.size)
        // Looking away from a request already on screen isn't news.
        looking(false)
        d.snapshot = snapshot("z2", sessions = listOf(oneSession()), approvals = listOf(ask("a1")))
        d.closeSocket()
        until("the link back") { d.count("GET", "/v1/snapshot") >= 2 }
        settle()
        assertEquals(0, alerts.shown.size)
    }

    @Test fun aQuestionAndAPlanSayWhatTheyAreAndAClickOpensTheWindowOnThatServer() = runDesktopComposeUiTest(900, 700) {
        d.snapshot = snapshot("z2", sessions = listOf(oneSession()))
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        looking(false)
        d.push(msg("approval") { put("approval", ask("q1", kind = "question")) })
        until("the question") { alerts.shown.size == 1 }
        assertEquals(Ui.notif_question.format("Migrate the payments table"), lastSaid().first)
        d.push(msg("approval") { put("approval", ask("p1", kind = "plan")) })
        until("the plan") { alerts.shown.size == 2 }
        assertEquals(Ui.notif_plan.format("Migrate the payments table"), lastSaid().first)

        val opened = CopyOnWriteArrayList<String?>()
        platform.alerts.opened = { opened += it }
        alerts.clicked()
        assertEquals(listOf<String?>(Core.activeId), opened.toList())
        platform.alerts.opened = {}
    }

    @Test fun theOwnersSwitchesHoldAndTheCardsDontCare() = runDesktopComposeUiTest(900, 700) {
        d.snapshot = snapshot("z2", sessions = listOf(oneSession()))
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        NotificationPrefsStore.set(platform.storage, NotificationPrefs(master = false))
        looking(false)
        d.push(msg("approval") { put("approval", ask("a1")) })
        until("on the list") { Core.live?.pendingApprovals?.any { it.id == "a1" } == true }
        settle()
        assertEquals(0, alerts.shown.size)
    }

    @Test fun settingsSaysWhichItIsAndThatAlertsStopWithTheApp() = runDesktopComposeUiTest(900, 900) {
        alerts.available = true
        setContent { App(shell, reduced = true) }
        onNodeWithText(Ui.desk_settings.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.desk_alerts_tray.text()).assertExists()
        onNodeWithText(Ui.desk_alerts_only_open.text()).assertExists()
        main { shell.settings = false }
        alerts.available = false
        waitForIdle()
        onNodeWithText(Ui.desk_settings.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.desk_alerts_no_tray.text()).assertExists()
        main { shell.settings = false }
    }

    // ─── The notifier by itself ───

    private val quiet = object : Log {
        override fun d(tag: String, message: String) = Unit
        override fun w(tag: String, message: String) = Unit
    }

    private fun notifier(tray: StandTray, label: (String?) -> String? = { null }) = TrayNotifier(quiet, platform.storage, tray, label)

    private fun notice(kind: Notice.Kind, id: String = "$kind:s1:1", sessionId: String? = "s1", text: String = "the words") =
        Notice(id, kind, sessionId, "cosmos renderer", text)

    @Test fun onlyWhatNeedsInputIsAnAlert() {
        val tray = StandTray()
        val n = notifier(tray)
        for (k in listOf(Notice.Kind.Finished, Notice.Kind.Error, Notice.Kind.Update, Notice.Kind.Limit, Notice.Kind.Power, Notice.Kind.Backpack, Notice.Kind.Automation, Notice.Kind.ServerUpdateFailed)) {
            n.notice(notice(k))
        }
        assertEquals(emptyList<Pair<String, String>>(), tray.shown.toList())
        n.notice(notice(Notice.Kind.Waiting, text = "what it's asking, maybe a secret"))
        assertEquals(Ui.notice_title_waiting.format("cosmos renderer") to Ui.desk_alert_body.text(), tray.shown.last())
        n.notice(notice(Notice.Kind.Waiting))
        assertEquals("one wait, once", 1, tray.shown.size)
        // The account's words are the app's own, and it has one id for each of its episodes, which the hub keeps apart.
        n.notice(notice(Notice.Kind.Account, id = "account", sessionId = null, text = "Sign in again"))
        assertEquals("cosmos renderer" to "Sign in again", tray.shown.last())
        n.notice(notice(Notice.Kind.Account, id = "account", sessionId = null, text = "Out of credits"))
        assertEquals("Out of credits", tray.shown.last().second)
    }

    @Test fun anotherServersRequestSaysWhereItsFromAndIsItsOwn() {
        val tray = StandTray()
        val n = notifier(tray) { if (it == "other") "juniper" else null }
        val a = com.prudnikweb.hypernova.data.WApproval(
            id = "a1", sessionId = "s1", createdAt = 1, expiresAt = null, kind = "tool", toolName = "Bash", summary = "Bash: ls",
            input = mapOf("command" to "ls"), reason = null, canAlways = false, questions = emptyList(), plan = null,
        )
        n.approval(a, null, server = "here")
        assertEquals(Ui.notif_tool_plain.text() to Ui.desk_alert_body.text(), tray.shown.last())
        n.approval(a, null, server = "other")
        assertEquals("the same id on another server is another request", 2, tray.shown.size)
        assertEquals("juniper\n" + Ui.desk_alert_body.text(), tray.shown.last().second)
        n.approval(a.copy(id = "a2", agentId = "x", agentLabel = "implement:fix"), null, server = "other")
        assertEquals(Ui.notif_server_from_agent.format("juniper", "implement:fix") + "\n" + Ui.desk_alert_body.text(), tray.shown.last().second)

        val heard = CopyOnWriteArrayList<String?>()
        n.opened = { heard += it }
        tray.clicked()
        assertEquals("a click goes to the server of the last alert", listOf<String?>("other"), heard.toList())
    }

    @Test fun noTrayIsNothingAtAll() {
        // These tests run headless, where AWT says as much: the same answer a desktop with no tray gets.
        val tray = AwtTray(quiet) { BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB) }
        assertFalse(tray.available)
        tray.start()
        tray.show("a title", "some text")
        val n = TrayNotifier(quiet, platform.storage, tray) { null }
        assertFalse(n.canPost())
        n.notice(notice(Notice.Kind.Waiting))
    }

    @Test fun theTitleCountsWhatNeedsYou() {
        assertEquals(APP_NAME, windowTitle(0))
        assertEquals("(2) $APP_NAME", windowTitle(2))
    }

    @Test fun focusGoneForAMomentIsStillLooking() = runBlocking {
        val focus = MutableStateFlow(true)
        val said = CopyOnWriteArrayList<Boolean>()
        val job = launch { followFocus(focus, graceMs = 300) { said += it } }
        fun waitFor(n: Int) {
            val until = System.currentTimeMillis() + 5_000
            while (said.size < n && System.currentTimeMillis() < until) Thread.sleep(10)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            waitFor(1)
            assertEquals(listOf(true), said.toList())
            // Away and straight back: nothing changed.
            focus.value = false
            Thread.sleep(50)
            focus.value = true
            Thread.sleep(500)
            assertTrue("no false in $said", said.none { !it })
            // Away for good.
            focus.value = false
            val before = said.size
            waitFor(before + 1)
            assertEquals(false, said.last())
        }
        job.cancel()
    }
}
