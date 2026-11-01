package com.prudnikweb.hypernova.data

import com.prudnikweb.hypernova.ui.changes.ChangesRepo
import com.prudnikweb.hypernova.ui.changes.LineKind
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The protocol's contract fixtures (docs/PROTOCOL.md, "Contract fixtures"): every file in
 * protocol/fixtures/ is what the daemon really sends (daemon/test/contract.test.ts builds each one
 * through the daemon's own code and fails when it no longer matches), run here through the app's
 * own parsers. A change on either side the other doesn't follow fails one of the two suites.
 *
 * Gradle puts the folder on the test classpath (app/build.gradle.kts), so a changed fixture runs
 * this again. Every fixture needs its check below, and every check its fixture.
 *
 * The live feed's messages go to the parser LiveHub's own dispatch calls for their `t`
 * (LiveHub.parse, which is private): the same function, on the same part of the message.
 */
class ContractTest {
    private companion object {
        /** The daemon's normalised time (contract.test.ts, `T`). */
        const val T = 1_800_000_000_000L
        const val MAIN = "c0ffee00-0000-4000-8000-00000000c0de"
        const val LIMITED = "c0ffee00-0000-4000-8000-00000000beef"
        const val PLAIN = "a0123456789abcdef"
        const val SURVEYOR = "a2222222222222222"
        const val RUN = "wf_0a1b2c3d-4e5"
        const val CWD = "/home/me/Projects/app-parser-fix"
    }

    private val dir: File by lazy {
        val url = javaClass.classLoader!!.getResource("fixtures") ?: error("protocol/fixtures is not on the test classpath (app/build.gradle.kts)")
        File(url.toURI())
    }

    private fun fixture(name: String): JSONObject = JSONObject(File(dir, "$name.json").readText())

    /** A live-feed message of type [t]. */
    private fun ws(o: JSONObject, t: String): JSONObject = o.also { assertEquals(t, it.str("t")) }

    private val checks: Map<String, (JSONObject) -> Unit> = mapOf(
        "snapshot" to ::snapshot,
        // ── The live feed ──
        "ws-session" to { o ->
            val s = Wire.session(ws(o, "session").obj("session")!!)
            assertEquals("sess-died", s.id)
            assertTrue(s.died)
            assertEquals("died0001", s.agentJob)
            assertEquals(false, s.agentRunning)
            assertEquals("idle", s.state)
        },
        "ws-session-removed" to { o -> assertEquals("sess-gone", ws(o, "session.removed").str("id")) },
        "ws-job-died" to { o -> assertEquals(JobDied("sess-died", "exit 1 — Error: the API refused the request", T), JobDied.parse(ws(o, "job.died"))) },
        "ws-event-approval" to { o ->
            ws(o, "event")
            assertEquals(MAIN, o.str("sessionId"))
            val e = Wire.event(o.obj("event")!!) as WEvent.Approval
            assertTrue(e.approvalId.isNotBlank())
            assertEquals("a:${e.approvalId}", e.id)
            assertEquals("Bash: npm test --prefix daemon", e.summary)
            assertNull(e.resolution)
        },
        "ws-event-notice" to { o ->
            ws(o, "event")
            val e = Wire.event(o.obj("event")!!) as WEvent.Notice
            assertEquals("Claude Code restarted", e.text)
            assertEquals("warn", e.tone)
            assertEquals(T, e.ts)
        },
        "ws-event-removed" to { o ->
            val (sid, id) = removedEvent(ws(o, "event.removed"))!!
            assertEquals(MAIN, sid)
            assertTrue(id.startsWith("n:"))
        },
        "ws-approval" to { o -> toolApproval(Wire.approval(ws(o, "approval").obj("approval")!!)) },
        "ws-approval-resolved" to { o ->
            ws(o, "approval.resolved")
            assertNotNull(o.str("id"))
            assertEquals(MAIN, o.str("sessionId"))
            assertEquals("allow", o.str("decision"))
            assertEquals("phone", o.str("by"))
        },
        "ws-usage" to { o -> usageWithoutFiveHours(Wire.usage(ws(o, "usage").obj("usage"))) },
        "ws-agent" to { o ->
            val j = ws(o, "agent").obj("agent")!!
            assertFalse(j.bool("superseded"))
            val a = Wire.agent(j)!!
            assertEquals(PLAIN, a.id)
            assertEquals(MAIN, a.sessionId)
            assertEquals("toolu_agent", a.toolUseId)
            assertEquals("Review the parser", a.label)
            assertEquals("Explore", a.type)
            assertEquals("claude-sonnet-5-5", a.model)
            assertTrue(a.background)
            assertTrue(a.running)
        },
        "ws-workflow" to { o -> workflow(Wire.workflow(ws(o, "workflow").obj("workflow")!!)!!) },
        "ws-projects" to { o ->
            val p = ws(o, "projects").arr("projects").mapObjects(Wire::project)
            assertEquals(o.arr("projects")!!.length(), p.size)
            assertEquals(setOf("demo", "app", "app-parser-fix"), p.map { it.name }.toSet())
            assertEquals(8, p.first { it.name == "app-parser-fix" }.sessionCount)
            assertEquals("/home/me/Projects/demo", p.first { it.name == "demo" }.path)
        },
        "ws-providers" to { o -> builtInProvider(ws(o, "providers").arr("providers").mapObjects(Wire::provider)) },
        // On the limit a transcript said (rest-session-events-limit).
        "ws-account" to { o ->
            val a = Wire.account(ws(o, "account").obj("account")!!)
            assertEquals("limited", a.state)
            assertEquals("anthropic", a.providerId)
            assertEquals(Account.Limit("five_hour", "session limit", T, null, false), a.limit)
            assertEquals("You've hit your session limit · resets 2am", a.text)
            assertEquals(T, a.since)
            assertEquals(LIMITED, a.sessionId)
            assertNull(a.login)
        },
        "ws-session-limited" to { o ->
            val s = Wire.session(ws(o, "session").obj("session")!!)
            assertEquals(LIMITED, s.id)
            assertEquals(Blocked("limit", "You've hit your session limit · resets 2am", T, null, null), s.blocked)
            assertNull(s.turnStartedAt)
        },
        "ws-usage-limited" to { o ->
            val u = Wire.usage(ws(o, "usage").obj("usage"))!!
            assertEquals(Window(100.0, T), u.fiveHour)
            assertEquals(Window(62.0, T), u.sevenDay)
        },
        "ws-codex" to { o -> codexOff(CodexWire.state(ws(o, "codex").obj("codex"))) },
        "ws-claude" to { o -> assertEquals(ClaudeState(true, "2.1.314", agents = true), ClaudeState.parse(ws(o, "claude").obj("claude"))) },
        "ws-files" to { o -> assertTrue(ws(o, "files").obj("files")?.bool("browseOutsideProjects") == true) },
        "ws-update" to { o ->
            val u = ServerUpdate.parse(ws(o, "update").obj("update"))!!
            assertTrue(u.auto)
            assertEquals(false, u.available)
            assertTrue(u.reason!!.isNotBlank())
            assertEquals(ServerUpdate.IDLE, u.state)
            assertNull(u.last)
        },
        "ws-lan" to { o ->
            val lan = LanInfo.parse(ws(o, "lan").obj("lan"))!!
            assertEquals(47323, lan.port)
            assertEquals(listOf("192.168.1.20"), lan.addresses)
            assertEquals(64, lan.fingerprint.length)
            assertFalse(lan.deviceCert)
        },
        "ws-app" to { o ->
            val before = Wire.mac(fixture("snapshot").obj("mac")!!)
            val after = Wire.withApp(before, ws(o, "app").obj("app"))
            assertEquals(4300, after.appVersionCode)
            assertEquals("0.43.0", after.appVersionName)
        },
        // ── The routes ──
        "rest-session-events" to { o ->
            val ev = events(o)
            val user = ev.filterIsInstance<WEvent.User>()
            assertEquals("Fix the parser so it trims its input", user[0].text)
            assertNull(user[0].command)
            assertEquals(CommandRun("/effort", "high", "local"), user[1].command)
            assertEquals(CommandKind.Local, user[1].command!!.type)
            assertEquals(40, ev.filterIsInstance<WEvent.Thinking>().single().chars)
            assertEquals("I will make the parser trim its input.", ev.filterIsInstance<WEvent.Assistant>().single().text)
            val tools = ev.filterIsInstance<WEvent.Tool>().associateBy { it.name }
            val edit = tools.getValue("Edit")
            assertEquals("done", edit.status)
            assertEquals("$CWD/parser.ts", edit.path)
            assertEquals("$CWD/parser.ts", edit.input["file_path"])
            val diff = edit.diff!!
            assertEquals(1, diff.added)
            assertEquals(1, diff.removed)
            assertEquals(listOf("-export const parse = (s: string) => s", "+export const parse = (s: string) => s.trim()"), diff.hunks.single().lines)
            val bash = tools.getValue("Bash")
            assertEquals(0, bash.exitCode)
            assertEquals("ok 82 tests", bash.output)
            assertEquals(1000L, bash.durationMs)
            assertEquals("npm test", bash.input["command"])
            assertEquals("running", tools.getValue("Agent").status)
            assertEquals("running", tools.getValue("Workflow").status)
            assertEquals("done", tools.getValue("TodoWrite").status)
            val unsent = ev.filterIsInstance<WEvent.Notice>().single().unsent!!
            assertTrue(unsent.returned)
            assertEquals(listOf(Unsent.Prompt("Run the linter too", null)), unsent.prompts)
            assertEquals("allow", ev.filterIsInstance<WEvent.Approval>().single().resolution)
        },
        "rest-session-events-limit" to { o ->
            val n = events(o).filterIsInstance<WEvent.Notice>().single()
            assertEquals(Blocked.LIMIT, n.blocker)
            assertEquals("warn", n.tone)
            assertTrue(n.text.startsWith("You've hit your session limit"))
        },
        "rest-agents" to { o ->
            val agents = o.arr("agents").mapObjects(Wire::agent).associateBy { it.id }
            assertEquals(o.arr("agents")!!.length(), agents.size)
            val plain = agents.getValue(PLAIN)
            assertTrue(plain.running)
            assertEquals("Read: app-parser-fix/parser.ts", plain.activity)
            assertEquals(20100L, plain.tokens)
            assertEquals(1, plain.toolUses)
            val s = agents.getValue(SURVEYOR)
            assertEquals(RUN, s.workflowId)
            assertEquals("Survey", s.phase)
            assertEquals("done", s.status)
            assertEquals("The parser never trims", s.result)
            workflow(o.arr("workflows").mapObjects(Wire::workflow).single())
        },
        "rest-agent-events" to { o ->
            val ev = events(o)
            assertEquals(listOf(WEvent.User::class, WEvent.Assistant::class, WEvent.Tool::class), ev.map { it::class })
            assertEquals("cursor", o.str("cursor"))
            assertTrue(!o.has("full") || o.bool("full"))
            assertFalse(o.has("starting") && o.bool("starting"))
        },
        // LiveHub.agentEvents reads it so: AgentStarting, nothing kept.
        "rest-agent-events-starting" to { o -> assertTrue(o.has("starting") && o.bool("starting")) },
        "rest-changes" to { o ->
            val c = ChangesRepo.parseChanges(o.getJSONObject("changes"))
            assertTrue(c.git)
            assertEquals("parser-fix", c.branch)
            assertTrue(c.scoped)
            val f = c.files.single()
            assertEquals("parser.ts", f.path)
            assertEquals('M', f.status)
            assertEquals(1, f.add)
            assertEquals(1, f.del)
            assertTrue(f.touched)
            assertFalse(f.untracked)
            assertEquals(2, c.others)
            assertEquals(0, c.hidden)
            assertEquals("origin/main", c.upstream)
            assertEquals(2, c.ahead)
            assertEquals(1, c.behind)
            assertEquals(3, c.dirty)
            assertEquals("app-parser-fix", c.worktree)
        },
        "rest-changes-diff" to { o ->
            val lines = ChangesRepo.parseDiff(o.optString("diff"))
            assertEquals(listOf(LineKind.Hunk, LineKind.Del, LineKind.Add), lines.map { it.kind })
            assertEquals("export const parse = (s: string) => s.trim()", lines.last().text)
            assertFalse(o.optBoolean("truncated"))
            assertFalse(o.optBoolean("binary"))
        },
        "rest-permissions" to { o ->
            val p = PermissionRulesWire.of(o)
            assertFalse(p.managedOnly)
            assertEquals(o.obj("permissions")!!.arr("sources")!!.length(), p.sources.size)
            val by = p.sources.associateBy { it.source }
            assertEquals(RuleSource.MISSING, by.getValue(RuleSource.LOCAL).state)
            val project = by.getValue(RuleSource.PROJECT)
            assertEquals("$CWD/.claude/settings.json", project.path)
            assertEquals(listOf("Bash(npm test *)"), project.allow)
            assertEquals(listOf("Read(./.env)"), project.deny)
            assertEquals("acceptEdits", project.defaultMode)
            val user = by.getValue(RuleSource.USER)
            assertTrue(user.ok)
            assertEquals(listOf("Bash(git push *)"), user.ask)
            assertEquals(listOf("~/shared"), user.additionalDirectories)
        },
        "rest-history-search" to { o ->
            val h = HistorySearch.wire(o)
            assertFalse(h.partial)
            val hit = h.hits.single()
            assertEquals(MAIN, hit.id)
            assertEquals("Fix the parser so it trims its input", hit.snippet)
            assertEquals(listOf(8 until 14, 21 until 26), hit.marks)
            assertFalse(hit.fromClaude)
            assertEquals(3, hit.matches)
        },
        "rest-prompt-held" to { o ->
            // LiveHub.sendNow: held, so the session's `held` shows it, not the phone's copy.
            assertTrue(o.bool("held"))
            assertTrue(o.has("queued") && o.bool("queued"))
            val s = Wire.session(o.obj("session")!!)
            assertEquals("sess-held", s.id)
            assertEquals(listOf("Also run the linter" to "client-held-1", "Then commit it" to "client-held-2"), s.held.map { it.text to it.clientId })
            assertEquals(o.str("heldId"), s.held.last().id)
        },
        "rest-allow" to { o -> assertEquals(2, o.optJSONArray("allowed")?.length()) },
        "rest-session-effort" to { o ->
            val s = Wire.session(o.obj("session")!!)
            assertEquals("max", s.effort)
            assertEquals(TermLink("term-id", 0, 0), s.term)
            assertEquals("eff00001", s.agentJob)
            assertEquals("now", o.str("applies"))
        },
        "rest-models" to { o ->
            val list = ModelCatalog.wire(o).single()
            assertEquals("anthropic", list.providerId)
            assertEquals("claude", list.source)
            assertNull(list.error)
            assertEquals(listOf(null, "sonnet", "sonnet[1m]"), list.models.map { it.value })
            assertEquals("Default", list.models[0].name)
            assertEquals("claude-opus-5-5", list.models[0].model)
            assertEquals(1_000_000L, list.models[0].context)
        },
        "rest-session-model" to { o ->
            assertEquals("claude-sonnet-5-5", o.str("model"))
            assertEquals("now", o.str("applies"))
            assertEquals("claude-sonnet-5-5", Wire.session(o.obj("session")!!).model)
        },
        "rest-session-mode" to { o ->
            assertEquals("plan", Wire.session(o.obj("session")!!).permissionMode)
            assertNull(o.str("applies"))
        },
        "rest-session-rename" to { o -> assertEquals("Parser fix", Wire.session(o.obj("session")!!).title) },
        "rest-btw-ask" to { o ->
            val a = AsideWire.of(o)!!
            assertTrue(a.asking)
            assertEquals("Why does the parser wrap around?", a.question)
            assertNull(a.answer)
            assertEquals(T, a.askedAt)
        },
        "rest-btw" to { o ->
            val a = AsideWire.of(o)!!
            assertEquals(Aside.ANSWERED, a.state)
            assertTrue(a.answer!!.startsWith("The parser keeps its tokens in a ring buffer"))
            assertFalse(a.clipped)
            assertNull(a.error)
        },
        "rest-session-stop" to { o -> assertEquals("sess-stop", Wire.session(o.obj("session")!!).id) },
        "rest-session-respawn" to { o ->
            val s = Wire.session(o.obj("session")!!)
            assertEquals("sess-respawn", s.id)
            assertNull(s.agentOutdated)
        },
        "rest-jobs-respawn" to { o -> assertEquals(listOf(JobRestart("sess-all", true, "Done all00001")), OutdatedJobs.results(o)) },
        "rest-update" to { o -> assertEquals(true, ServerUpdate.parse(o.obj("update"))?.auto) },
        "rest-claude" to { o -> assertEquals(ClaudeState(true, "2.1.314", agents = true), ClaudeState.parse(o.obj("claude"))) },
        // Pairing.pair reads these three.
        "rest-pair" to { o ->
            assertEquals("device-token", o.str("token"))
            assertEquals("device-id", o.str("deviceId"))
            assertEquals("server", o.obj("mac")?.str("name"))
            assertEquals("mac-id", o.obj("mac")?.str("id"))
        },
    ) + FeatureContracts.checks

    @Test fun everyFixtureHasItsChecksAndEveryCheckItsFixture() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.toSortedSet()
        assertTrue("no fixtures in $dir", files.isNotEmpty())
        assertEquals("fixtures without a check here (left), or checks without a fixture (right)", files.toList(), checks.keys.sorted())
    }

    @Test fun everyFixtureParsesAsTheDaemonMeantIt() {
        val failures = checks.mapNotNull { (name, check) ->
            val file = File(dir, "$name.json")
            if (!file.isFile) return@mapNotNull null
            runCatching { check(JSONObject(file.readText())) }.exceptionOrNull()?.let { "$name.json: ${it::class.simpleName}: ${it.message}" }
        }
        if (failures.isNotEmpty()) fail("protocol/fixtures the app doesn't read as the daemon sends them:\n" + failures.joinToString("\n"))
    }

    // ── Shared checks ──

    private fun events(o: JSONObject): List<WEvent> {
        val ev = o.arr("events").mapObjects(Wire::event)
        assertEquals("every event read", o.arr("events")!!.length(), ev.size)
        assertEquals("no event of a kind the app doesn't know", emptyList<WEvent>(), ev.filterIsInstance<WEvent.Unknown>())
        return ev
    }

    private fun toolApproval(a: WApproval) {
        assertEquals(MAIN, a.sessionId)
        assertEquals("tool", a.kind)
        assertEquals("Bash", a.toolName)
        assertEquals("npm test --prefix daemon", a.input["command"])
        assertTrue(a.canAlways)
        assertFalse(a.canSession)
        assertEquals(listOf("Bash(npm test *)"), a.alwaysRules)
        assertEquals("project", a.alwaysScope)
        assertEquals(AlwaysScope.Project, AlwaysScope.of(a.alwaysScope))
        val why = a.why!!
        assertEquals("safety", why.kind)
        assertEquals("hypernova", why.source)
        assertEquals("Bash(npm test *)", why.rule)
        assertFalse(a.autoDeny)
        assertEquals(T, a.expiresAt)
    }

    private fun usageWithoutFiveHours(u: WUsage?) {
        assertNotNull(u)
        assertNull(u!!.fiveHour)
        assertEquals(Window(62.0, T), u.sevenDay)
        assertEquals("official", u.source)
        assertEquals(T, u.observedAt)
        assertEquals(listOf(WModelWeek("Sonnet", 12.0, T)), u.models)
        assertEquals(UsageRate(null, null, null, null), u.rate)
    }

    private fun workflow(w: WWorkflow) {
        assertEquals(RUN, w.id)
        assertEquals(MAIN, w.sessionId)
        assertEquals("toolu_wf", w.toolUseId)
        assertEquals("parser-survey", w.name)
        assertEquals("Survey the parser, then fix it", w.summary)
        assertTrue(w.running)
        assertEquals(listOf(WPhase("Survey", "Read the code"), WPhase("Implement", null)), w.phases)
        assertEquals(1, w.agentCount)
        assertEquals(1, w.done)
        assertEquals(0, w.failed)
    }

    private fun builtInProvider(p: List<Provider>) {
        val a = p.single()
        assertEquals("anthropic", a.id)
        assertEquals("anthropic", a.kind)
        assertTrue(a.builtIn)
        assertFalse(a.paid)
        assertFalse(a.hasKey)
        assertNull(a.status)
    }

    private fun codexOff(c: CodexState?) {
        assertNotNull(c)
        assertTrue(c!!.available)
        assertFalse(c.enabled)
        assertFalse(c.connected)
        assertEquals("0.163.0", c.version)
        assertEquals("0.163.0", c.min)
        assertEquals("/tmp/hn/codex", c.path)
        assertNull(c.problem)
        assertNull(c.account)
    }

    // ── GET /v1/snapshot ──

    private fun snapshot(o: JSONObject) {
        val s = Wire.snapshot(o)
        val mac = s.mac
        assertEquals("mac-id", mac.id)
        assertEquals("server", mac.name)
        assertEquals("0.0.0", mac.daemonVersion)
        assertEquals("2.1.314", mac.claudeVersion)
        assertEquals("/home/me/Projects", mac.projectsRoot)
        assertEquals(4200, mac.appVersionCode)
        assertEquals("0.42.0", mac.appVersionName)
        assertNull(mac.battery)
        assertEquals(MacAwake.OFF, mac.awake!!.mode)
        assertEquals(Mac.LINUX, mac.platform)
        assertEquals(false, mac.browseOutsideProjects)
        assertEquals(false, mac.update!!.auto)
        assertEquals(false, mac.update!!.available)
        assertEquals(setOf("battery", "awake", "macVoice", "openWindow"), mac.unavailable.keys)
        usageWithoutFiveHours(s.usage)

        val sessions = s.sessions.associateBy { it.id }
        assertEquals("every session read", o.arr("sessions")!!.length(), sessions.size)
        assertEquals("every approval read", o.arr("approvals")!!.length(), s.approvals.size)
        val approvals = s.approvals.associateBy { it.kind }

        val main = sessions.getValue(MAIN)
        assertEquals("Fix the parser so it trims its input", main.title)
        assertEquals(CWD, main.cwd)
        assertEquals("app-parser-fix", main.projectName)
        assertEquals("terminal", main.origin)
        assertEquals("needs_you", main.state)
        assertEquals("claude-opus-5-5", main.model)
        assertEquals("high", main.effort)
        assertEquals("acceptEdits", main.permissionMode)
        assertEquals(42.5, main.contextPct!!, 0.0)
        assertEquals(1.25, main.costUsd!!, 0.0)
        assertEquals("I will make the parser trim its input.", main.lastText)
        assertEquals("Bash: npm test --prefix daemon", main.activity)
        assertEquals(listOf(approvals.getValue("tool").id), main.pendingApprovalIds)
        assertEquals(T, main.turnStartedAt)
        assertEquals(TodoNow("Running the tests", 1, 3), main.todo)
        assertEquals(1, main.agentsRunning)
        assertEquals(1, main.queued)
        assertEquals(listOf("Check the lexer as well"), main.queue.map { it.text })
        assertTrue(main.browsable)
        assertFalse(main.outsideProjects)
        assertEquals(AgentKind.CLAUDE, main.agent)
        assertNull(main.agentJob)
        assertNull(main.agentRunning)
        assertFalse(main.died)

        // Claude Code's own permission dialog on the terminal's screen: an approval card.
        val dialog = sessions.getValue("sess-dialog").menu!!
        assertEquals(listOf("Yes", "No"), dialog.options.map { it.label })
        assertTrue(dialog.cancel)
        assertNull(dialog.steps)
        val perm = dialog.permission!!
        assertEquals("Bash command", perm.tool)
        assertTrue(perm.command!!.startsWith("/media/cubxi/docker/projects/arc/.venv/bin/python "))
        assertEquals("Do you want to proceed?", perm.question)
        assertEquals(Why("ask_rule", "Ask rule Bash(python:*) overrides auto mode for this command.", "Bash(python:*)", "claude"), perm.why)

        // AskUserQuestion's form of two questions: its tabs.
        val form = sessions.getValue("sess-form").menu!!
        assertEquals(TermMenu.Steps(listOf("Versioning", "First version"), listOf(false, false)), form.steps)
        assertEquals("Which versioning scheme should releases use?", form.title)
        assertEquals(5, form.options.size)
        assertEquals("MAJOR.MINOR.PATCH, bumped from the commit types", form.options[0].detail)
        assertNull(form.permission)

        val died = sessions.getValue("sess-died")
        assertTrue(died.died)
        assertEquals("died0001", died.agentJob)
        assertEquals(false, died.agentRunning)

        val job = sessions.getValue("sess-job")
        assertEquals("old00001", job.agentJob)
        assertEquals(true, job.agentRunning)
        assertEquals(OutdatedClaude("2.1.312", "2.1.314"), job.agentOutdated)
        assertEquals("working", job.state)
        assertEquals(T, job.turnStartedAt)

        val held = sessions.getValue("sess-held")
        assertEquals(listOf("Also run the linter"), held.held.map { it.text })
        assertEquals("client-held-1", held.held.single().clientId)
        assertEquals(T, held.held.single().at)
        assertEquals(listOf(approvals.getValue("question").id), held.pendingApprovalIds)

        val limited = sessions.getValue("sess-limit")
        assertEquals(Blocked(Blocked.LIMIT, "You’ve hit your session limit · resets 2am", T, null, null), limited.blocked)
        assertNull(limited.turnStartedAt)

        assertEquals("hypernova", sessions.getValue("sess-plan").origin)

        // The approvals: one from a hook (Always's rules, why), a question that says no by itself, a plan that waits.
        toolApproval(approvals.getValue("tool"))
        val q = approvals.getValue("question")
        assertEquals("sess-held", q.sessionId)
        assertTrue(q.autoDeny)
        assertEquals(T, q.expiresAt)
        assertEquals(emptyList<String>(), q.alwaysRules)
        assertNull(q.why)
        val question = q.questions.single()
        assertEquals("Which version comes first?", question.question)
        assertEquals("Version", question.header)
        assertFalse(question.multiSelect)
        assertEquals(listOf(Question.Option("1.0", "The stable one"), Question.Option("2.0", "The new parser")), question.options)
        val plan = approvals.getValue("plan")
        assertEquals("1. Split the lexer out\n2. Trim the input", plan.plan)
        assertNull(plan.expiresAt)
        assertFalse(plan.autoDeny)

        assertEquals(setOf("app", "app-parser-fix"), s.projects.map { it.name }.toSet())
        builtInProvider(s.providers)
        assertEquals(Account(), s.account)
        val auto = s.automations!!
        assertFalse(auto.paused)
        assertEquals(emptyList<Automation>(), auto.list)
        assertEquals("UTC", auto.tz)
        assertNull(s.lan)
        codexOff(s.codex)
        assertEquals(ClaudeState(true, "2.1.314", agents = true), s.claude)
        // LiveHub hands the snapshot's `speech` to SpeechLink.snapshot, which reads it so.
        assertNotNull(SpeechOut.parse(o.obj("speech")))
    }
}
