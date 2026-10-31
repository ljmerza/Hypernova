package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.PaidModel
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.data.SendStatus
import com.prudnikweb.hypernova.data.SessionSwitched
import com.prudnikweb.hypernova.data.TerminalBusy
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** How the hub takes the daemon's refusals and its absence: 401 unpairs, 409s say why, offline retries. */
@RunWith(RobolectricTestRunner::class)
class ErrorsTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    @After fun tearDown() = rig.close()

    /** Connected to a Mac (its words are the app's own; a Linux server's: [aLinuxServersHubSaysServerInItsOwnMessages]). */
    private fun connect(vararg sessions: JSONObject, approvals: List<JSONObject> = emptyList()) {
        daemon.snapshot = snapshot(sessions = sessions.toList().ifEmpty { listOf(session("s1")) }, approvals = approvals, platform = "darwin")
        rig.connect()
    }

    private fun words(id: Int) = rig.context.getString(id)

    /**
     * A Linux server's hub words its own messages (toasts, errors, banners) for a server, as the
     * screens around them do (ComputerWords.wordedFor). It once kept the Resources it was made with,
     * the plain Mac ones, until the app restarted.
     */
    @Test fun aLinuxServersHubSaysServerInItsOwnMessages() {
        daemon.snapshot = snapshot(sessions = listOf(session("s1")), platform = "linux")
        rig.connect()
        // The app's words follow the server now (ComputerWords.set from the snapshot)...
        assertTrue(words(R.string.problem_unpaired).contains("server"))
        // ...and so should the hub's.
        daemon.on("POST", "/v1/sessions/s1/rename") { refusal(401, "unauthorized", null) }
        val r = rig.call { rig.hub.renameSession("s1", "x") }
        assertEquals(words(R.string.problem_unpaired), r.exceptionOrNull()?.message)
    }

    // ─── 401: the server forgot this phone ───

    @Test fun a401OnTheSnapshotUnpairsAndNothingIsTriedAgain() {
        daemon.unauthorized = true
        rig.hub.start()
        rig.until("unpaired") { rig.hub.link.state == LinkState.Unpaired }
        assertEquals(Problem.Unauthorized, rig.hub.link.problem)
        val asked = daemon.requests.size
        rig.advance(5 * 60_000L)
        rig.hub.retry()
        rig.hub.foreground(true)
        rig.pump()
        Thread.sleep(150)
        rig.pump()
        assertEquals("no request after unpairing", asked, daemon.requests.size)
        assertEquals(0, daemon.opened.get())
    }

    @Test fun a401OnTheSocketUnpairs() {
        daemon.socketStatus = 401
        rig.hub.start()
        rig.until("unpaired") { rig.hub.link.state == LinkState.Unpaired }
        assertEquals(1, daemon.count("GET", "/v1/snapshot"))
    }

    @Test fun a401OnAnActionUnpairs() {
        connect()
        daemon.on("POST", "/v1/sessions/s1/rename") { refusal(401, "unauthorized", null) }
        val r = rig.call { rig.hub.renameSession("s1", "x") }
        assertTrue(r.isFailure)
        assertEquals(LinkState.Unpaired, rig.hub.link.state)
        assertEquals(words(R.string.problem_unpaired), r.exceptionOrNull()?.message)
    }

    @Test fun a401WhileOpeningASessionUnpairs() {
        connect()
        daemon.on("GET", "/v1/sessions/s1/events") { refusal(401, "unauthorized", null) }
        rig.hub.opened("s1")
        rig.until("unpaired") { rig.hub.link.state == LinkState.Unpaired }
    }

    // ─── 409s: the daemon says why ───

    @Test fun anApprovalAnsweredElsewhereSaysSoAndCountsAsDone() {
        connect(session("s1", state = "needs_you"), approvals = listOf(approval("a1", "s1")))
        daemon.ok("GET", "/v1/sessions/s1/events", JSONObject().put("events", JSONArray().put(JSONObject().put("id", "ev").put("kind", "approval").put("ts", 1L).put("approvalId", "a1"))))
        rig.hub.opened("s1")
        rig.until("history") { rig.hub.transcript("s1").isNotEmpty() }
        daemon.on("POST", "/v1/approvals/a1") { refusal(409, "already_resolved", "Already resolved") }
        assertTrue("the widget's Allow is done", rig.call { rig.hub.answerApproval("a1", Answer.Allow) })
        assertTrue(rig.hub.pendingApprovals.isEmpty())
        assertEquals(listOf(words(R.string.err_already_answered)), rig.toasts)
        val card = rig.hub.transcript("s1").filterIsInstance<Item.Approval>().single()
        assertEquals("a neutral line until the server's own word", "elsewhere", card.by ?: card.resolved?.let { "elsewhere" })
    }

    @Test fun anAnswerThatFailsComesBackAndSaysSo() {
        connect(session("s1", state = "needs_you"), approvals = listOf(approval("a1", "s1")))
        daemon.on("POST", "/v1/approvals/a1") { refusal(500, "boom", "The hook is gone") }
        assertTrue(!rig.call { rig.hub.answerApproval("a1", Answer.Allow) })
        assertEquals(listOf("The hook is gone"), rig.toasts)
        assertEquals(listOf("a1"), rig.hub.waitingAsks("s1").map { it.id })
    }

    @Test fun aPaidModelAsksFirst() {
        connect(session("s1"))
        daemon.on("POST", "/v1/sessions/s1/model") { refusal(409, "confirm_paid", "Opus via OpenRouter costs money. Switch?") }
        val e = rig.call { rig.hub.setModel("s1", "openrouter/opus", false) }.exceptionOrNull()
        assertTrue("$e", e is PaidModel)
        assertEquals("Opus via OpenRouter costs money. Switch?", e!!.message)
    }

    @Test fun unsentTextInTheTerminalIsOfferedToReplace() {
        connect(session("s1"))
        daemon.on("POST", "/v1/sessions/s1/prompt") { refusal(409, "typing", "There's text in the terminal") { put("pending", "half a sentence") } }
        val e = rig.call { rig.hub.send("s1", "hello", emptyList(), false) }.exceptionOrNull()
        assertTrue("$e", e is TerminalBusy)
        assertEquals("half a sentence", (e as TerminalBusy).pending)
        // Its copy goes: the composer has the text back.
        assertTrue(rig.hub.transcript("s1").none { it is Item.User })
        assertEquals(0, rig.hub.waitingCount("s1"))
    }

    @Test fun aConversationThatWentOnElsewhereSaysWhere() {
        connect(session("s1"))
        daemon.on("POST", "/v1/sessions/s1/prompt") { refusal(409, "switched", "It went on in Codex") { put("to", "s9") } }
        val e = rig.call { rig.hub.send("s1", "hello", emptyList(), false) }.exceptionOrNull()
        assertTrue("$e", e is SessionSwitched)
        assertEquals("s9", (e as SessionSwitched).to)
    }

    @Test fun anyOther409IsTheServersOwnWords() {
        connect(session("s1"))
        daemon.on("POST", "/v1/sessions/s1/prompt") { refusal(409, "busy", "The terminal is starting") }
        val e = rig.call { rig.hub.send("s1", "hello", emptyList(), false) }.exceptionOrNull()
        assertEquals("The terminal is starting", e?.message)
        assertEquals("not held on the phone: the server answered", 0, rig.hub.waitingCount("s1"))
    }

    @Test fun aRefusalWithoutWordsHasTheAppsOwn() {
        connect(session("s1"))
        daemon.on("POST", "/v1/sessions/s1/rename") { refusal(500, "boom", null) }
        assertEquals(words(R.string.err_rename), rig.call { rig.hub.renameSession("s1", "x") }.exceptionOrNull()?.message)
    }

    @Test fun aWaitingPromptWhoseSessionIsGoneWaitsForANewSession() {
        connect(session("s1"))
        daemon.down = true
        daemon.closeSocket()
        rig.until("not linked") { rig.hub.link.state != LinkState.Connected && rig.hub.link.retryAt != null }
        rig.call { rig.hub.send("s1", "still there?", emptyList(), false) }
        daemon.on("POST", "/v1/sessions/s1/prompt") { refusal(404, "no_session", "No such session") }
        daemon.down = false
        rig.advance(30_100)
        rig.until("the flush") { daemon.count("POST", "/v1/sessions/s1/prompt") == 1 && rig.hub.orphanedPrompts().isNotEmpty() }
        assertEquals(SendStatus.SessionGone, rig.hub.orphanedPrompts().single().status)
        assertEquals(0, rig.hub.waitingCount("s1"))
    }

    // ─── The server offline ───

    @Test fun aServerThatIsntThereIsOfflineAndTriedAgainAtOnceThenEveryThirtySeconds() {
        daemon.close()
        rig.hub.start()
        rig.until("the at-once retry failed") { rig.hub.link.retryAt != null }
        val link = rig.hub.link
        // Never linked: Offline from the start, not "Reconnecting".
        assertEquals(LinkState.Offline, link.state)
        assertEquals(Problem.Unreachable, link.problem)
        val tried = link.tried!!
        assertNull("nothing answered the round", tried.winner)
        val wait = link.retryAt!! - rig.now
        assertTrue("next try in $wait ms", wait in 25_000L..30_000L)
    }

    @Test fun anActionWhileTheServerIsAwaySaysItIsntAnswering() {
        connect(session("s1"))
        daemon.down = true
        val r = rig.call { rig.hub.renameSession("s1", "x") }
        assertEquals(words(R.string.problem_unreachable), r.exceptionOrNull()?.message)
        assertTrue("a request that failed doesn't unpair", rig.hub.link.state != LinkState.Unpaired)
    }

    @Test fun aServerErrorOnTheSnapshotIsRetried() {
        daemon.on("GET", "/v1/snapshot") { refusal(500, "boom", "Snapshot failed") }
        rig.hub.start()
        rig.until("the at-once retry failed") { daemon.count("GET", "/v1/snapshot") >= 2 && rig.hub.link.retryAt != null }
        assertEquals(Problem.Server, rig.hub.link.problem)
        daemon.on("GET", "/v1/snapshot") { FakeDaemon.json(200, daemon.snapshot) }
        rig.advance(30_100)
        rig.until("linked") { rig.hub.link.state == LinkState.Connected }
    }
}
