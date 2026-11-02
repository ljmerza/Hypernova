package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Attachment
import com.prudnikweb.hypernova.data.BlockedWire
import com.prudnikweb.hypernova.data.FileFailure
import com.prudnikweb.hypernova.data.FolderPick
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.NewSessionRequest
import com.prudnikweb.hypernova.data.SendStatus
import com.prudnikweb.hypernova.data.Upload
import com.prudnikweb.hypernova.data.Wire
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hub's session routes against the daemon's own answers: each route answered with its contract
 * fixture (protocol/fixtures, what daemon/test/contract.test.ts gets from the real daemon), and its
 * refusals in the daemon's words. New session and its refusals (FEATURES N1, N3), the folder browser
 * (N2), Codex's models (N6), attachments (Q7), load earlier (C4), blocked calls (S5), the "/" list
 * (S10), going on with a session (Y4), carrying on after a limit (C13), another provider (S12), the
 * live terminal (C11), Stop (Q8), a waiting prompt as a new session (Q4), a Codex approval (A9).
 */
@RunWith(RigRunner::class)
class SessionRoutesTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    @After fun tearDown() = rig.close()

    private companion object {
        const val STARTED = "c0ffee00-0000-4000-8000-00000000f001"
        const val CWD = "/home/me/Projects/app-parser-fix"
        /** The session the auto-continue and provider fixtures name (the daemon's normalised id). */
        const val LIMITED = "00000000-0000-4000-8000-000000000001"
    }

    /** protocol/fixtures/[name].json, on the test classpath (app/build.gradle.kts). */
    private fun fixture(name: String): JSONObject =
        JSONObject(javaClass.classLoader!!.getResource("fixtures/$name.json")!!.readText())

    /** A refusal fixture's body as the daemon sent it (its `status` is the fixture's own note). */
    private fun refusalBody(name: String): Pair<Int, JSONObject> = fixture(name).let { it.getInt("status") to JSONObject().put("error", it.getJSONObject("error")) }

    /** Connected to a Mac (the app's plain words, so a test can name them). */
    private fun connect(vararg sessions: JSONObject, approvals: List<JSONObject> = emptyList()) {
        daemon.snapshot = snapshot(sessions = sessions.toList().ifEmpty { listOf(session("s1")) }, approvals = approvals, platform = "darwin")
        rig.connect()
    }

    private fun words(id: Str) = rig.res.getString(id)
    private fun last(method: String, path: String): FakeDaemon.Req = daemon.calls(method, path).last()
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun req(projectPath: String? = CWD, prompt: String = "Tidy the README", model: String? = null, mode: String = "default", effort: String? = null, providerId: String? = null, newProject: String? = null, files: List<Attachment> = emptyList(), agent: String = AgentKind.CLAUDE) =
        NewSessionRequest(projectPath = projectPath, newProject = newProject, prompt = prompt, providerId = providerId, model = model, permissionMode = mode, files = files, agent = agent, effort = effort)

    // ─── New session (POST /v1/sessions) ───

    @Test fun startSendsTheSheetsChoicesAndTheNewSessionIsThereAtOnce() {
        connect()
        daemon.ok("POST", "/v1/sessions", fixture("rest-sessions-start"))
        val r = rig.call { rig.hub.start(req(prompt = "  Tidy the README  ", model = "sonnet", mode = "acceptEdits", effort = "high")) }
        assertEquals(STARTED, r.getOrThrow())
        val b = last("POST", "/v1/sessions").json
        // The body the daemon's contract test sends for this fixture, key for key.
        assertEquals(setOf("prompt", "projectPath", "permissionMode", "model", "effort"), b.keys().asSequence().toSet())
        assertEquals("Tidy the README", b.getString("prompt"))
        assertEquals(CWD, b.getString("projectPath"))
        assertEquals("acceptEdits", b.getString("permissionMode"))
        assertEquals("sonnet", b.getString("model"))
        assertEquals("high", b.getString("effort"))
        // On Home before the socket says so.
        assertEquals("Tidy the README", rig.hub.session(STARTED)?.name)
    }

    @Test fun aNewProjectGoesByItsNameAndTheProviderAndFilesGoWithIt() {
        connect()
        daemon.ok("POST", "/v1/sessions", fixture("rest-sessions-start"))
        val file = Attachment("/tmp/hn/support/attachments/0123456789ab/notes.txt", "notes.txt", "text/plain", 23)
        rig.call { rig.hub.start(req(projectPath = null, newProject = "  sketchpad ", providerId = "or-1", files = listOf(file))) }.getOrThrow()
        val b = last("POST", "/v1/sessions").json
        assertFalse(b.has("projectPath"))
        assertEquals("sketchpad", b.getJSONObject("newProject").getString("name"))
        assertTrue(b.getJSONObject("newProject").getBoolean("git"))
        assertEquals("or-1", b.getString("providerId"))
        assertEquals(listOf(file.path), b.getJSONArray("attachments").strings())
        assertFalse("no effort: Claude Code's own default", b.has("effort"))
        assertFalse(b.has("agent"))
    }

    @Test fun aCodexSessionNamesItsAgentAndNoProvider() {
        connect()
        daemon.ok("POST", "/v1/sessions", fixture("rest-sessions-start"))
        rig.call { rig.hub.start(req(model = "gpt-5.5", mode = "read-only", effort = "high", providerId = "or-1", agent = AgentKind.CODEX)) }.getOrThrow()
        val b = last("POST", "/v1/sessions").json
        assertEquals("codex", b.getString("agent"))
        assertEquals("gpt-5.5", b.getString("model"))
        assertEquals("high", b.getString("effort"))
        assertEquals("read-only", b.getString("permissionMode"))
        assertFalse("Codex's providers aren't Claude Code's", b.has("providerId"))
    }

    @Test fun theRefusalsAreTheServersOwnWordsAndStartNothing() {
        connect()
        val (status, body) = refusalBody("rest-sessions-bad-project")
        val refusals = listOf(
            Triple(status, body, "That project folder doesn’t exist."),
            Triple(409, refusal(409, "mac_privacy", "macOS hasn’t let Hypernova open Documents.").getBody()!!.readUtf8().let(::JSONObject), "macOS hasn’t let Hypernova open Documents."),
            Triple(409, refusal(409, "sdk_missing", "Claude Code’s SDK isn’t here yet.").getBody()!!.readUtf8().let(::JSONObject), "Claude Code’s SDK isn’t here yet."),
            Triple(503, refusal(503, "codex_unavailable", "Codex isn’t answering on this Mac.").getBody()!!.readUtf8().let(::JSONObject), "Codex isn’t answering on this Mac."),
        )
        for ((s, b, said) in refusals) {
            daemon.on("POST", "/v1/sessions") { json(s, b) }
            val r = rig.call { rig.hub.start(req()) }
            assertEquals(said, r.exceptionOrNull()?.message)
        }
        // An answer without a session is the app's own words.
        daemon.ok("POST", "/v1/sessions", JSONObject())
        assertEquals(words(Str.err_start), rig.call { rig.hub.start(req()) }.exceptionOrNull()?.message)
        assertEquals(listOf("s1"), rig.hub.sessions.map { it.id })
        assertEquals(LinkState.Connected, rig.hub.link.state)
    }

    @Test fun aWaitingPromptWhoseSessionIsGoneStartsANewSessionInItsFolder() {
        connect(session("s1", more = { put("permissionMode", "acceptEdits") }))
        daemon.down = true
        daemon.closeSocket()
        rig.until("not linked") { rig.hub.link.state != LinkState.Connected && rig.hub.link.retryAt != null }
        rig.call { rig.hub.send("s1", "still there?", emptyList(), false) }
        daemon.on("POST", "/v1/sessions/s1/prompt") { refusal(404, "no_session", "No such session") }
        // The first try at a new session fails: it stays where it was, to try again.
        daemon.on("POST", "/v1/sessions") { refusal(409, "sdk_missing", "Claude Code’s SDK isn’t here yet.") }
        daemon.down = false
        rig.advance(30_100)
        rig.until("the flush") { rig.hub.orphanedPrompts().isNotEmpty() }
        val w = rig.hub.orphanedPrompts().single()
        assertEquals(SendStatus.SessionGone, w.status)
        assertEquals("Claude Code’s SDK isn’t here yet.", rig.call { rig.hub.startFromWaiting(w.waitingId!!) }.exceptionOrNull()?.message)
        assertEquals(1, rig.hub.orphanedPrompts().size)
        daemon.ok("POST", "/v1/sessions", fixture("rest-sessions-start"))
        assertEquals(STARTED, rig.call { rig.hub.startFromWaiting(w.waitingId!!) }.getOrThrow())
        val b = last("POST", "/v1/sessions").json
        assertEquals("/home/me/proj-s1", b.getString("projectPath"))
        assertEquals("still there?", b.getString("prompt"))
        assertTrue(rig.hub.orphanedPrompts().isEmpty())
        // Gone once sent: there's nothing to start again.
        assertTrue(rig.call { rig.hub.startFromWaiting(w.waitingId!!) }.isFailure)
    }

    // ─── The folder browser (GET /v1/folders) and Codex's models ───

    @Test fun theFolderBrowserAsksForAFolderAndItsHiddenOnes() {
        connect()
        daemon.ok("GET", "/v1/folders", fixture("rest-folders"))
        val f = rig.call { rig.hub.folders("~/Projects", hidden = true) }.getOrThrow()
        assertEquals(FolderPick.wire(fixture("rest-folders")), f)
        assertEquals("path=%7E%2FProjects&hidden=1", last("GET", "/v1/folders").query)
        rig.call { rig.hub.folders(null) }.getOrThrow()
        assertEquals("", last("GET", "/v1/folders").query)
        daemon.on("GET", "/v1/folders") { refusal(403, "outside_projects", "That folder is outside your projects.") }
        val e = rig.call { rig.hub.folders("/etc") }.exceptionOrNull() as FileFailure
        assertEquals("outside_projects", e.code)
        assertEquals(403, e.status)
        assertEquals("That folder is outside your projects.", e.message)
    }

    @Test fun codexsModelsAreAskedForOnceInAWhile() {
        connect()
        val models = JSONObject().put("models", JSONArray().put(JSONObject().put("value", "gpt-5.5").put("name", "GPT-5.5").put("efforts", JSONArray(listOf("low", "high"))).put("defaultEffort", "high").put("isDefault", true)))
        daemon.ok("GET", "/v1/codex/models", models)
        val list = rig.call { rig.hub.codexModels() }.getOrThrow()
        assertEquals(listOf("gpt-5.5"), list.map { it.value })
        assertEquals(listOf("low", "high"), list.single().efforts)
        assertTrue(list.single().isDefault)
        rig.call { rig.hub.codexModels() }.getOrThrow()
        assertEquals("kept, not asked again", 1, daemon.count("GET", "/v1/codex/models"))
    }

    @Test fun codexsModelsWhileItIsOffSayTheServersWords() {
        connect()
        daemon.on("GET", "/v1/codex/models") { refusal(409, "codex_off", "Codex is off on this Mac.") }
        assertEquals("Codex is off on this Mac.", rig.call { rig.hub.codexModels() }.exceptionOrNull()?.message)
    }

    // ─── Attachments (POST /v1/attachments, then the prompt) ───

    @Test fun anUploadStreamsTheFileUnderItsName() {
        connect()
        daemon.ok("POST", "/v1/attachments", fixture("rest-attachment"))
        val bytes = "Trim before splitting.\n".toByteArray()
        val a = rig.call { rig.hub.upload(Upload("notes \"draft\".txt", "text/plain", bytes.size.toLong()) { it.write(bytes) }) }.getOrThrow()
        assertEquals(Wire.attachment(fixture("rest-attachment").getJSONObject("attachment")), a)
        val r = last("POST", "/v1/attachments")
        assertEquals("name=notes+%22draft%22.txt", r.query)
        assertEquals("Trim before splitting.\n", r.body)
    }

    @Test fun anUploadTheServerRefusesSaysWhy() {
        connect()
        daemon.on("POST", "/v1/attachments") { refusal(413, "too_large", "That file is too big to attach (30 MB at most).") }
        assertEquals("That file is too big to attach (30 MB at most).", rig.call { rig.hub.upload(Upload("big.bin", "application/octet-stream", 3) { it.write(byteArrayOf(1, 2, 3)) }) }.exceptionOrNull()?.message)
        daemon.ok("POST", "/v1/attachments", JSONObject())
        assertEquals(words(Str.err_upload), rig.call { rig.hub.upload(Upload("a.txt", "text/plain", 1) { it.write(1) }) }.exceptionOrNull()?.message)
    }

    @Test fun aPromptCarriesItsFilesPathsAndAGoneFileIsSaid() {
        connect(session("sess-prompt"))
        daemon.ok("POST", "/v1/sessions/sess-prompt/prompt", fixture("rest-prompt"))
        val file = Wire.attachment(fixture("rest-attachment").getJSONObject("attachment"))!!
        rig.call { rig.hub.send("sess-prompt", "Read my notes", listOf(file), false) }.getOrThrow()
        val b = last("POST", "/v1/sessions/sess-prompt/prompt").json
        assertEquals("Read my notes", b.getString("text"))
        assertEquals(listOf(file.path), b.getJSONArray("attachments").strings())
        val (status, body) = refusalBody("rest-prompt-attachment-gone")
        daemon.on("POST", "/v1/sessions/sess-prompt/prompt") { json(status, body) }
        assertEquals(body.getJSONObject("error").getString("message"), rig.call { rig.hub.send("sess-prompt", "Again", listOf(file), false) }.exceptionOrNull()?.message)
    }

    @Test fun aWaitingPromptWhoseFilesAreGoneGoesWithoutThem() {
        connect(session("s1"))
        daemon.down = true
        daemon.closeSocket()
        rig.until("not linked") { rig.hub.link.state != LinkState.Connected && rig.hub.link.retryAt != null }
        val file = Attachment("/tmp/hn/support/attachments/0123456789ab/gone.txt", "gone.txt", "text/plain", 3)
        rig.call { rig.hub.send("s1", "with my notes", listOf(file), false) }
        val (status, body) = refusalBody("rest-prompt-attachment-gone")
        daemon.on("POST", "/v1/sessions/s1/prompt") { r ->
            if (r.json.has("attachments")) json(status, body) else json(200, JSONObject().put("session", session("s1", state = "working")))
        }
        daemon.down = false
        rig.advance(30_100)
        rig.until("sent without the file") { daemon.count("POST", "/v1/sessions/s1/prompt") == 2 && rig.hub.waitingCount("s1") == 0 }
        val (first, second) = daemon.calls("POST", "/v1/sessions/s1/prompt").map { it.json }
        assertEquals(listOf(file.path), first.getJSONArray("attachments").strings())
        assertFalse(second.has("attachments"))
        assertEquals("with my notes", second.getString("text"))
        assertEquals("the same send, under its name", first.getString("clientId"), second.getString("clientId"))
        assertTrue(rig.toasts.toString(), words(Str.waiting_files_gone) in rig.toasts)
    }

    // ─── Load earlier (GET …/events with a bigger limit) ───

    /** A Mac whose session s1 has [n] prompts ("prompt 1" oldest), answering the last `limit` of them. */
    private fun history(n: Int, ts: (Int) -> Long = { it.toLong() }, id: (Int) -> String = { "e$it" }) = daemon.on("GET", "/v1/sessions/s1/events") { r ->
        val limit = Regex("limit=(\\d+)").find(r.query)!!.groupValues[1].toInt()
        val all = (1..n).map { JSONObject().put("id", id(it)).put("kind", "user").put("ts", ts(it)).put("text", "prompt $it") }
        json(200, JSONObject().put("events", JSONArray(all.takeLast(limit))))
    }

    private fun prompts() = rig.hub.transcript("s1").filterIsInstance<Item.User>().map { it.text }

    @Test fun aLongHistoryOpensOnItsLatestAndLoadEarlierPutsTheRestInFront() {
        connect()
        history(450)
        rig.hub.opened("s1")
        rig.until("the history") { prompts().isNotEmpty() }
        assertEquals("limit=200&output=8192", last("GET", "/v1/sessions/s1/events").query)
        assertEquals(200, prompts().size)
        assertEquals("prompt 251", prompts().first())
        assertTrue(rig.hub.hasEarlier("s1"))

        rig.hub.loadEarlier("s1")
        assertTrue(rig.hub.loadingEarlier("s1"))
        // A second tap meanwhile asks for nothing more.
        rig.hub.loadEarlier("s1")
        rig.until("the earlier part") { !rig.hub.loadingEarlier("s1") }
        assertEquals(2, daemon.count("GET", "/v1/sessions/s1/events"))
        assertEquals("limit=400&output=8192", last("GET", "/v1/sessions/s1/events").query)
        assertEquals(400, prompts().size)
        assertEquals(listOf("prompt 51", "prompt 52"), prompts().take(2))
        assertEquals("prompt 450", prompts().last())
        assertTrue("400 of 400 asked for: there may be more", rig.hub.hasEarlier("s1"))

        rig.hub.loadEarlier("s1")
        rig.until("the start") { !rig.hub.loadingEarlier("s1") && daemon.count("GET", "/v1/sessions/s1/events") == 3 }
        assertEquals(450, prompts().size)
        assertEquals("prompt 1", prompts().first())
        assertFalse(rig.hub.hasEarlier("s1"))
    }

    @Test fun aHistoryTheMacRewroteMeanwhileIsTakenWholeWithWhatCameSince() {
        connect()
        history(300)
        rig.hub.opened("s1")
        rig.until("the history") { prompts().size == 200 }
        // A live prompt arrives, then the Mac's history is rewritten (a compact): new ids throughout,
        // newer than what the phone had, but not newer than the live prompt it hasn't logged yet.
        rig.push(msg("event") { put("sessionId", "s1").put("event", JSONObject().put("id", "live").put("kind", "user").put("ts", 1_000L).put("text", "a live one")) }) { "a live one" in prompts() }
        history(120, ts = { 500L + it }) { "r$it" }
        rig.hub.loadEarlier("s1")
        rig.until("taken whole") { !rig.hub.loadingEarlier("s1") && daemon.count("GET", "/v1/sessions/s1/events") == 2 }
        assertEquals((1..120).map { "prompt $it" } + "a live one", prompts())
        assertFalse(rig.hub.hasEarlier("s1"))
    }

    @Test fun aShortHistoryHasNothingEarlierAndAFailedLoadEarlierIsSaid() {
        connect(session(LIMITED_ID))
        daemon.ok("GET", "/v1/sessions/$LIMITED_ID/events", fixture("rest-session-events-limit"))
        rig.hub.opened(LIMITED_ID)
        rig.until("the history") { rig.hub.transcript(LIMITED_ID).isNotEmpty() }
        assertFalse(rig.hub.hasEarlier(LIMITED_ID))
        // Asked for anyway (an older screen): a failure is a toast, and what's shown stays.
        val shown = rig.hub.transcript(LIMITED_ID)
        daemon.on("GET", "/v1/sessions/$LIMITED_ID/events") { refusal(500, "boom", null) }
        rig.hub.loadEarlier(LIMITED_ID)
        rig.until("the toast") { rig.toasts.isNotEmpty() && !rig.hub.loadingEarlier(LIMITED_ID) }
        assertEquals(words(Str.err_history), rig.toasts.last())
        assertEquals(shown, rig.hub.transcript(LIMITED_ID))
    }

    // ─── Blocked calls (GET …/blocked, Allow & retry, Dismiss) ───

    @Test fun blockedCallsAreListedRetriedAndDismissed() {
        connect(session("sess-blocked"))
        daemon.ok("GET", "/v1/sessions/sess-blocked/blocked", fixture("rest-blocked"))
        val list = rig.call { rig.hub.blocked("sess-blocked") }.getOrThrow()
        assertEquals(BlockedWire.list(fixture("rest-blocked")), list)
        assertEquals(setOf("Bash", "WebFetch"), list.map { it.tool }.toSet())
        val bash = list.first { it.tool == "Bash" }
        val web = list.first { it.tool == "WebFetch" }

        daemon.ok("POST", "/v1/sessions/sess-blocked/blocked/${bash.id}/retry", fixture("rest-blocked-retry"))
        // (Each fixture numbers its random ids afresh: the calls are told apart by their tools here.)
        assertEquals(listOf("WebFetch"), rig.call { rig.hub.retryBlocked("sess-blocked", bash.id) }.getOrThrow().map { it.tool })
        // The session as the retry left it: in its shared terminal now.
        assertNotNull(rig.hub.session("sess-blocked")?.term)

        daemon.ok("DELETE", "/v1/sessions/sess-blocked/blocked/${web.id}", fixture("rest-blocked-dismiss"))
        assertEquals(emptyList<Any>(), rig.call { rig.hub.dismissBlocked("sess-blocked", web.id) }.getOrThrow())
        assertEquals(1, daemon.count("DELETE", "/v1/sessions/sess-blocked/blocked/${web.id}"))
    }

    @Test fun aRetryTheSessionCantTakeSaysWhy() {
        connect(session("sess-blocked"))
        daemon.on("POST", "/v1/sessions/sess-blocked/blocked/b1/retry") { refusal(409, "switched", "The conversation went on with Codex.") { put("to", "s2") } }
        assertEquals("The conversation went on with Codex.", rig.call { rig.hub.retryBlocked("sess-blocked", "b1") }.exceptionOrNull()?.message)
        daemon.on("DELETE", "/v1/sessions/sess-blocked/blocked/b1") { refusal(404, "no_blocked", "That call isn’t blocked any more.") }
        assertEquals("That call isn’t blocked any more.", rig.call { rig.hub.dismissBlocked("sess-blocked", "b1") }.exceptionOrNull()?.message)
    }

    // ─── The "/" list (GET /v1/commands) ───

    @Test fun theSlashListIsTheProjectsOwnAndKeptAFewMinutes() {
        connect()
        daemon.ok("GET", "/v1/commands", fixture("rest-commands"))
        val list = rig.call { rig.hub.commands(CWD) }.getOrThrow()
        assertEquals(Wire.commands(fixture("rest-commands")), list)
        assertEquals("project=%2Fhome%2Fme%2FProjects%2Fapp-parser-fix", last("GET", "/v1/commands").query)
        rig.call { rig.hub.commands(CWD) }.getOrThrow()
        assertEquals("kept for the same folder", 1, daemon.count("GET", "/v1/commands"))
        rig.call { rig.hub.commands(null) }.getOrThrow()
        assertEquals("", last("GET", "/v1/commands").query)
        daemon.on("GET", "/v1/commands") { refusal(409, "sdk_missing", "Claude Code’s SDK isn’t here yet.") }
        assertEquals("Claude Code’s SDK isn’t here yet.", rig.call { rig.hub.commands("/home/me/Projects/demo") }.exceptionOrNull()?.message)
    }

    // ─── Going on: after the away stop, after a limit, on another provider ───

    @Test fun goingOnWithASessionPostsContinueAndHidesItsContinueAtOnce() {
        connect(session("sess-back") { put("stoppedForAway", true) })
        // The Mac's answer still says stopped: the phone hides Continue anyway until the update comes.
        daemon.ok("POST", "/v1/sessions/sess-back/continue", fixture("rest-session-continue").also { it.getJSONObject("session").put("stoppedForAway", true) })
        assertEquals("sess-back", rig.call { rig.hub.resume("sess-back") }.getOrThrow())
        assertEquals(JSONObject().toString(), last("POST", "/v1/sessions/sess-back/continue").json.toString())
        assertFalse(rig.hub.session("sess-back")!!.stoppedForAway)
        // A terminal session still open goes on in a fork: the new one.
        daemon.ok("POST", "/v1/sessions/sess-back/continue", JSONObject().put("session", session("fork-1")).put("forked", true))
        assertEquals("fork-1", rig.call { rig.hub.resume("sess-back") }.getOrThrow())
        assertNotNull(rig.hub.session("fork-1"))
        daemon.on("POST", "/v1/sessions/sess-back/continue") { refusal(409, "needs_answer", "Claude is waiting for your answer above.") }
        assertEquals("Claude is waiting for your answer above.", rig.call { rig.hub.resume("sess-back") }.exceptionOrNull()?.message)
    }

    @Test fun afterALimitContinueSaysWhyAndCarryingOnByItselfCanBeTurnedOff() {
        connect(session(LIMITED))
        daemon.ok("POST", "/v1/sessions/$LIMITED/continue", JSONObject().put("session", session(LIMITED, state = "working")))
        assertEquals(LIMITED, rig.call { rig.hub.continueAfterLimit(LIMITED) }.getOrThrow())
        assertEquals("limit", last("POST", "/v1/sessions/$LIMITED/continue").json.getString("reason"))

        daemon.ok("POST", "/v1/sessions/$LIMITED/auto-continue", fixture("rest-session-auto-continue"))
        rig.call { rig.hub.autoContinue(LIMITED, false) }.getOrThrow()
        assertEquals(false, last("POST", "/v1/sessions/$LIMITED/auto-continue").json.getBoolean("on"))
        val resume = rig.hub.session(LIMITED)!!.blocked!!.resume!!
        assertTrue("off, as the Mac's answer has it", resume.off)
        assertTrue(resume.canArm)
        daemon.on("POST", "/v1/sessions/$LIMITED/auto-continue") { refusal(409, "not_available", "This session can’t carry on by itself from here.") }
        assertEquals("This session can’t carry on by itself from here.", rig.call { rig.hub.autoContinue(LIMITED, true) }.exceptionOrNull()?.message)
        assertEquals(true, last("POST", "/v1/sessions/$LIMITED/auto-continue").json.getBoolean("on"))
    }

    @Test fun aSessionMovesToAnotherProviderHereOrInAFork() {
        connect(session(LIMITED) { put("providerId", "openrouter-1") })
        daemon.ok("POST", "/v1/sessions/$LIMITED/provider", fixture("rest-session-provider"))
        assertEquals(LIMITED, rig.call { rig.hub.switchProvider(LIMITED, "anthropic") }.getOrThrow())
        assertEquals("anthropic", last("POST", "/v1/sessions/$LIMITED/provider").json.getString("providerId"))
        assertEquals("anthropic", rig.hub.session(LIMITED)!!.providerId)
        daemon.ok("POST", "/v1/sessions/$LIMITED/provider", JSONObject().put("session", session("fork-2")).put("forked", true))
        assertEquals("fork-2", rig.call { rig.hub.switchProvider(LIMITED, "anthropic") }.getOrThrow())
        daemon.on("POST", "/v1/sessions/$LIMITED/provider") { refusal(409, "same_provider", "It's on that provider already.") }
        assertEquals("It's on that provider already.", rig.call { rig.hub.switchProvider(LIMITED, "anthropic") }.exceptionOrNull()?.message)
    }

    // ─── The live terminal, Stop ───

    @Test fun theTerminalsTicketIsOnTheRouteInUse() {
        connect(session("sess-term"))
        daemon.ok("POST", "/v1/sessions/sess-term/terminal", fixture("rest-session-terminal"))
        val t = rig.call { rig.hub.terminal("sess-term") }.getOrThrow()
        assertEquals(daemon.base.replaceFirst("http", "ws") + "/v1/terms/term-id/ws?ticket=ticket", t.url)
        assertNull("the base URL: the page opens the socket itself", t.client)
        daemon.on("POST", "/v1/sessions/sess-term/terminal") { refusal(409, "no_terminal", "This session isn’t running in a shared terminal.") }
        assertEquals("This session isn’t running in a shared terminal.", rig.call { rig.hub.terminal("sess-term") }.exceptionOrNull()?.message)
        daemon.ok("POST", "/v1/sessions/sess-term/open", JSONObject().put("app", "Ghostty").put("already", true))
        assertEquals("Ghostty" to true, rig.call { rig.hub.openOnMac("sess-term") }.getOrThrow())
    }

    @Test fun stopThatTheServerCantDoIsSaid() {
        connect(session("s1", state = "working"))
        daemon.ok("POST", "/v1/sessions/s1/interrupt", fixture("rest-session-interrupt"))
        rig.hub.interrupt("s1")
        rig.until("Esc") { daemon.count("POST", "/v1/sessions/s1/interrupt") == 1 }
        rig.pump()
        assertTrue(rig.toasts.isEmpty())
        daemon.on("POST", "/v1/sessions/s1/interrupt") { refusal(409, "terminal_session", "This session runs in a terminal on your Mac. Press Esc there to stop it.") }
        rig.hub.interrupt("s1")
        rig.until("the toast") { rig.toasts.isNotEmpty() }
        assertEquals("This session runs in a terminal on your Mac. Press Esc there to stop it.", rig.toasts.single())
    }

    // ─── A Codex approval, answered from the phone ───

    @Test fun aCodexApprovalIsAnsweredFromThePhoneAndGoesWhenResolved() {
        connect(
            session("cx-1", state = "needs_you") { put("agent", "codex") },
            approvals = listOf(approval("cx-a1", "cx-1", summary = "Bash: npm test") { put("agent", "codex") }),
        )
        assertEquals(listOf("cx-a1"), rig.hub.waitingAsks("cx-1").map { it.id })
        daemon.ok("POST", "/v1/approvals/cx-a1", fixture("rest-approval-answer"))
        assertTrue(rig.call { rig.hub.answerApproval("cx-a1", Answer.Deny) })
        assertEquals("deny", last("POST", "/v1/approvals/cx-a1").json.getString("decision"))
        assertTrue(rig.hub.waitingAsks("cx-1").isEmpty())
        rig.push(msg("approval.resolved") { put("id", "cx-a1").put("sessionId", "cx-1").put("decision", "deny").put("by", "phone") }) { "cx-a1" in rig.gone }
        assertTrue(rig.hub.pendingApprovals.isEmpty())
    }

    @Test fun aCodexApprovalAnsweredElsewhereFirstCountsAsDone() {
        connect(session("cx-1", state = "needs_you") { put("agent", "codex") }, approvals = listOf(approval("cx-a1", "cx-1")))
        val (status, body) = refusalBody("rest-approval-already")
        daemon.on("POST", "/v1/approvals/cx-a1") { json(status, body) }
        assertTrue(rig.call { rig.hub.answerApproval("cx-a1", Answer.Allow) })
        assertTrue(rig.hub.pendingApprovals.isEmpty())
    }

    // ─── Q5: Claude Code's own queue ───

    @Test fun promptsInClaudeCodesQueueShowAsQueuedAndGoWhenItTakesThem() {
        // As the snapshot fixture has the main session's queue: one prompt waiting behind the turn.
        val q = JSONArray().put(JSONObject().put("id", "q:2").put("text", "Check the lexer as well").put("at", 900L).put("clientId", JSONObject.NULL))
        connect(session("s1", state = "working") { put("queue", q).put("queued", 1) })
        daemon.ok("GET", "/v1/sessions/s1/events", JSONObject().put("events", JSONArray().put(JSONObject().put("id", "u1").put("kind", "user").put("ts", 800L).put("text", "Fix the parser"))))
        rig.hub.opened("s1")
        rig.until("the queue drawn") { rig.hub.transcript("s1").any { it is Item.User && it.key == "queued:q:2" } }
        val queued = rig.hub.transcript("s1").filterIsInstance<Item.User>().single { it.key == "queued:q:2" }
        assertEquals("Check the lexer as well", queued.text)
        assertTrue(queued.queued)
        // Claude Code takes it: off the queue, and its own copy is the prompt.
        rig.push(msg("session") { put("session", session("s1", state = "working") { put("queue", JSONArray()).put("queued", 0) }) }) {
            rig.hub.transcript("s1").none { it is Item.User && it.key == "queued:q:2" }
        }
    }
}

private const val LIMITED_ID = "c0ffee00-0000-4000-8000-00000000beef"
