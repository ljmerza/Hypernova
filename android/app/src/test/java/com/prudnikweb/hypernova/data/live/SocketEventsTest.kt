package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.Resolution
import com.prudnikweb.hypernova.data.SendStatus
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.usage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the live socket pushes (docs/PROTOCOL.md, "WebSocket `GET /v1/ws`") and what the hub makes of it. */
@RunWith(RobolectricTestRunner::class)
class SocketEventsTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    @After fun tearDown() = rig.close()

    private fun connectWith(vararg sessions: JSONObject, approvals: List<JSONObject> = emptyList()) {
        daemon.snapshot = snapshot(sessions = sessions.toList(), approvals = approvals)
        rig.connect()
    }

    private fun event(sessionId: String, e: JSONObject) = msg("event") { put("sessionId", sessionId).put("event", e) }
    private fun user(id: String, text: String, clientId: String? = null) = JSONObject().put("id", id).put("kind", "user").put("ts", 5_000L).put("text", text).apply { if (clientId != null) put("clientId", clientId) }
    private fun assistant(id: String, text: String, streaming: Boolean = false) = JSONObject().put("id", id).put("kind", "assistant").put("ts", 5_000L).put("text", text).put("streaming", streaming)

    /** Opens [id] with [events] as its history. */
    private fun open(id: String, vararg events: JSONObject) {
        daemon.ok("GET", "/v1/sessions/$id/events", JSONObject().put("events", JSONArray(events.toList())))
        daemon.ok("GET", "/v1/sessions/$id/agents", JSONObject().put("agents", JSONArray()).put("workflows", JSONArray()))
        rig.hub.opened(id)
        rig.until("$id's history") { !rig.hub.loadingTranscript(id) && daemon.count("GET", "/v1/sessions/$id/events") > 0 }
    }

    @Test fun aSessionMessageAddsAndUpdatesASession() {
        connectWith(session("s1", state = "idle"))
        rig.push(msg("session") { put("session", session("s2", state = "working", lastActivityAt = 9_000)) }) { rig.hub.session("s2") != null }
        assertEquals(listOf("s2", "s1"), rig.hub.sessions.map { it.id })
        assertEquals(SessionState.Working, rig.hub.session("s2")!!.state)
        assertTrue("one the snapshot didn't have is new", rig.hub.session("s2")!!.isNew)
        assertFalse(rig.hub.session("s1")!!.isNew)

        rig.push(msg("session") { put("session", session("s2", title = "Fix the parser", lastActivityAt = 9_500)) }) { rig.hub.session("s2")!!.name == "Fix the parser" }
        assertEquals(SessionState.Idle, rig.hub.session("s2")!!.state)
    }

    @Test fun sessionRemovedTakesItOff() {
        connectWith(session("s1"), session("s2"))
        rig.push(msg("session.removed") { put("id", "s1") }) { rig.hub.session("s1") == null }
        assertEquals(listOf("s2"), rig.hub.sessions.map { it.id })
    }

    @Test fun aNewApprovalIsPendingAndAnnouncedThenResolvedTakesItAway() {
        connectWith(session("s1", state = "working"))
        rig.push(msg("approval") { put("approval", approval("a1", "s1", summary = "Bash: make")) }) { rig.hub.pendingApprovals.isNotEmpty() }
        assertEquals(SessionState.NeedsYou, rig.hub.session("s1")!!.state)
        assertEquals("Bash: make", rig.hub.session("s1")!!.activity)
        rig.until("its banner") { rig.banners.any { it.id == "a:a1" && it.kind == Notice.Kind.Approval } }
        assertEquals(listOf("a1"), rig.hub.waitingAsks("s1").map { it.id })

        rig.push(msg("approval.resolved") { put("id", "a1").put("sessionId", "s1").put("decision", "allow").put("by", "mac") }) { rig.hub.pendingApprovals.isEmpty() }
        assertEquals(listOf("a1"), rig.gone)
        assertNull(rig.hub.approval("s1"))
        assertEquals(SessionState.Working, rig.hub.session("s1")!!.state)
    }

    @Test fun anApprovalOffScreenIsANotification() {
        connectWith(session("s1", state = "working"))
        rig.visible = false
        rig.push(msg("approval") { put("approval", approval("a1", "s1")) }) { rig.notified.isNotEmpty() }
        assertEquals("a1", rig.notified.single().first.id)
    }

    @Test fun anAgentsApprovalWaitsTenSecondsBeforeItNotifiesAndSaysNothingIfSettledMeanwhile() {
        connectWith(session("s1", state = "working"))
        rig.visible = false
        rig.push(msg("approval") { put("approval", approval("a1", "s1") { put("agentId", "ag1").put("agentLabel", "Review") }) }) { rig.hub.pendingApprovals.isNotEmpty() }
        rig.advance(9_000)
        assertTrue(rig.notified.isEmpty())
        rig.advance(1_100)
        assertEquals(listOf("a1"), rig.notified.map { it.first.id })

        rig.push(msg("approval") { put("approval", approval("a2", "s1") { put("agentId", "ag1") }) }) { rig.hub.pendingApprovals.size == 2 }
        rig.push(msg("approval.resolved") { put("id", "a2").put("decision", "allow").put("by", "mac") }) { rig.hub.pendingApprovals.size == 1 }
        rig.advance(11_000)
        assertEquals(listOf("a1"), rig.notified.map { it.first.id })
    }

    @Test fun eventsGrowTheOpenTranscriptAndEventRemovedTakesOneBack() {
        connectWith(session("s1", state = "working"))
        open("s1", user("e1", "hello"))
        assertEquals(listOf("e1"), rig.hub.transcript("s1").map { it.key })

        rig.push(event("s1", assistant("e2", "Hi", streaming = true))) { rig.hub.transcript("s1").size == 2 }
        // The same id replaces the previous version (streaming text grows).
        rig.push(event("s1", assistant("e2", "Hi there"))) { (rig.hub.transcript("s1").last() as? Item.Assistant)?.text == "Hi there" }
        assertEquals(2, rig.hub.transcript("s1").size)
        rig.push(event("s1", JSONObject().put("id", "n1").put("kind", "notice").put("ts", 6_000L).put("text", "Waiting in the terminal"))) {
            rig.hub.transcript("s1").any { it.key == "n1" }
        }
        rig.push(msg("event.removed") { put("sessionId", "s1").put("id", "n1") }) { rig.hub.transcript("s1").none { it.key == "n1" } }
        assertEquals(listOf("e1", "e2"), rig.hub.transcript("s1").map { it.key })
    }

    @Test fun aHistoryFailureIsSaidAndCanBeRetried() {
        connectWith(session("s1"))
        daemon.on("GET", "/v1/sessions/s1/events") { FakeDaemon.refusal(500, "boom", "The transcript is unreadable") }
        rig.hub.opened("s1")
        rig.until("the error") { rig.hub.transcriptError("s1") != null }
        assertEquals("The transcript is unreadable", rig.hub.transcriptError("s1"))
        daemon.ok("GET", "/v1/sessions/s1/events", JSONObject().put("events", JSONArray(listOf(user("e1", "hi")))))
        rig.hub.reloadTranscript("s1")
        rig.until("the history") { rig.hub.transcript("s1").isNotEmpty() }
        assertNull(rig.hub.transcriptError("s1"))
    }

    @Test fun aResyncSnapshotReloadsTheOpenSession() {
        connectWith(session("s1"))
        open("s1", user("e1", "hello"))
        val loads = daemon.count("GET", "/v1/sessions/s1/events")
        rig.push(msg("snapshot") { put("snapshot", daemon.snapshot) }) { daemon.count("GET", "/v1/sessions/s1/events") > loads }
    }

    @Test fun aNewerAppOnTheServerIsOfferedAndWithdrawnWithoutReconnecting() {
        connectWith(session("s1"))
        assertNull(rig.hub.appUpdate)
        rig.push(msg("app") { put("app", JSONObject().put("versionCode", 2_000_000_000).put("versionName", "9.9.9")) }) { rig.hub.appUpdate != null }
        val u = rig.hub.appUpdate!!
        assertEquals("9.9.9", u.versionName)
        assertEquals(daemon.base + "/download/android", u.url)
        // On screen the banner comes from appUpdate itself: no notification.
        assertTrue(rig.notices.none { it.kind == Notice.Kind.Update })
        rig.push(msg("app") { put("app", JSONObject.NULL) }) { rig.hub.appUpdate == null }
        assertEquals(1, daemon.opened.get())
    }

    @Test fun aNewerAppOffScreenIsANotificationOncePerVersion() {
        connectWith(session("s1"))
        rig.visible = false
        val app = JSONObject().put("versionCode", 2_000_000_001).put("versionName", "9.9.10")
        rig.push(msg("app") { put("app", app) }) { rig.notices.any { it.kind == Notice.Kind.Update } }
        daemon.push(msg("app") { put("app", app) })
        rig.sync()
        assertEquals(1, rig.notices.count { it.kind == Notice.Kind.Update })
    }

    @Test fun anOlderOrSameAppIsNoOffer() {
        connectWith(session("s1"))
        daemon.push(msg("app") { put("app", JSONObject().put("versionCode", 1).put("versionName", "0.0.1")) })
        rig.sync()
        assertNull(rig.hub.appUpdate)
    }

    @Test fun aDeadJobIsSaidOnce() {
        connectWith(session("s1", title = "Nightly build"))
        val died = msg("job.died") { put("sessionId", "s1").put("job", "j1").put("reason", "API error 529").put("at", 7_000L) }
        rig.push(died) { rig.banners.any { it.id == "died:s1:7000" } }
        val n = rig.banners.single { it.id == "died:s1:7000" }
        assertEquals(Notice.Kind.Error, n.kind)
        assertEquals("Nightly build", n.title)
        assertEquals("API error 529", n.text)
        daemon.push(died)
        rig.sync()
        assertEquals(1, rig.banners.count { it.id == "died:s1:7000" })
    }

    @Test fun theClaudeSettingFollowsItsMessage() {
        connectWith(session("s1"))
        rig.push(msg("claude") { put("claude", JSONObject().put("available", true).put("version", "2.1.400").put("agents", true)) }) { rig.hub.claude?.agents == true }
        assertEquals("2.1.400", rig.hub.claude!!.version)
        rig.push(msg("claude") { put("claude", JSONObject().put("available", true).put("agents", false)) }) { rig.hub.claude?.agents == false }
    }

    @Test fun usageMessagesMoveTheSun() {
        connectWith(session("s1"))
        val reset = rig.now + 3_600_000L
        rig.push(msg("usage") { put("usage", usage(55.0, 20.0, reset, rig.now + 86_400_000L)) }) { rig.hub.usage.fiveHour > 0.5f }
        assertEquals(0.55f, rig.hub.usage.fiveHour, 0.001f)
        // The window resets on time, before the Mac says so.
        rig.advance(3_600_000L + 1_000)
        assertEquals(0f, rig.hub.usage.fiveHour, 0.001f)
    }

    @Test fun heldPromptsShowAsHeldAndBecomeSentCopiesWhenTheServerTypesThemIn() {
        val held = JSONArray().put(JSONObject().put("id", "h1").put("text", "and run the tests").put("at", 4_000L).put("clientId", "c1"))
        connectWith(session("s1", state = "needs_you") { put("held", held) })
        open("s1", user("e1", "fix it"))
        assertEquals(1, rig.hub.heldCount("s1"))
        val shown = rig.hub.transcript("s1").filterIsInstance<Item.User>().single { it.key == "held:h1" }
        assertEquals(SendStatus.Held, shown.status)
        assertEquals("h1", shown.heldId)

        // The Mac typed it in: no longer held, this phone's copy stands in until the Mac's own arrives.
        rig.push(msg("session") { put("session", session("s1", state = "working")) }) { rig.hub.heldCount("s1") == 0 }
        val copy = rig.hub.transcript("s1").filterIsInstance<Item.User>().single { it.text == "and run the tests" }
        assertEquals(SendStatus.Sending, copy.status)
        rig.push(event("s1", user("e2", "and run the tests", clientId = "c1"))) { rig.hub.transcript("s1").any { it.key == "e2" } }
        val users = rig.hub.transcript("s1").filterIsInstance<Item.User>()
        assertEquals(listOf("e1", "e2"), users.map { it.key })
        assertTrue(users.all { it.status == null })
    }

    @Test fun aHeldCopyNobodyClaimsGoesAfterFifteenSeconds() {
        val held = JSONArray().put(JSONObject().put("id", "h1").put("text", "later").put("at", 4_000L))
        connectWith(session("s1", state = "needs_you") { put("held", held) })
        open("s1")
        rig.push(msg("session") { put("session", session("s1", state = "working")) }) { rig.hub.heldCount("s1") == 0 }
        assertTrue(rig.hub.transcript("s1").any { (it as? Item.User)?.text == "later" })
        rig.advance(15_100)
        assertTrue(rig.hub.transcript("s1").none { (it as? Item.User)?.text == "later" })
    }

    @Test fun aTurnThatEndsIsAFinishedNoticeAMomentLater() {
        connectWith(session("s1", state = "working", title = "Docs"))
        rig.push(msg("session") { put("session", session("s1", state = "idle", title = "Docs", lastActivityAt = 8_000) { put("lastText", "All done.") }) }) { rig.hub.session("s1")!!.state == SessionState.Idle }
        assertTrue(rig.banners.none { it.kind == Notice.Kind.Finished })
        rig.advance(1_600)
        val n = rig.banners.single { it.kind == Notice.Kind.Finished }
        assertEquals("s1", n.sessionId)
        assertEquals("Docs", n.title)
        assertEquals("All done.", n.text)
    }

    @Test fun aTurnThatCarriesOnWithinTheMomentSaysNothing() {
        connectWith(session("s1", state = "working"))
        rig.push(msg("session") { put("session", session("s1", state = "idle", lastActivityAt = 8_000)) }) { rig.hub.session("s1")!!.state == SessionState.Idle }
        rig.push(msg("session") { put("session", session("s1", state = "working", lastActivityAt = 8_100)) }) { rig.hub.session("s1")!!.state == SessionState.Working }
        rig.advance(2_000)
        assertTrue(rig.banners.none { it.kind == Notice.Kind.Finished })
    }

    @Test fun anErrorIsSaidAtOnceAndOffScreenAsANotification() {
        connectWith(session("s1", state = "working"))
        rig.visible = false
        rig.push(msg("session") { put("session", session("s1", state = "error") { put("lastText", "Claude Code crashed") }) }) { rig.notices.isNotEmpty() }
        val n = rig.notices.single()
        assertEquals(Notice.Kind.Error, n.kind)
        assertEquals("Claude Code crashed", n.text)
    }

    @Test fun waitingInTheTerminalIsSaidWithItsQuestion() {
        connectWith(session("s1", state = "working"))
        rig.push(msg("session") { put("session", session("s1", state = "needs_you") { put("question", "Which branch?") }) }) { rig.hub.session("s1")!!.state == SessionState.NeedsYou }
        rig.advance(1_600)
        val n = rig.banners.single { it.kind == Notice.Kind.Waiting }
        assertEquals("Which branch?", n.text)
    }

    @Test fun theAnswerShownAtOnceIsReplacedByTheServersResolution() {
        connectWith(session("s1", state = "needs_you"), approvals = listOf(approval("a1", "s1")))
        daemon.ok("POST", "/v1/approvals/a1")
        open("s1", JSONObject().put("id", "ev-a1").put("kind", "approval").put("ts", 5_000L).put("approvalId", "a1").put("summary", "Bash: ls"))
        val ok = rig.call { rig.hub.answerApproval("a1", com.prudnikweb.hypernova.data.Answer.Deny) }
        assertTrue(ok)
        val card = rig.hub.transcript("s1").filterIsInstance<Item.Approval>().single()
        assertEquals(Resolution.Denied, card.resolved)
        rig.push(msg("approval.resolved") { put("id", "a1").put("sessionId", "s1").put("decision", "deny").put("by", "phone") }) {
            rig.hub.transcript("s1").filterIsInstance<Item.Approval>().single().by == "phone"
        }
        assertNotNull(rig.hub.transcript("s1").filterIsInstance<Item.Approval>().single().resolved)
    }
}
