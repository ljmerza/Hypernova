package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.choose
import com.prudnikweb.hypernova.desktop.DesktopRig.inPane
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.platform
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Allow and Deny through the real screen, the real hub and this platform, over real mutual TLS on loopback, against
 * [FakeDaemon]: the demo server is read-only, so answers can't go through it (it refuses them, which [DemoServerTest]
 * shows as well).
 */
@OptIn(ExperimentalTestApi::class)
class ApproveTest {
    private lateinit var d: FakeDaemon

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
    }

    @After fun tearDown() {
        DesktopRig.reset()
        d.close()
    }

    private fun tool(id: String, sessionId: String, tool: String, input: JSONObject, summary: String = "$tool: …") =
        approval(id, sessionId, summary = summary) { put("toolName", tool).put("input", input) }

    private fun onlySession(ask: JSONObject) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "needs_you", title = "Migrate the payments table")), approvals = listOf(ask))
    }

    private fun whole() = onlySession(tool("a1", "s1", "Bash", JSONObject().put("command", "npm run db:migrate")))

    private fun lastAnswer(id: String) = d.calls("POST", "/v1/approvals/$id").last()

    @Test fun allowSendsAllowOverMutualTls() = runDesktopComposeUiTest(900, 700) {
        whole()
        d.ok("POST", "/v1/approvals/a1")
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        onNodeWithText("$ npm run db:migrate").assertExists()
        onNodeWithText(Ui.allow.text()).performClick()
        until("the answer") { d.count("POST", "/v1/approvals/a1") == 1 }
        val req = lastAnswer("a1")
        assertEquals("allow", req.json.getString("decision"))
        assertTrue("on the listener", req.overLan)
        assertEquals("with this computer's certificate", d.issued.last().encoded.toList(), req.clientCert?.encoded?.toList())
        assertEquals("Bearer device-token-z2", req.auth)
        // And it says what's happening, not the pills: a second click can't send a second answer.
        until("the hub to have it") { Core.live?.approval("s1") == null }
    }

    @Test fun denySendsDeny() = runDesktopComposeUiTest(900, 700) {
        whole()
        d.ok("POST", "/v1/approvals/a1")
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        onNodeWithText(Ui.deny.text()).performClick()
        until("the answer") { d.count("POST", "/v1/approvals/a1") == 1 }
        assertEquals("deny", lastAnswer("a1").json.getString("decision"))
    }

    @Test fun whileAnAnswerGoesItSaysSo() = runDesktopComposeUiTest(900, 700) {
        whole()
        val release = java.util.concurrent.CountDownLatch(1)
        d.on("POST", "/v1/approvals/a1") {
            release.await()
            FakeDaemon.json(200, JSONObject())
        }
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        onNodeWithText(Ui.allow.text()).performClick()
        until("the word") { runCatching { onNodeWithText(Ui.widget_allowing.text()).assertExists() }.isSuccess }
        release.countDown()
        until("the hub to have it") { Core.live?.approval("s1") == null }
    }

    @Test fun whatCantBeReadWholeHasNoAllowAndDenyStillWorks() = runDesktopComposeUiTest(900, 700) {
        onlySession(tool("a1", "s1", "Edit", JSONObject().put("file_path", "/p/a.kt").put("old_string", "a").put("new_string", "b"), summary = "Edit: /p/a.kt"))
        d.ok("POST", "/v1/approvals/a1")
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        onNodeWithText(Ui.desk_not_whole.text()).assertExists()
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()
        onNodeWithText(Ui.deny.text()).performClick()
        until("the answer") { d.count("POST", "/v1/approvals/a1") == 1 }
        assertEquals("deny", lastAnswer("a1").json.getString("decision"))
    }

    @Test fun aMaskedCommandHasNoAllowAndShowsNoSecret() = runDesktopComposeUiTest(900, 700) {
        val secret = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789"
        onlySession(tool("a1", "s1", "Bash", JSONObject().put("command", "curl -H 'Authorization: Bearer $secret' https://x")))
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()
        onNodeWithText(secret, substring = true).assertDoesNotExist()
        // In the row and in the pane.
        assertEquals(2, onAllNodesWithText("•••", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun aQuestionAndAPlanSayToAnswerOnThePhoneWithNothingToPress() = runDesktopComposeUiTest(900, 700) {
        d.snapshot = snapshot(
            "z2",
            sessions = listOf(session("s1", state = "needs_you", title = "Asks"), session("s2", state = "needs_you", title = "Plans")),
            approvals = listOf(
                approval("q1", "s1", kind = "question") { put("toolName", "AskUserQuestion") },
                approval("p1", "s2", kind = "plan") { put("toolName", "ExitPlanMode") },
            ),
        )
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        onNodeWithText(Ui.desk_answer_on_phone_question.text()).assertExists()
        onNodeWithText(Ui.desk_answer_on_phone_plan.text()).assertExists()
        for ((name, words) in listOf("Asks" to Ui.desk_answer_on_phone_question, "Plans" to Ui.desk_answer_on_phone_plan)) {
            choose(name)
            inPane(words.text()).assertExists()
            onNodeWithText(Ui.allow.text()).assertDoesNotExist()
            onNodeWithText(Ui.deny.text()).assertDoesNotExist()
        }
    }

    @Test fun aRefusalSaysSoOnScreenAndThePillsComeBack() = runDesktopComposeUiTest(900, 700) {
        whole()
        d.on("POST", "/v1/approvals/a1") { refusal(500, "boom", "The hook is gone") }
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        onNodeWithText(Ui.allow.text()).performClick()
        until("the refusal") { shell.toast != null }
        waitForIdle()
        onNodeWithText("The hook is gone").assertExists()
        onNodeWithText(Ui.desk_answer_failed.text()).assertExists()
        onNodeWithText(Ui.allow.text()).assertExists()
        assertNotNull("still waiting", Core.live?.approval("s1"))
    }

    @Test fun someoneElseAnsweringFirstSaysSo() = runDesktopComposeUiTest(900, 700) {
        whole()
        d.on("POST", "/v1/approvals/a1") { refusal(409, "already_resolved", "Already resolved") }
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        onNodeWithText(Ui.allow.text()).performClick()
        until("the word") { shell.toast != null }
        waitForIdle()
        onNodeWithText(platform.strings.text(Str.err_already_answered)).assertExists()
        assertTrue(Core.live?.approval("s1") == null)
    }

    @Test fun aLostLinkSaysSoOnTheCardAndTheChip() = runDesktopComposeUiTest(900, 700) {
        whole()
        d.ok("POST", "/v1/approvals/a1")
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        d.down = true
        d.closeSocket()
        onNodeWithText(Ui.allow.text()).performClick()
        until("it not to get through", timeoutMs = 30_000) { runCatching { onNodeWithText(Ui.desk_answer_failed.text()).assertExists() }.isSuccess }
        assertNotNull("still waiting on this end", Core.live?.approval("s1"))
        onNodeWithText(Ui.allow.text()).assertExists()
        // And the link says it's lost, then why.
        until("the chip", timeoutMs = 60_000) { Core.live?.link?.state == LinkState.Offline || Core.live?.link?.state == LinkState.Reconnecting }
        waitForIdle()
        val word = if (Core.live?.link?.state == LinkState.Offline) Ui.desk_link_offline else Ui.desk_link_reconnecting
        onNodeWithText(word.text().uppercase()).assertExists()
    }

    @Test fun offlineIsOnScreenHoweverFarTheListWasScrolledAndCanBeRetried() = runDesktopComposeUiTest(900, 500) {
        d.snapshot = snapshot("z2", sessions = (1..30).map { session("s$it", state = "working", title = "Session number $it") })
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { Core.live?.sessions?.size == 30 }
        waitForIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(25)
        waitForIdle()
        d.down = true
        d.closeSocket()
        until("offline", timeoutMs = 90_000) { Core.live?.link?.state == LinkState.Offline }
        waitForIdle()
        onNodeWithText(Ui.cant_reach.format("z2")).assertIsDisplayed()
        onNodeWithText(Ui.desk_link_offline.text().uppercase()).assertIsDisplayed()
        onNodeWithText(Ui.desk_stale.text()).assertIsDisplayed()
        // Back up, and Retry now finds it.
        d.down = false
        onNodeWithText(Ui.retry_now.text()).performClick()
        until("linked again", timeoutMs = 30_000) { Core.live?.link?.state == LinkState.Connected }
        waitForIdle()
        onNodeWithText(Ui.cant_reach.format("z2")).assertDoesNotExist()
    }

    @Test fun anAnswerComingBackFromElsewhereTakesTheCardsAskAway() = runDesktopComposeUiTest(900, 700) {
        whole()
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        onNodeWithText(Ui.allow.text()).assertExists()
        d.push(msg("approval.resolved") { put("id", "a1").put("sessionId", "s1").put("decision", "allow").put("by", "phone") })
        until("the ask gone") { Core.live?.approval("s1") == null }
        waitForIdle()
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()
    }

    @Test fun theKeyboardReachesThePillsAndPressesThem() = runDesktopComposeUiTest(900, 700) {
        whole()
        d.ok("POST", "/v1/approvals/a1")
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        // Tab goes through what can take the focus, in order: Sessions, History, Settings, Pair another, then Deny, then Allow.
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithText(Ui.sessions.text()).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithText(Ui.history.text()).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithText(Ui.desk_settings.text()).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithText(Ui.desk_pair_another.text()).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithText(Ui.deny.text()).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithText(Ui.allow.text()).assertIsFocused()
        onNodeWithText(Ui.allow.text()).requestFocus()
        onNodeWithText(Ui.allow.text()).performKeyInput { pressKey(Key.Enter) }
        until("the answer") { d.count("POST", "/v1/approvals/a1") == 1 }
        assertEquals("allow", lastAnswer("a1").json.getString("decision"))
    }

    @Test fun aServerThatForgetsThisComputerSaysSoAndPairAgainGoesBackToTheField() = runDesktopComposeUiTest(900, 700) {
        whole()
        d.on("POST", "/v1/approvals/a1") { refusal(401, "unauthorized", "Not paired.") }
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        choose("Migrate the payments table")
        onNodeWithText(Ui.allow.text()).performClick()
        until("unpaired") { Core.live?.link?.state == LinkState.Unpaired }
        waitForIdle()
        onNodeWithText(Ui.desk_unpaired_title.format("z2")).assertExists()
        onNodeWithText(Ui.desk_link_unpaired.text().uppercase()).assertExists()
        onNodeWithText(Ui.desk_pair_again.text()).performClick()
        until("forgotten") { Core.servers.isEmpty() }
        waitForIdle()
        onNodeWithText(Ui.onb_title.text()).assertExists()
    }
}
