package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.NewSessionMemory
import com.prudnikweb.hypernova.data.AlwaysScope
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Asides
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Resolution
import com.prudnikweb.hypernova.data.SendStatus
import com.prudnikweb.hypernova.data.WaitingTooLate
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the hub's actions ask of the daemon (docs/PROTOCOL.md, "REST", "Approvals", "Session menu", "Held prompts"). */
@RunWith(RigRunner::class)
class ActionsTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    @After fun tearDown() = rig.close()

    private fun connect(vararg sessions: JSONObject, approvals: List<JSONObject> = emptyList()) {
        daemon.snapshot = snapshot(sessions = sessions.toList().ifEmpty { listOf(session("s1")) }, approvals = approvals)
        rig.connect()
    }

    private fun last(method: String, path: String): FakeDaemon.Req = daemon.calls(method, path).last()

    // ─── Approvals ───

    private fun answered(answer: Answer): JSONObject {
        connect(session("s1", state = "needs_you"), approvals = listOf(approval("a1", "s1")))
        daemon.ok("POST", "/v1/approvals/a1")
        assertTrue(rig.call { rig.hub.answerApproval("a1", answer) })
        assertTrue("answered: off the pending list", rig.hub.pendingApprovals.isEmpty())
        return last("POST", "/v1/approvals/a1").json
    }

    @Test fun allow() = assertEquals("allow", answered(Answer.Allow).getString("decision"))

    @Test fun deny() = assertEquals("deny", answered(Answer.Deny).getString("decision"))

    @Test fun always() {
        val b = answered(Answer.Always)
        assertEquals("always", b.getString("decision"))
        assertFalse(b.has("scope"))
    }

    @Test fun alwaysWithAScopeAndRules() {
        val b = answered(Answer.AlwaysAs(AlwaysScope.Project, listOf("Bash(npm test:*)", "Bash(npm run lint)")))
        assertEquals("always", b.getString("decision"))
        assertEquals("project", b.getString("scope"))
        assertEquals(listOf("Bash(npm test:*)", "Bash(npm run lint)"), b.getJSONArray("rules").let { a -> (0 until a.length()).map(a::getString) })
    }

    @Test fun forTheSession() = assertEquals("session", answered(Answer.Session).getString("decision"))

    @Test fun approveAPlan() = assertEquals("allow", answered(Answer.ApprovePlan).getString("decision"))

    @Test fun keepPlanningIsADenyWithAMessage() {
        val b = answered(Answer.KeepPlanning)
        assertEquals("deny", b.getString("decision"))
        assertTrue(b.getString("message").isNotBlank())
    }

    @Test fun answersToQuestions() {
        val b = answered(Answer.Answers(mapOf("Which DB?" to "Postgres")))
        assertEquals("allow", b.getString("decision"))
        assertEquals("Postgres", b.getJSONObject("answers").getString("Which DB?"))
    }

    @Test fun theAnswerShowsAtOnceBeforeTheServerConfirms() {
        connect(session("s1", state = "needs_you"), approvals = listOf(approval("a1", "s1")))
        daemon.ok("GET", "/v1/sessions/s1/events", JSONObject().put("events", JSONArray().put(JSONObject().put("id", "ev").put("kind", "approval").put("ts", 1L).put("approvalId", "a1"))))
        rig.hub.opened("s1")
        // The waiting approval has its card from the snapshot alone: the history is in when its load is over.
        rig.until("history") { !rig.hub.loadingTranscript("s1") }
        // The daemon holds the answer a moment; the card says Allowed meanwhile.
        val gate = java.util.concurrent.CountDownLatch(1)
        daemon.on("POST", "/v1/approvals/a1") { gate.await(); json(200, JSONObject()) }
        val d = rig.hub.answerAsync("a1", Answer.Allow)
        rig.pump()
        assertEquals(Resolution.Allowed, rig.hub.transcript("s1").filterIsInstance<Item.Approval>().single().resolved)
        assertTrue(rig.hub.waitingAsks("s1").isEmpty())
        gate.countDown()
        rig.until("the answer") { d.isCompleted }
    }

    @Test fun allowSeveralAtOnce() {
        connect(session("s1", state = "needs_you"), approvals = listOf(approval("a1", "s1"), approval("a2", "s1"), approval("a3", "s1")))
        daemon.ok("POST", "/v1/sessions/s1/allow", JSONObject().put("allowed", JSONArray(listOf("a1", "a2"))))
        val r = rig.call { rig.hub.allowSeveral("s1", listOf("a1", "a2")) }
        assertEquals(2, r.getOrThrow())
        assertEquals(listOf("a1", "a2"), last("POST", "/v1/sessions/s1/allow").json.getJSONArray("ids").let { a -> (0 until a.length()).map(a::getString) })
        assertEquals(listOf("a3"), rig.hub.pendingApprovals.map { it.id })
    }

    @Test fun allowSeveralThatFailsPutsTheAsksBack() {
        connect(session("s1", state = "needs_you"), approvals = listOf(approval("a1", "s1"), approval("a2", "s1")))
        daemon.on("POST", "/v1/sessions/s1/allow") { FakeDaemon.refusal(500, "boom", "Could not allow") }
        val r = rig.call { rig.hub.allowSeveral("s1", listOf("a1", "a2")) }
        assertEquals("Could not allow", r.exceptionOrNull()?.message)
        assertEquals(listOf("a1", "a2"), rig.hub.waitingAsks("s1").map { it.id })
    }

    // ─── Prompts ───

    @Test fun aPromptAsksToBeHeldAndCarriesItsClientId() {
        connect(session("s1", state = "working"))
        daemon.ok("POST", "/v1/sessions/s1/prompt", JSONObject().put("session", session("s1", state = "working")))
        val r = rig.call { rig.hub.send("s1", "  run the tests  ", emptyList(), false) }
        assertEquals("s1", r.getOrThrow())
        val b = last("POST", "/v1/sessions/s1/prompt").json
        assertEquals("run the tests", b.getString("text"))
        assertTrue(b.getBoolean("hold"))
        assertFalse(b.has("replace"))
        assertTrue(b.getString("clientId").isNotBlank())
    }

    @Test fun aSentPromptShowsAsSendingUntilTheServersCopyArrives() {
        connect(session("s1", state = "idle"))
        daemon.ok("GET", "/v1/sessions/s1/events", JSONObject().put("events", JSONArray()))
        rig.hub.opened("s1")
        rig.until("history") { !rig.hub.loadingTranscript("s1") }
        daemon.ok("POST", "/v1/sessions/s1/prompt", JSONObject().put("session", session("s1", state = "working")))
        rig.call { rig.hub.send("s1", "hello", emptyList(), false) }
        val copy = rig.hub.transcript("s1").filterIsInstance<Item.User>().single()
        assertEquals(SendStatus.Sending, copy.status)
        assertTrue(rig.hub.sending("s1"))
        val cid = last("POST", "/v1/sessions/s1/prompt").json.getString("clientId")
        rig.push(msg("event") { put("sessionId", "s1").put("event", JSONObject().put("id", "e1").put("kind", "user").put("ts", 9L).put("text", "hello").put("clientId", cid)) }) {
            rig.hub.transcript("s1").any { it.key == "e1" }
        }
        val users = rig.hub.transcript("s1").filterIsInstance<Item.User>()
        assertEquals(listOf("e1"), users.map { it.key })
        assertNull(users.single().status)
        assertFalse(rig.hub.sending("s1"))
    }

    @Test fun aPromptTheServerHeldIsShownByTheSessionsHeldNotACopy() {
        connect(session("s1", state = "needs_you"), approvals = listOf(approval("a1", "s1")))
        daemon.ok("GET", "/v1/sessions/s1/events", JSONObject().put("events", JSONArray()))
        rig.hub.opened("s1")
        rig.until("history") { !rig.hub.loadingTranscript("s1") }
        val held = JSONArray().put(JSONObject().put("id", "h1").put("text", "after that, deploy").put("at", 1L))
        daemon.ok("POST", "/v1/sessions/s1/prompt", JSONObject().put("session", session("s1", state = "needs_you") { put("held", held) }).put("queued", true).put("held", true).put("heldId", "h1"))
        rig.call { rig.hub.send("s1", "after that, deploy", emptyList(), false) }
        val users = rig.hub.transcript("s1").filterIsInstance<Item.User>()
        assertEquals(listOf("held:h1"), users.map { it.key })
        assertEquals(SendStatus.Held, users.single().status)
        assertEquals(1, rig.hub.heldCount("s1"))
    }

    @Test fun anImmediateCommandTheServerTypesAtOnceIsNotHeld() {
        // The phone always asks for hold; the daemon types Claude Code's immediate commands (/btw, /status…) at once.
        connect(session("s1", state = "needs_you"), approvals = listOf(approval("a1", "s1")))
        daemon.ok("GET", "/v1/sessions/s1/events", JSONObject().put("events", JSONArray()))
        rig.hub.opened("s1")
        rig.until("history") { !rig.hub.loadingTranscript("s1") }
        daemon.ok("POST", "/v1/sessions/s1/prompt", JSONObject().put("session", session("s1", state = "needs_you")).put("queued", false))
        rig.call { rig.hub.send("s1", "/status", emptyList(), false) }
        assertTrue(last("POST", "/v1/sessions/s1/prompt").json.getBoolean("hold"))
        val users = rig.hub.transcript("s1").filterIsInstance<Item.User>()
        assertEquals(1, users.size)
        assertEquals(SendStatus.Sending, users.single().status)
        assertEquals(0, rig.hub.heldCount("s1"))
    }

    @Test fun replaceSendsWithoutHold() {
        connect(session("s1"))
        daemon.ok("POST", "/v1/sessions/s1/prompt", JSONObject().put("session", session("s1")))
        rig.call { rig.hub.send("s1", "new text", emptyList(), true) }
        val b = last("POST", "/v1/sessions/s1/prompt").json
        assertTrue(b.getBoolean("replace"))
        assertFalse(b.has("hold"))
    }

    @Test fun aSideQuestionGoesToTheBtwRoute() {
        assertEquals("why is it slow?", Asides.sideQuestion("/btw why is it slow?"))
        assertNull("a bare /btw is not a question", Asides.sideQuestion("/btw"))
        connect(session("s1", state = "working"))
        daemon.ok("POST", "/v1/sessions/s1/btw", JSONObject().put("aside", JSONObject().put("id", "b1").put("question", "why is it slow?").put("state", "asking").put("askedAt", 1L)))
        rig.call { rig.hub.askAside("s1", "  why is it slow?  ") }
        assertEquals("why is it slow?", last("POST", "/v1/sessions/s1/btw").json.getString("question"))
        assertEquals(0, daemon.count("POST", "/v1/sessions/s1/prompt"))
    }

    @Test fun whileTheServerIsAwayAPromptWaitsOnThePhoneAndGoesWhenItIsBack() {
        connect(session("s1"))
        daemon.down = true
        daemon.closeSocket()
        // The at-once retry has failed too: the next one waits 30 s.
        rig.until("not linked") { rig.hub.link.state != LinkState.Connected && rig.hub.link.retryAt != null }
        rig.call { rig.hub.send("s1", "when you're back", emptyList(), false) }
        assertEquals(0, daemon.count("POST", "/v1/sessions/s1/prompt"))
        assertEquals(1, rig.hub.waitingCount("s1"))
        assertEquals(SendStatus.WaitingMac, rig.hub.waiting("s1").single().status)

        daemon.ok("POST", "/v1/sessions/s1/prompt", JSONObject().put("session", session("s1", state = "working")))
        daemon.down = false
        rig.advance(30_100)
        rig.until("the flush") { daemon.count("POST", "/v1/sessions/s1/prompt") == 1 && rig.hub.waitingCount("s1") == 0 }
        assertEquals("when you're back", last("POST", "/v1/sessions/s1/prompt").json.getString("text"))
    }

    @Test fun aPromptNothingAnsweredWaitsUnderTheSameNameAndGoesAgain() {
        connect(session("s1"))
        daemon.on("POST", "/v1/sessions/s1/prompt") { FakeDaemon.drop() }
        val r = rig.call { rig.hub.send("s1", "are you there", emptyList(), false) }
        assertTrue("held on the phone, not failed", r.isSuccess)
        assertEquals("waiting (link ${rig.hub.link}, toasts ${rig.toasts}, calls ${daemon.count("POST", "/v1/sessions/s1/prompt")})", 1, rig.hub.waitingCount("s1"))
        val first = daemon.calls("POST", "/v1/sessions/s1/prompt").last().json.getString("clientId")
        daemon.ok("POST", "/v1/sessions/s1/prompt", JSONObject().put("session", session("s1")))
        rig.advance(20_100)
        rig.until("sent again") { rig.hub.waitingCount("s1") == 0 }
        assertEquals("the same clientId, so a repeat is safe", first, last("POST", "/v1/sessions/s1/prompt").json.getString("clientId"))
    }

    @Test fun editAndRemoveAHeldPrompt() {
        val held = JSONArray().put(JSONObject().put("id", "h1").put("text", "deploy").put("at", 1L))
        connect(session("s1", state = "needs_you") { put("held", held) })
        daemon.ok("PUT", "/v1/sessions/s1/held/h1", JSONObject().put("session", session("s1", state = "needs_you") { put("held", JSONArray().put(JSONObject().put("id", "h1").put("text", "deploy to staging").put("at", 1L))) }))
        assertTrue(rig.call { rig.hub.editHeld("s1", "h1", "deploy to staging") }.isSuccess)
        assertEquals("deploy to staging", last("PUT", "/v1/sessions/s1/held/h1").json.getString("text"))
        daemon.ok("DELETE", "/v1/sessions/s1/held/h1", JSONObject().put("session", session("s1", state = "needs_you")))
        assertTrue(rig.call { rig.hub.removeHeld("s1", "h1") }.isSuccess)
        assertEquals(0, rig.hub.heldCount("s1"))
        // Taken back: no copy of it stands in as sent.
        assertTrue(rig.hub.transcript("s1").none { (it as? Item.User)?.text?.startsWith("deploy") == true })
    }

    @Test fun editingAHeldPromptStopsItBeingSentFirstThenSavesOrCancels() {
        val h = JSONObject().put("id", "h1").put("text", "deploy").put("at", 1L)
        connect(session("s1", state = "needs_you") { put("held", JSONArray().put(h)) })
        val item = rig.hub.transcript("s1").filterIsInstance<Item.User>().single { it.heldId == "h1" }
        daemon.ok("PUT", "/v1/sessions/s1/held/h1", JSONObject().put("session", session("s1", state = "needs_you") { put("held", JSONArray().put(JSONObject(h.toString()).put("editing", true))) }))
        val edit = rig.call { rig.hub.openWaitingEdit("s1", item) }.getOrThrow()
        assertTrue("the server is told before anything opens", last("PUT", "/v1/sessions/s1/held/h1").json.getBoolean("editing"))
        assertTrue(rig.hub.transcript("s1").filterIsInstance<Item.User>().single { it.heldId == "h1" }.editing)
        assertEquals("deploy", edit.text)
        daemon.ok("PUT", "/v1/sessions/s1/held/h1", JSONObject().put("session", session("s1", state = "needs_you") { put("held", JSONArray().put(h)) }))
        rig.hub.cancelWaitingEdit(edit)
        rig.until("told it's no longer being edited") { !last("PUT", "/v1/sessions/s1/held/h1").json.getBoolean("editing") }
        assertTrue(rig.call { rig.hub.saveWaitingEdit(edit, " deploy to staging ") }.isSuccess)
        assertEquals("deploy to staging", last("PUT", "/v1/sessions/s1/held/h1").json.getString("text"))
    }

    @Test fun aHeldPromptThatLeftBeforeTheChoiceSaysSoInTheChatNotInAToast() {
        val h = JSONObject().put("id", "h1").put("text", "deploy").put("at", 1L)
        connect(session("s1", state = "needs_you") { put("held", JSONArray().put(h)) })
        val item = rig.hub.transcript("s1").filterIsInstance<Item.User>().single { it.heldId == "h1" }
        daemon.on("PUT", "/v1/sessions/s1/held/h1") { refusal(404, "no_held", "That message isn’t held any more: it was sent or removed.") }
        val r = rig.call { rig.hub.openWaitingEdit("s1", item) }
        assertTrue(r.exceptionOrNull() is WaitingTooLate)
        daemon.on("DELETE", "/v1/sessions/s1/held/h1") { refusal(409, "sending", "That message is being sent right now.") }
        assertTrue(rig.call { rig.hub.removeWaitingMessage("s1", item) }.exceptionOrNull() is WaitingTooLate)
    }

    @Test fun aServerFromBeforeItCouldHoldOneBackStillEdits() {
        val h = JSONObject().put("id", "h1").put("text", "deploy").put("at", 1L)
        connect(session("s1", state = "needs_you") { put("held", JSONArray().put(h)) })
        val item = rig.hub.transcript("s1").filterIsInstance<Item.User>().single { it.heldId == "h1" }
        daemon.on("PUT", "/v1/sessions/s1/held/h1") { refusal(400, "empty", "Write something first.") }
        assertEquals("h1", rig.call { rig.hub.openWaitingEdit("s1", item) }.getOrThrow().heldId)
    }

    @Test fun aQueueEntryCanBeRemovedOrTakenOutOnlyWhenTheServerSaysSo() {
        val q = { JSONArray().put(JSONObject().put("id", "q:1").put("text", "and the docs").put("at", 1L).put("clientId", JSONObject.NULL)) }
        connect(session("s1", state = "working") { put("queue", q()); put("queued", 1); put("term", JSONObject().put("id", "t").put("mac", 0).put("phone", 0)) })
        daemon.ok("GET", "/v1/sessions/s1/events", JSONObject().put("events", JSONArray()))
        rig.hub.opened("s1")
        rig.until("history") { !rig.hub.loadingTranscript("s1") }
        val older = rig.hub.transcript("s1").filterIsInstance<Item.User>().single()
        assertNull("an older server can't", older.queueId)
        assertFalse(older.changeable)

        daemon.push(JSONObject().put("t", "session").put("session", session("s1", state = "working") { put("queue", q()); put("queued", 1); put("queueEdit", true); put("term", JSONObject().put("id", "t").put("mac", 0).put("phone", 0)) }))
        rig.until("it says so") { rig.hub.transcript("s1").filterIsInstance<Item.User>().singleOrNull()?.queueId == "q:1" }
        val item = rig.hub.transcript("s1").filterIsInstance<Item.User>().single()
        assertTrue(item.changeable)

        val heldNow = JSONArray().put(JSONObject().put("id", "h9").put("text", "and the docs").put("at", 1L).put("editing", true))
        daemon.ok("POST", "/v1/sessions/s1/queue/q:1/edit", JSONObject().put("session", session("s1", state = "working") { put("held", heldNow); put("queueEdit", true) }).put("heldId", "h9").put("text", "and the docs, whole"))
        val edit = rig.call { rig.hub.openWaitingEdit("s1", item) }.getOrThrow()
        assertEquals("h9", edit.heldId)
        assertEquals("and the docs, whole", edit.text)
        val shown = rig.hub.transcript("s1").filterIsInstance<Item.User>().single()
        assertEquals("h9", shown.heldId)
        assertTrue(shown.editing)

        // Removing one that's still queued; and one Claude took first.
        daemon.ok("DELETE", "/v1/sessions/s1/queue/q:1", JSONObject().put("session", session("s1", state = "working") { put("queueEdit", true) }))
        assertTrue(rig.call { rig.hub.removeWaitingMessage("s1", item) }.isSuccess)
        daemon.on("DELETE", "/v1/sessions/s1/queue/q:1") { refusal(404, "no_queued", "That message isn’t queued any more: Claude has it.") }
        assertTrue(rig.call { rig.hub.removeWaitingMessage("s1", item) }.exceptionOrNull() is WaitingTooLate)
    }

    @Test fun anOfflineMessageBeingEditedDoesNotGoWhenTheServerComesBack() {
        connect(session("s1"))
        daemon.down = true
        daemon.closeSocket()
        rig.until("not linked") { rig.hub.link.state != LinkState.Connected && rig.hub.link.retryAt != null }
        rig.call { rig.hub.send("s1", "when you're back", emptyList(), false) }
        val item = rig.hub.waiting("s1").single()
        val edit = rig.call { rig.hub.openWaitingEdit("s1", item) }.getOrThrow()
        assertTrue(rig.hub.waiting("s1").single().editing)

        daemon.ok("POST", "/v1/sessions/s1/prompt", JSONObject().put("session", session("s1", state = "working")))
        daemon.down = false
        rig.advance(30_100)
        rig.until("linked") { rig.hub.link.state == LinkState.Connected }
        rig.advance(3_000)
        assertEquals("the link is back and it stays", 0, daemon.count("POST", "/v1/sessions/s1/prompt"))
        assertTrue(rig.call { rig.hub.saveWaitingEdit(edit, "when you are back") }.isSuccess)
        rig.until("saved, it goes") { daemon.count("POST", "/v1/sessions/s1/prompt") == 1 }
        assertEquals("when you are back", last("POST", "/v1/sessions/s1/prompt").json.getString("text"))
    }

    // ─── Session settings ───

    @Test fun setModeSaysWhetherItAppliesNextTurn() {
        connect(session("s1"))
        daemon.ok("POST", "/v1/sessions/s1/mode", JSONObject().put("session", session("s1") { put("permissionMode", "plan") }).put("applies", "next_turn"))
        assertTrue(rig.call { rig.hub.setMode("s1", "plan") }.getOrThrow())
        assertEquals("plan", last("POST", "/v1/sessions/s1/mode").json.getString("permissionMode"))
        assertEquals("plan", rig.hub.session("s1")!!.mode)
    }

    @Test fun setEffort() {
        connect(session("s1"))
        daemon.ok("POST", "/v1/sessions/s1/effort", JSONObject().put("session", session("s1") { put("effort", "high") }))
        assertFalse(rig.call { rig.hub.setEffort("s1", "high") }.getOrThrow())
        assertEquals("high", last("POST", "/v1/sessions/s1/effort").json.getString("effort"))
        assertEquals("high", rig.hub.session("s1")!!.effort)
    }

    @Test fun setModelAndTheChipFollowsAtOnce() {
        connect(session("s1") { put("model", "sonnet") })
        daemon.ok("POST", "/v1/sessions/s1/model", JSONObject().put("model", "opus").put("applies", "next_turn"))
        val m = rig.call { rig.hub.setModel("s1", "opus", false) }.getOrThrow()
        assertEquals("opus", m.model)
        assertTrue(m.nextTurn)
        val b = last("POST", "/v1/sessions/s1/model").json
        assertEquals("opus", b.getString("model"))
        assertFalse(b.has("confirmPaid"))
        assertEquals("opus", rig.hub.session("s1")!!.model)
    }

    @Test fun theDefaultModelIsSentAsNull() {
        connect(session("s1") { put("model", "sonnet") })
        daemon.ok("POST", "/v1/sessions/s1/model", JSONObject())
        rig.call { rig.hub.setModel("s1", null, true) }
        val b = last("POST", "/v1/sessions/s1/model").json
        assertTrue(b.isNull("model"))
        assertTrue(b.getBoolean("confirmPaid"))
    }

    // ─── The Home card's menu ───

    @Test fun renameTrimsTheName() {
        connect(session("s1"))
        daemon.ok("POST", "/v1/sessions/s1/rename", JSONObject().put("session", session("s1", title = "Parser fix")))
        assertTrue(rig.call { rig.hub.renameSession("s1", "  Parser fix ") }.isSuccess)
        assertEquals("Parser fix", last("POST", "/v1/sessions/s1/rename").json.getString("name"))
        assertEquals("Parser fix", rig.hub.session("s1")!!.name)
    }

    @Test fun stopRespawnAndDelete() {
        connect(session("s1", state = "working"))
        daemon.ok("POST", "/v1/sessions/s1/stop", JSONObject().put("session", session("s1", state = "ended")))
        assertTrue(rig.call { rig.hub.stopSession("s1") }.isSuccess)
        assertEquals(1, daemon.count("POST", "/v1/sessions/s1/stop"))
        daemon.ok("POST", "/v1/sessions/s1/respawn", JSONObject().put("session", session("s1", state = "starting")))
        assertTrue(rig.call { rig.hub.respawnSession("s1") }.isSuccess)
        assertEquals(1, daemon.count("POST", "/v1/sessions/s1/respawn"))
        daemon.ok("DELETE", "/v1/sessions/s1")
        assertTrue(rig.call { rig.hub.deleteSession("s1") }.isSuccess)
        assertEquals(1, daemon.count("DELETE", "/v1/sessions/s1"))
        // An older server's answer has no session: it's gone there.
        assertTrue(rig.hub.sessions.none { it.id == "s1" })
    }

    @Test fun writingInASessionWhoseFolderHasGoneSaysSo() {
        connect(session("s1", state = "ended"))
        daemon.on("POST", "/v1/sessions/s1/prompt") { json(409, JSONObject().put("error", JSONObject().put("code", "folder_gone").put("message", "The folder it ran in is gone."))) }
        assertEquals("The folder it ran in is gone.", rig.call { rig.hub.send("s1", "carry on", emptyList(), false) }.exceptionOrNull()?.message)
        assertEquals(com.prudnikweb.hypernova.data.Phase.Ended, rig.hub.session("s1")?.phase)
    }

    @Test fun removeLeavesItEndedForHistory() {
        connect(session("s1", state = "idle"))
        daemon.ok("DELETE", "/v1/sessions/s1", JSONObject().put("ok", true).put("session", session("s1", state = "ended")))
        assertTrue(rig.call { rig.hub.deleteSession("s1") }.isSuccess)
        assertEquals(com.prudnikweb.hypernova.data.Phase.Ended, rig.hub.session("s1")?.phase)
    }

    @Test fun deleteOnAnOlderServerSaysItNeedsUpdating() {
        connect(session("s1"))
        daemon.on("DELETE", "/v1/sessions/s1") { json(409, JSONObject().put("error", JSONObject().put("code", "not_agents").put("message", "Only a session in claude agents can be stopped or deleted here."))) }
        val r = rig.call { rig.hub.deleteSession("s1") }
        assertTrue(r.isFailure)
        assertEquals(rig.res.getString(Str.err_delete_older), r.exceptionOrNull()?.message)
        // The card stays: nothing was deleted.
        assertTrue(rig.hub.sessions.any { it.id == "s1" })
    }

    @Test fun deleteRefusedSaysTheServersOwnWords() {
        connect(session("s1"))
        daemon.on("DELETE", "/v1/sessions/s1") { json(409, JSONObject().put("error", JSONObject().put("code", "terminal_open").put("message", "It’s open in a terminal Hypernova doesn’t hold."))) }
        val r = rig.call { rig.hub.deleteSession("s1") }
        assertEquals("It’s open in a terminal Hypernova doesn’t hold.", r.exceptionOrNull()?.message)
        assertTrue(rig.hub.sessions.any { it.id == "s1" })
    }

    @Test fun restartPostsAndTakesTheSessionBack() {
        connect(session("s1"))
        daemon.ok("POST", "/v1/sessions/s1/restart", JSONObject().put("session", session("s1", state = "starting")))
        assertTrue(rig.call { rig.hub.restartSession("s1") }.isSuccess)
        assertEquals(1, daemon.count("POST", "/v1/sessions/s1/restart"))
        assertEquals(1, rig.hub.sessions.count { it.id == "s1" })
        rig.until("it's starting") { rig.hub.session("s1")?.phase == com.prudnikweb.hypernova.data.Phase.Starting }
    }

    @Test fun restartOnAnOlderServerSaysItNeedsUpdating() {
        connect(session("s1"))
        daemon.on("POST", "/v1/sessions/s1/restart") { json(404, JSONObject().put("error", JSONObject().put("code", "not_found").put("message", "Not found"))) }
        assertEquals(rig.res.getString(Str.err_restart_older), rig.call { rig.hub.restartSession("s1") }.exceptionOrNull()?.message)
    }

    @Test fun restartOfASessionGoneSaysSoNotUpdate() {
        connect(session("s1"))
        daemon.on("POST", "/v1/sessions/s1/restart") { json(404, JSONObject().put("error", JSONObject().put("code", "no_session").put("message", "That session no longer exists."))) }
        assertEquals("That session no longer exists.", rig.call { rig.hub.restartSession("s1") }.exceptionOrNull()?.message)
    }

    @Test fun respawnOutdatedJobsUsesTheSlowRoute() {
        connect(session("s1"), session("s2"))
        daemon.ok("POST", "/v1/jobs/respawn", JSONObject().put("results", JSONArray()))
        assertTrue(rig.call { rig.hub.respawnOutdated(listOf("s1", "s2")) }.isSuccess)
        assertEquals(2, last("POST", "/v1/jobs/respawn").json.getJSONArray("ids").length())
    }

    @Test fun interruptPosts() {
        connect(session("s1", state = "working"))
        daemon.ok("POST", "/v1/sessions/s1/interrupt")
        rig.hub.interrupt("s1")
        rig.until("the interrupt") { daemon.count("POST", "/v1/sessions/s1/interrupt") == 1 }
    }

    // ─── The terminal's menu ───

    @Test fun answerAMenuChoiceAndCancelOne() {
        connect(session("s1", state = "needs_you"))
        daemon.ok("POST", "/v1/sessions/s1/menu")
        rig.call { rig.hub.answerMenu("s1", "m1", 2, alt = true) }
        var b = last("POST", "/v1/sessions/s1/menu").json
        assertEquals("m1", b.getString("menuId"))
        assertEquals(2, b.getInt("choice"))
        assertTrue(b.getBoolean("alt"))
        rig.call { rig.hub.answerMenu("s1", "m1", null, alt = false) }
        b = last("POST", "/v1/sessions/s1/menu").json
        assertTrue(b.getBoolean("cancel"))
        assertFalse(b.has("choice"))
    }

    @Test fun menuStepsMoveOrJump() {
        connect(session("s1", state = "needs_you"))
        daemon.ok("POST", "/v1/sessions/s1/menu")
        rig.call { rig.hub.menuStep("s1", "m1", move = -3, step = null) }
        assertEquals(-1, last("POST", "/v1/sessions/s1/menu").json.getInt("move"))
        rig.call { rig.hub.menuStep("s1", "m1", move = null, step = 2) }
        val b = last("POST", "/v1/sessions/s1/menu").json
        assertEquals(2, b.getInt("step"))
        assertFalse(b.has("move"))
    }

    // ─── Reads ───

    @Test fun historySearchAsksTheServerAndAnOlderServerMeansFilterOnThePhone() {
        connect(session("s1"))
        daemon.ok("GET", "/v1/history/search", JSONObject().put("hits", JSONArray()))
        assertTrue(rig.call { rig.hub.searchHistory("  parser bug ") }.isSuccess)
        assertEquals("q=parser+bug", last("GET", "/v1/history/search").query)
        daemon.on("GET", "/v1/history/search") { FakeDaemon.refusal(404, "not_found", null) }
        assertNull(rig.call { rig.hub.searchHistory("x") }.getOrThrow())
    }

    @Test fun permissionRulesAreTheSessionsOwn() {
        connect(session("s1"))
        daemon.ok("GET", "/v1/sessions/s1/permissions", JSONObject().put("allow", JSONArray().put("Bash(ls)")).put("deny", JSONArray()).put("ask", JSONArray()))
        assertTrue(rig.call { rig.hub.permissionRules("s1") }.isSuccess)
        assertEquals(1, daemon.count("GET", "/v1/sessions/s1/permissions"))
    }

    @Test fun theSideQuestionsAnswerIsRead() {
        connect(session("s1"))
        daemon.ok("GET", "/v1/sessions/s1/btw", JSONObject())
        assertTrue(rig.call { rig.hub.aside("s1") }.isSuccess)
        assertEquals(1, daemon.count("GET", "/v1/sessions/s1/btw"))
    }

    @Test fun anOlderServerWithoutModelsListsNone() {
        connect(session("s1"))
        assertEquals(emptyList<Any>(), rig.call { rig.hub.models() }.getOrThrow())
    }

    @Test fun claudeAgentsSettingIsPostedAndApplied() {
        connect(session("s1"))
        daemon.ok("POST", "/v1/claude", JSONObject().put("claude", JSONObject().put("available", true).put("agents", true)))
        rig.call { rig.hub.setClaudeAgents(true) }
        assertTrue(last("POST", "/v1/claude").json.getBoolean("agents"))
        assertEquals(true, rig.hub.claude?.agents)
    }

    @Test fun loginContinueSettingIsPostedAndApplied() {
        connect(session("s1"))
        assertEquals(null, rig.hub.claude?.loginContinue)
        daemon.ok("POST", "/v1/claude", JSONObject().put("claude", JSONObject().put("available", true).put("loginContinue", false)))
        rig.call { rig.hub.setLoginContinue(false) }
        assertFalse(last("POST", "/v1/claude").json.getBoolean("loginContinue"))
        assertEquals(false, rig.hub.claude?.loginContinue)
    }

    /** Each agent's goes to its own route, and comes back as the server has it. */
    @Test fun aStartModeIsPostedForItsAgentAndApplied() {
        connect(session("s1"))
        daemon.ok("POST", "/v1/claude", JSONObject().put("claude", JSONObject().put("available", true).put("startMode", "plan")))
        daemon.ok("POST", "/v1/codex", JSONObject().put("codex", JSONObject().put("available", true).put("enabled", true).put("connected", true).put("startMode", "workspace-write")))
        assertTrue(rig.call { rig.hub.setStartMode(AgentKind.CLAUDE, "plan") }.isSuccess)
        assertEquals("plan", last("POST", "/v1/claude").json.getString("startMode"))
        assertEquals("plan", rig.hub.claude?.startMode)
        assertTrue(rig.call { rig.hub.setStartMode(AgentKind.CODEX, "workspace-write") }.isSuccess)
        assertEquals("workspace-write", last("POST", "/v1/codex").json.getString("startMode"))
        assertEquals("workspace-write", rig.hub.codex?.startMode)
    }

    // The phone-wide "New sessions start in" of before moves to Claude Code on each server, once.
    private fun oldSetting(mode: String, pairedAtFirstStart: List<String> = listOf(rig.serverId)) {
        rig.storage.prefs("hypernova").edit().putString("newSession.startMode", mode).apply()
        NewSessionMemory.noteStartModeServers(rig.storage, pairedAtFirstStart)
    }
    private fun serverKeeps(mode: String?) {
        daemon.snapshot = snapshot("z2") {
            put("claude", JSONObject().put("available", true).apply { if (mode != null) put("startMode", mode) })
            put("codex", JSONObject().put("available", true).put("enabled", true).put("connected", true).apply { if (mode != null) put("startMode", "read-only") })
        }
        daemon.ok("POST", "/v1/claude", JSONObject().put("claude", JSONObject().put("available", true).put("startMode", "plan")))
    }

    private fun moves() = daemon.calls("POST", "/v1/claude")
    /** The socket drops and the link comes back: the next snapshot. */
    private fun nextSnapshot() {
        val n = daemon.count("GET", "/v1/snapshot")
        daemon.closeSocket()
        rig.until("the next snapshot") { daemon.count("GET", "/v1/snapshot") > n && rig.hub.link.state == LinkState.Connected }
    }
    /** Nothing more on its way: the hub's handled what it had, and a moment for a request it may have sent. */
    private fun settled() {
        rig.sync()
        Thread.sleep(100)
        rig.pump()
    }

    @Test fun theOldSettingGoesToClaudeOnceAndNeverToCodex() {
        oldSetting("plan")
        serverKeeps("default")
        rig.connect()
        rig.until("it moved") { rig.hub.claude?.startMode == "plan" }
        assertEquals("plan", moves().single().json.getString("moveStartMode"))
        assertEquals("read-only", rig.hub.codex?.startMode)
        assertTrue("Codex hears nothing", daemon.calls("POST", "/v1/codex").isEmpty())
        rig.until("it's marked") { NewSessionMemory.startModeToMove(rig.storage, rig.serverId) == null }
        assertFalse("nothing left to move: the old value's gone", rig.storage.prefs("hypernova").contains("newSession.startMode"))
        // Another connection, a later snapshot: not again.
        nextSnapshot()
        settled()
        assertEquals(1, moves().size)
    }

    @Test fun anOlderServerTakesNothingUntilItsUpdated() {
        oldSetting("plan")
        serverKeeps(null)
        rig.connect()
        settled()
        assertNull(rig.hub.claude?.startMode)
        assertTrue(moves().isEmpty())
        // Updated: it takes it then.
        serverKeeps("default")
        nextSnapshot()
        rig.until("it moved") { rig.hub.claude?.startMode == "plan" }
        assertEquals("plan", moves().single().json.getString("moveStartMode"))
    }

    /** Paired after the phone's first start with this: nothing moves, and the server's Ask stands. */
    @Test fun aServerPairedAfterTheFirstStartGetsNoMove() {
        oldSetting("auto", pairedAtFirstStart = listOf("another"))
        serverKeeps("default")
        rig.connect()
        settled()
        assertTrue(moves().isEmpty())
        assertEquals("default", rig.hub.claude?.startMode)
        assertNull(rig.hub.startModeOwed)
        assertEquals("the other's still owed", "auto", NewSessionMemory.startModeToMove(rig.storage, "another"))
    }

    /** Older, and paired later: it isn't owed the old value either, so nothing here opens on it. */
    @Test fun anOlderServerPairedLaterIsntOwedIt() {
        oldSetting("auto", pairedAtFirstStart = listOf("another"))
        serverKeeps(null)
        rig.connect()
        settled()
        assertEquals("default", NewSessionMemory.startsIn(AgentKind.CLAUDE, rig.hub, NewSessionMemory.load(rig.storage)))
    }

    /** Older, and paired at the first start: it opens on the old value until it's updated. */
    @Test fun anOlderServerPairedAtTheFirstStartOpensOnIt() {
        oldSetting("auto")
        serverKeeps(null)
        rig.connect()
        settled()
        assertEquals("auto", NewSessionMemory.startsIn(AgentKind.CLAUDE, rig.hub, NewSessionMemory.load(rig.storage)))
    }

    @Test fun anUnusableOldSettingMovesNothing() {
        for (bad in listOf("bypassPermissions", "workspace-write", "danger-full-access", "default")) {
            oldSetting(bad)
            assertNull(bad, NewSessionMemory.startModeToMove(rig.storage, rig.serverId))
        }
        serverKeeps("default")
        rig.connect()
        settled()
        assertTrue(moves().isEmpty())
    }

    /** A move that failed tries again with the next snapshot: it isn't marked done. */
    @Test fun aMoveThatFailedTriesAgain() {
        oldSetting("auto")
        serverKeeps("default")
        daemon.on("POST", "/v1/claude") { json(500, JSONObject().put("error", JSONObject().put("code", "boom").put("message", "no"))) }
        rig.connect()
        rig.until("it tried") { moves().size == 1 }
        settled()
        assertEquals("auto", NewSessionMemory.startModeToMove(rig.storage, rig.serverId))
        daemon.ok("POST", "/v1/claude", JSONObject().put("claude", JSONObject().put("available", true).put("startMode", "auto")))
        nextSnapshot()
        rig.until("it moved") { rig.hub.claude?.startMode == "auto" }
        rig.until("it's marked") { NewSessionMemory.startModeToMove(rig.storage, rig.serverId) == null }
    }
}
