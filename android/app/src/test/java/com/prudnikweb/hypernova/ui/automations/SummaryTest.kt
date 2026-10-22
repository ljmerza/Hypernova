package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.AutoMsg
import com.prudnikweb.hypernova.data.AutomationInput
import com.prudnikweb.hypernova.data.AutomationWire
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionAutomation
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Then
import com.prudnikweb.hypernova.data.Trigger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The automations' words (spec §6): the summary sentence for every trigger, action and "then",
 * the verb rule and the cut, the run lines, and the wire format. It reads the real strings file,
 * so what's checked here is what the phone says.
 */
class SummaryTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    /** Thursday 16 October 2026, 11:40 on the Mac. */
    private val now = ZonedDateTime.of(LocalDate.of(2026, 10, 16), LocalTime.of(11, 40), zone).toInstant().toEpochMilli()
    private val w = XmlWords()
    private val ctx = SummaryContext(
        zone, now,
        projectName = { it.substringAfterLast('/') },
        sessionName = { if (it == "s1") "Parser refactor" else null },
        providerName = { if (it == "openrouter") "OpenRouter" else null },
    )
    private val hyper = "/Users/me/Programming/hypernova"
    private fun start(prompt: String, then: String = Then.NONE) = Action.Start(hyper, prompt, "anthropic", null, AutoMode.ALLOWED, emptyList(), then)
    /** The sentence, its invisible word joiners (which keep "/review" in one piece) taken out. */
    private fun say(t: Trigger, a: Action, once: Boolean = false, words: Words = w) = Summary.sentence(words, t, a, once, ctx).replace("\u2060", "")
    private fun at(day: LocalDate, hh: Int, mm: Int) = ZonedDateTime.of(day, LocalTime.of(hh, mm), zone).toInstant().toEpochMilli()

    @Test fun theMorningBriefReadsAsTheSpecSaysIt() {
        val brief = "Summarise what changed in this project since yesterday: commits, branches, open work, anything failing. Then say what's worth doing next. Don't change anything."
        assertEquals(
            "Every weekday at 9:00, in hypernova, ask Claude to summarise what changed in this project since yesterday: commits, branches, open work, anything failing. Then say what's… Notify me when it's done.",
            say(Trigger.Time("09:00", Trigger.WEEKDAYS), start(brief, Then.NOTIFY)),
        )
    }

    @Test fun timesOfDayAndDays() {
        val a = start("Run the tests")
        assertEquals("Every day at 9:00, in hypernova, ask Claude to run the tests.", say(Trigger.Time("09:00", Trigger.ALL_DAYS), a))
        assertEquals("Every weekend at 10:30, in hypernova, ask Claude to run the tests.", say(Trigger.Time("10:30", listOf(6, 7)), a))
        assertEquals("Every Mon, Wed and Fri at 9:00, in hypernova, ask Claude to run the tests.", say(Trigger.Time("09:00", listOf(1, 3, 5)), a))
        assertEquals("Every Tue at 18:05, in hypernova, ask Claude to run the tests.", say(Trigger.Time("18:05", listOf(2)), a))
        // A 12-hour phone.
        assertEquals("Every day at 9:00 AM, in hypernova, ask Claude to run the tests.", say(Trigger.Time("09:00", Trigger.ALL_DAYS), a, words = XmlWords(h24 = false, locale = Locale.US)))
        // A step Codex runs asks Codex.
        val onCodex = a.copy(providerId = AgentKind.CODEX_PROVIDER, permissionMode = AgentKind.READ_ONLY)
        assertEquals("Every day at 9:00, in hypernova, ask Codex to run the tests.", say(Trigger.Time("09:00", Trigger.ALL_DAYS), onCodex))
        assertEquals("Every day at 9:00, in hypernova, ask Codex: “What changed today?”", say(Trigger.Time("09:00", Trigger.ALL_DAYS), onCodex.copy(prompt = "What changed today?")))
        // A time trigger repeats by nature: "next time only" is for events.
        assertEquals("Every day at 9:00, in hypernova, ask Claude to run the tests.", say(Trigger.Time("09:00", Trigger.ALL_DAYS), a, once = true))
    }

    @Test fun once() {
        val a = start("Run the tests")
        val today = LocalDate.of(2026, 10, 16)
        assertEquals("Today at 15:30, in hypernova, ask Claude to run the tests.", say(Trigger.Once(at(today, 15, 30)), a))
        assertEquals("Tomorrow at 9:00, in hypernova, ask Claude to run the tests.", say(Trigger.Once(at(today.plusDays(1), 9, 0)), a))
        assertEquals("On Tue 20 Oct at 9:00, in hypernova, ask Claude to run the tests.", say(Trigger.Once(at(today.plusDays(4), 9, 0)), a))
    }

    @Test fun everyFewHours() {
        val a = start("Check the build")
        assertEquals("Every hour, in hypernova, ask Claude to check the build.", say(Trigger.Every(1, "00:00", null, Trigger.ALL_DAYS), a))
        assertEquals("Every 2 hours, 9:00–19:00 on weekdays, in hypernova, ask Claude to check the build.", say(Trigger.Every(2, "09:00", "19:00", Trigger.WEEKDAYS), a))
        // Around the clock it keeps one grid every day: from 8:00 every 3 hours is also 2:00 and 5:00 (A13).
        assertEquals("Every 3 hours from 2:00 on Mon and Thu, in hypernova, ask Claude to check the build.", say(Trigger.Every(3, "08:00", null, listOf(1, 4)), a))
        assertEquals("Every 2 hours from 1:00, in hypernova, ask Claude to check the build.", say(Trigger.Every(2, "09:00", null, Trigger.ALL_DAYS), a))
        assertEquals("Every 4 hours, in hypernova, ask Claude to check the build.", say(Trigger.Every(4, "08:00", null, Trigger.ALL_DAYS), a))
        // A window past midnight reads as the Mac runs it.
        assertEquals("Every 2 hours, 22:00–6:00, in hypernova, ask Claude to check the build.", say(Trigger.Every(2, "22:00", "06:00", Trigger.ALL_DAYS), a))
        assertEquals("Every 6 hours on weekends, in hypernova, ask Claude to check the build.", say(Trigger.Every(6, "00:00", null, listOf(6, 7)), a))
    }

    @Test fun limitsResettingAndHit() {
        val a = start("")
        assertEquals("When my 5-hour limit resets after I hit it, in hypernova, start a session.", say(Trigger.Reset(Trigger.FIVE_HOUR, true), a))
        assertEquals("Whenever my weekly window resets, in hypernova, start a session.", say(Trigger.Reset(Trigger.SEVEN_DAY, false), a))
        assertEquals("Whenever my 5-hour window resets between 0:00 and 7:00, in hypernova, start a session.", say(Trigger.Reset(Trigger.FIVE_HOUR, false, "00:00", "07:00"), a))
        assertEquals("When my 5-hour limit resets after I hit it (next time only), in hypernova, start a session.", say(Trigger.Reset(Trigger.FIVE_HOUR, true), a, once = true))
        assertEquals("When I hit my 5-hour limit, in hypernova, start a session.", say(Trigger.Limit(Trigger.FIVE_HOUR), a))
    }

    @Test fun sessionsAndPower() {
        val a = Action.Notify("Done")
        assertEquals("When a session in hypernova finishes, notify me: “Done”.", say(Trigger.SessionEvent(Trigger.FINISHED, hyper, Trigger.MINE), a))
        assertEquals("When any session finishes, notify me: “Done”.", say(Trigger.SessionEvent(Trigger.FINISHED, null, Trigger.MINE), a))
        assertEquals("When an automated session in hypernova needs me, notify me: “Done”.", say(Trigger.SessionEvent(Trigger.NEEDS_YOU, hyper, Trigger.AUTOMATED), a))
        assertEquals("When any automated session finishes, notify me: “Done”.", say(Trigger.SessionEvent(Trigger.FINISHED, null, Trigger.AUTOMATED), a))
        assertEquals("When any session in hypernova, automated ones too, finishes, notify me: “Done”.", say(Trigger.SessionEvent(Trigger.FINISHED, hyper, Trigger.ANY), a))
        assertEquals("When any session, automated ones too, needs me, notify me: “Done”.", say(Trigger.SessionEvent(Trigger.NEEDS_YOU, null, Trigger.ANY), a))
        assertEquals("When the Mac is plugged in, notify me: “Done”.", say(Trigger.Plugged, a))
        assertEquals("When the Mac's battery drops to 15%, notify me: “Done”.", say(Trigger.BatteryLow(15), a))
        assertEquals("When the Mac is plugged in (next time only), notify me: “Done”.", say(Trigger.Plugged, a, once = true))
        assertEquals("When something this app doesn't know yet happens, notify me: “Done”.", say(Trigger.Unknown("moon", "{}"), a))
    }

    @Test fun startActions() {
        val t = Trigger.Time("09:00", Trigger.ALL_DAYS)
        // No verb up front: quoted, and a question mark already ends it.
        assertEquals("Every day at 9:00, in hypernova, ask Claude: “What changed overnight?” Notify me when it's done.", say(t, start("What changed overnight?", Then.NOTIFY)))
        assertEquals("Every day at 9:00, in hypernova, ask Claude: “The parser”.", say(t, start("The parser")))
        assertEquals("Every day at 9:00, in hypernova, run /review. Read the result aloud on my glasses.", say(t, start("/review", Then.SAY)))
        assertTrue(Summary.sentence(w, t, start("/review"), false, ctx).contains("/\u2060review"))
        assertEquals("Every day at 9:00, in hypernova, run /release-notes 1.2.", say(t, start("/release-notes 1.2")))
        assertEquals("Every day at 9:00, in hypernova, start a session.", say(t, start("   ")))
    }

    @Test fun continueActions() {
        val t = Trigger.SessionEvent(Trigger.FINISHED, null, Trigger.MINE)
        assertEquals("When any session finishes, ask that session to run the tests.", say(t, Action.Continue(Action.Continue.TRIGGER, "Run the tests", Then.NONE)))
        assertEquals("When any session finishes, ask every working session: “Wrap up”.", say(t, Action.Continue(Action.Continue.WORKING, "Wrap up", Then.NONE)))
        assertEquals("When any session finishes, ask “Parser refactor” to fix the lint. Notify me when it's done.", say(t, Action.Continue("s1", "Fix the lint", Then.NOTIFY)))
        assertEquals("When any session finishes, ask a session that's gone to fix the lint.", say(t, Action.Continue("gone", "Fix the lint", Then.NONE)))
        val reset = Trigger.Reset(Trigger.FIVE_HOUR, true)
        assertEquals("When my 5-hour limit resets after I hit it, carry on every session the limit stopped.", say(reset, Action.Continue(Action.Continue.LIMITED, "", Then.NONE)))
        assertEquals(
            "When I hit my 5-hour limit, carry on every session the limit stopped on OpenRouter, which costs money.",
            say(Trigger.Limit(Trigger.FIVE_HOUR), Action.Continue(Action.Continue.LIMITED, "", Then.NONE, providerId = "openrouter")),
        )
    }

    @Test fun notifyAndReadAloud() {
        val t = Trigger.Reset(Trigger.SEVEN_DAY, false)
        assertEquals("Whenever my weekly window resets, notify me: “{week}”.", say(t, Action.Notify("{week}")))
        assertEquals("Whenever my weekly window resets, notify me, and read it aloud on my glasses: “{week}”.", say(t, Action.Notify("{week}", glasses = true)))
        assertEquals("Whenever my weekly window resets, read aloud on my glasses: “Time to stretch.”", say(t, Action.Say("Time to stretch.")))
        assertEquals("Whenever my weekly window resets, notify me.", say(t, Action.Notify("")))
        assertEquals("Whenever my weekly window resets, do something this app doesn't know yet.", say(t, Action.Unknown("x", "{}")))
    }

    @Test fun theVerbRuleAndTheCut() {
        assertEquals("run CI on main", Summary.verbPhrase("Run CI on main"))
        assertEquals("summarize the week", Summary.verbPhrase("Summarize the week"))
        assertNull(Summary.verbPhrase("Don't touch main"))
        assertNull(Summary.verbPhrase("Parser refactor"))
        val long = "word ".repeat(40).trim()
        val cut = Summary.cut(long)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.length <= Summary.CUT + 1)
        assertEquals("Line one…", Summary.cut("Line one\nLine two"))
        assertEquals("Line one.", Summary.cut("Line one.\nLine two"))
        assertEquals("a b", Summary.cut("  a   b  "))
    }

    @Test fun runLines() {
        fun run(state: String, reason: String? = null, until: Long? = null, started: Long? = now - 74_000, ended: Long? = null, result: String? = null, quiet: Boolean = false, late: Long = 0L, missed: Int = 0, lateWhy: String? = null) =
            Run("ru_1", "au_1", "Morning brief", "schedule", at(LocalDate.of(2026, 10, 16), 9, 0), null, state, reason, until, listOf("s1"), started, ended, late = late, missed = missed, lateWhy = lateWhy, result = result, quiet = quiet)
        fun line(r: Run, ask: String? = null, provider: String? = null) = RunWords.line(w, r, now, zone, ask, provider)
        assertEquals("Waiting for a free slot", line(run(Run.QUEUED)).text)
        assertEquals("Waiting out your limit until 14:00", line(run(Run.WAITING, until = at(LocalDate.of(2026, 10, 16), 14, 0))).text)
        assertEquals("Waiting for the session to be free", line(run(Run.WAITING, "target_busy")).text)
        assertEquals("Running for 1m 14s", line(run(Run.RUNNING)).text)
        assertEquals(RunTone.Active, line(run(Run.RUNNING)).tone)
        assertEquals("Needs you: Bash: git push", line(run(Run.NEEDS_YOU), ask = "Bash: git push").text)
        assertEquals(RunTone.Alert, line(run(Run.NEEDS_YOU)).tone)
        assertEquals("3 PRs merged.", line(run(Run.DONE, result = "3 PRs merged.")).text)
        assertEquals("Done", line(run(Run.DONE)).text)
        assertEquals("Nothing new", line(run(Run.DONE, quiet = true)).text)
        assertEquals("Ran late: the Mac was asleep at 9:00", line(run(Run.DONE, late = 42 * 60_000L)).late)
        assertEquals("Ran late: Hypernova wasn't running at 9:00", line(run(Run.DONE, late = 42 * 60_000L, lateWhy = "down")).late)
        assertEquals("Missed while the Mac was asleep at 9:00", line(run(Run.SKIPPED, "missed", missed = 1)).text)
        assertEquals("Missed 3 times while the Mac was asleep", line(run(Run.SKIPPED, "missed", missed = 3)).text)
        assertEquals("Skipped for the usage limit, until Mon 10:00", line(run(Run.SKIPPED, "limited", until = at(LocalDate.of(2026, 10, 19), 10, 0))).text)
        assertEquals("Skipped for the usage limit", line(run(Run.SKIPPED, "limited")).text)
        // A reset earlier this week reads by its day too.
        assertEquals("Skipped for the usage limit, until Wed 11:00", line(run(Run.SKIPPED, "limited", until = at(LocalDate.of(2026, 10, 14), 11, 0))).text)
        assertEquals("Skipped: OpenRouter is out of credits", line(run(Run.SKIPPED, "provider"), provider = "OpenRouter is out of credits").text)
        assertEquals("Skipped: the last run was still going", line(run(Run.SKIPPED, "overlap")).text)
        assertEquals("Skipped: Claude Code is signed out on the Mac", line(run(Run.SKIPPED, "signed_out")).text)
        assertEquals("Failed: the project folder is gone", line(run(Run.FAILED, "no_project")).text)
        assertEquals(RunTone.Failed, line(run(Run.FAILED, "no_project")).tone)
        assertEquals("Failed: API Error: 500", line(run(Run.FAILED, "error", result = "API Error: 500")).text)
        assertEquals("Stopped: something blocked its session", line(run(Run.FAILED, "blocked")).text)
        // A block that stopped a run once it had started: failed, by the block's own reason.
        assertEquals("Stopped: Claude Code signed out on the Mac", line(run(Run.FAILED, "signed_out")).text)
        // A run whose session Codex runs says Codex (as the Mac's WorkflowWords do).
        assertEquals("Stopped: Codex signed out on the Mac", RunWords.line(w, run(Run.FAILED, "signed_out"), now, zone, codex = true).text)
        assertEquals("Skipped: Codex is signed out on the Mac", RunWords.line(w, run(Run.SKIPPED, "signed_out"), now, zone, codex = true).text)
        assertEquals("Failed: Codex didn't start", RunWords.line(w, run(Run.FAILED, "start_failed"), now, zone, codex = true).text)
        assertEquals("Failed: Claude Code didn't start", line(run(Run.FAILED, "start_failed")).text)
        assertEquals("Failed: Codex isn't set up on the Mac", RunWords.line(w, run(Run.FAILED, "no_provider"), now, zone, codex = true).text)
        assertEquals("Failed: that provider was removed", line(run(Run.FAILED, "no_provider")).text)
        assertEquals(RunTone.Failed, line(run(Run.FAILED, "signed_out")).tone)
        assertEquals("Stopped: OpenRouter is out of credits", line(run(Run.FAILED, "provider"), provider = "OpenRouter is out of credits").text)
        assertEquals("Stopped at the usage limit", line(run(Run.FAILED, "limited")).text)
        assertEquals("Stopped: out of usage credits", line(run(Run.FAILED, "no_credit")).text)
        // The reasons the Mac added.
        assertEquals("Failed: its folder is outside your projects folder", line(run(Run.FAILED, "outside_projects")).text)
        assertEquals("Failed: its model isn't offered any more", line(run(Run.FAILED, "no_model")).text)
        assertEquals("Skipped: you paused automations", line(run(Run.SKIPPED, "paused")).text)
        assertEquals("Stopped: the Mac restarted mid-run", line(run(Run.STOPPED, "mac_restart")).text)
        assertEquals(RunTone.Stopped, line(run(Run.STOPPED, "ended")).tone)
        assertEquals("Stopped: its session ended mid-run", line(run(Run.STOPPED, "ended")).text)
        // A retry says so under it.
        assertEquals("A retry", line(run(Run.DONE, result = "Tests pass.").copy(retryOf = "ru_0")).late)
        assertEquals("A retry", line(run(Run.FAILED, "error", result = "API Error: 500").copy(retryOf = "ru_0")).late)
        assertNull(line(run(Run.DONE)).late)
        // Run now's and Retry's toast: what the run does.
        assertEquals("Started", RunWords.started(w, run(Run.RUNNING), now, zone))
        assertEquals("In line: it starts when one of the two runs at work ends", RunWords.started(w, run(Run.QUEUED), now, zone))
        assertEquals("Waiting out your limit until 14:00", RunWords.started(w, run(Run.WAITING, until = at(LocalDate.of(2026, 10, 16), 14, 0)), now, zone))
        assertEquals("Your usage limit is reached: try again after 14:00", RunWords.limitedUntil(w, at(LocalDate.of(2026, 10, 16), 14, 0), now, zone))
        assertEquals("Stopped after 10 minutes without an answer", line(run(Run.STOPPED, "away")).text)
        assertEquals("Stopped by you", line(run(Run.STOPPED, "by_user")).text)
        assertEquals("Skipped", line(run(Run.SKIPPED, "something_new")).text)
        // Every line reads as a sentence: no "·"-joined fields, whatever the reason.
        val reasons = listOf("missed", "overlap", "queue_full", "stale", "daily_cap", "limited", "signed_out", "no_plan", "on_hold", "no_credit", "provider", "no_project", "no_session", "no_provider", "target_busy", "loop", "start_failed", "error", "blocked", "away", "timeout", "by_user", "outside_projects", "no_model", "paused", "mac_restart", "ended")
        for (r in reasons) for (st in listOf(Run.SKIPPED, Run.FAILED, Run.STOPPED)) {
            val l = line(run(st, r, result = "API Error: 500", missed = 2), provider = "OpenRouter is out of credits")
            assertTrue("$r: ${l.text}", '·' !in l.text && '·' !in (l.late ?: ""))
        }
    }

    @Test fun clockWords() {
        val today = LocalDate.of(2026, 10, 16)
        assertEquals("Today 9:00", RunWords.at(w, at(today, 9, 0), now, zone))
        assertEquals("Mon 9:42", RunWords.at(w, at(today.minusDays(4), 9, 42), now, zone))
        assertEquals("2 Oct 9:00", RunWords.at(w, at(today.minusDays(14), 9, 0), now, zone))
        assertEquals("Next 18:00", RunWords.next(w, at(today, 18, 0), now, zone))
        assertEquals("Next Mon 9:00", RunWords.next(w, at(today.plusDays(3), 9, 0), now, zone))
        assertEquals("Next 30 Oct 9:00", RunWords.next(w, at(today.plusDays(14), 9, 0), now, zone))
        assertEquals("45s", RunWords.duration(w, 45_000))
        assertEquals("2m 14s", RunWords.duration(w, 134_000))
        assertEquals("1h 03m", RunWords.duration(w, 63 * 60_000L))
    }

    @Test fun theWireFormatGoesBothWays() {
        val triggers = listOf(
            Trigger.Time("09:00", Trigger.WEEKDAYS), Trigger.Once(now + 60_000), Trigger.Every(2, "09:00", "19:00", Trigger.WEEKDAYS),
            Trigger.Every(4, "00:00", null, Trigger.ALL_DAYS), Trigger.Reset(Trigger.FIVE_HOUR, true), Trigger.Reset(Trigger.FIVE_HOUR, false, "00:00", "07:00"),
            Trigger.Limit(Trigger.SEVEN_DAY), Trigger.SessionEvent(Trigger.NEEDS_YOU, hyper, Trigger.ANY), Trigger.Plugged, Trigger.BatteryLow(15),
        )
        val actions = listOf(
            Action.Start(hyper, "/review", "anthropic", "opus", AutoMode.NO_ASKING, listOf("Bash(git log:*)"), Then.SAY),
            Action.Continue(Action.Continue.LIMITED, "", Then.NONE, "openrouter"), Action.Continue("s1", "Go on", Then.NOTIFY),
            Action.Notify("{week}", glasses = true), Action.Say("Hi"),
        )
        for (t in triggers) assertEquals(t, AutomationWire.trigger(AutomationWire.json(t)))
        for (a in actions) assertEquals(a, AutomationWire.action(AutomationWire.json(a)))
        val input = AutomationInput("Morning brief", true, false, triggers[0], actions[0], "brief", confirmBypass = true, confirmPaid = true)
        val json = AutomationWire.json(input)
        assertTrue(json.getBoolean("confirmBypass"))
        assertTrue(json.getBoolean("confirmPaid"))
        assertEquals(input.copy(confirmBypass = false, confirmPaid = false), AutomationWire.input(json))
        // "9:5" isn't a time; "9:05" is "09:05".
        assertEquals("09:05", AutomationWire.clock("9:05"))
        assertNull(AutomationWire.clock("9:5"))
    }

    @Test fun whatTheAppDoesntKnowIsKeptNotLost() {
        val t = AutomationWire.trigger(JSONObject("""{"kind":"moon","phase":"full"}"""))
        assertTrue(t is Trigger.Unknown)
        assertEquals("full", AutomationWire.json(t).getString("phase"))
        val a = AutomationWire.action(JSONObject("""{"kind":"teleport","to":"mars"}"""))
        assertTrue(a is Action.Unknown)
        assertEquals("mars", AutomationWire.json(a).getString("to"))
    }

    @Test fun readsTheSnapshotAndTheSocket() {
        val snap = JSONObject(
            """{"mac":{"tz":"Europe/Moscow"},"automations":{"paused":true,"running":1,"queued":2,"glasses":true,
               "notes":[{"id":"n1","automationId":"au_1","runId":"ru_1","name":"Morning brief","text":"3 PRs merged.","to":"phone","at":5}],
               "list":[{"id":"au_1","name":"Morning brief","enabled":true,"onlyOnce":false,"template":"brief","paid":false,"nextAt":10,"runCount":12,"problem":null,
                 "trigger":{"kind":"time","at":"09:00","days":[1,2,3,4,5]},
                 "action":{"kind":"start","projectPath":"/p","prompt":"Summarise","providerId":"anthropic","model":null,"permissionMode":"dontAsk","allowedTools":[],"then":"notify"},
                 "lastRun":{"id":"ru_1","automationId":"au_1","name":"Morning brief","fired":{"by":"schedule","at":1,"sessionId":null},"state":"done","reason":null,"until":null,
                   "sessionIds":["s1"],"startedAt":2,"endedAt":3,"late":0,"missed":0,"lateWhy":null,"result":"3 PRs merged.","quiet":false,"skippedTools":["Bash(npm test)"],"permissionMode":"dontAsk","depth":0}}]}}""",
        )
        val st = AutomationWire.fromSnapshot(snap)!!
        assertTrue(st.paused)
        assertEquals("Europe/Moscow", st.tz)
        assertEquals("not said by this Mac", null, st.hasBattery)
        assertEquals(false, AutomationWire.fromSnapshot(JSONObject("""{"automations":{"paused":false,"list":[],"hasBattery":false}}"""))!!.hasBattery)
        val a = st.list.single()
        assertEquals(Trigger.Time("09:00", Trigger.WEEKDAYS), a.trigger)
        assertEquals(AutoMode.ALLOWED, a.mode)
        assertEquals(listOf("Bash(npm test)"), a.lastRun!!.skippedTools)
        assertEquals("3 PRs merged.", st.notes.single().text)
        assertNull(AutomationWire.fromSnapshot(JSONObject("""{"mac":{}}""")))
        val m = AutomationWire.message(JSONObject("""{"t":"automations","paused":false,"running":0,"queued":0}"""))
        assertTrue(m is AutoMsg.Counts)
        assertTrue(AutomationWire.message(JSONObject("""{"t":"automation.removed","id":"au_1"}""")) is AutoMsg.Removed)
        assertNull(AutomationWire.message(JSONObject("""{"t":"session"}""")))
        assertEquals(SessionAutomation("au_1", "Morning brief", "ru_1"), AutomationWire.sessionAutomation(JSONObject("""{"id":"au_1","name":"Morning brief","runId":"ru_1"}""")))
    }

    @Test fun historyFoldsAnAutomationsSessions() {
        fun s(id: String, auto: String?) = Session("aa", id, id, "p", SessionState.Idle, "opus", "default", "", now, phase = Phase.Ended, automation = auto?.let { SessionAutomation(it, "Brief", null) })
        val rows = foldRuns(listOf(s("a", "au_1"), s("b", null), s("c", "au_1"), s("d", "au_2")))
        assertEquals(listOf("auto:au_1", "b", "auto:au_2"), rows.map { it.key })
        assertEquals(2, rows.first().count)
    }

    @Test fun allowRules() {
        assertNull(Rules.check("Bash(git log:*)"))
        assertNull(Rules.check("Read"))
        assertNull(Rules.check("mcp__github__get_issue"))
        assertEquals(Rules.BROAD, Rules.check("Bash"))
        assertEquals(Rules.BROAD, Rules.check("Bash(*)"))
        assertEquals(Rules.BAD, Rules.check("Bash("))
        assertEquals(Rules.BAD, Rules.check("rm -rf"))
        // As the Mac checks them (checkRule): every way of saying "everything" is refused.
        assertEquals(Rules.BROAD, Rules.check("Edit"))
        assertEquals(Rules.BROAD, Rules.check("Edit(**)"))
        assertEquals(Rules.BROAD, Rules.check("Write(**/*)"))
        assertEquals(Rules.BROAD, Rules.check("Bash(:*)"))
        assertEquals(Rules.BROAD, Rules.check("Bash(sh:*)"))
        assertEquals(Rules.BROAD, Rules.check("Bash(sh -c:*)"))
        assertEquals(Rules.BROAD, Rules.check("Bash(python3:*)"))
        assertEquals(Rules.BROAD, Rules.check("Bash(bash -x *)"))
        assertNull(Rules.check("Edit(src/**)"))
        assertNull(Rules.check("Bash(npm test:*)"))
        assertNull("a tool that isn't broad, whole", Rules.check("WebFetch"))
        assertEquals("not a tool's name", Rules.BAD, Rules.check("Bash.x(ls)"))
        assertEquals("an empty spec", Rules.BAD, Rules.check("Bash( )"))
        assertEquals(Rules.BAD, Rules.check("Bash(ls\nrm)"))
        // Kept in the modes that ask or edit too; Auto and No asking keep none.
        assertTrue(Rules.kept(AutoMode.ALLOWED) && Rules.kept(AutoMode.ASK) && Rules.kept(AutoMode.EDITS))
        assertTrue(!Rules.kept(AutoMode.AUTO) && !Rules.kept(AutoMode.NO_ASKING))
    }

    @Test fun limitTemplatesLead() {
        val today = listOf("carry_on", "reset", "switch_provider", "night_shift", "weekly_debrief", "brief", "prs", "plugged", "battery")
        assertEquals(today, Templates.ALL.map { it.id }.take(today.size))
        // A Mac without workflows is offered exactly today's.
        assertEquals(today, Templates.offered(emptySet()).map { it.id })
        assertTrue(Templates.byId("switch_provider")!!.costs)
    }
}

/**
 * [Words] from the app's own strings file, as the phone would say them (resource escapes undone,
 * quoted values keeping their spaces), with the ids from the generated R class.
 */
class XmlWords(override val h24: Boolean = true, override val locale: Locale = Locale.UK) : Words {
    override fun s(id: Int, vararg args: Any): String = String.format(locale, strings.getValue(names.getValue(id)), *args)
    override fun p(id: Int, n: Int, vararg args: Any): String {
        val forms = plurals.getValue(names.getValue(id))
        return String.format(locale, (if (n == 1) forms["one"] else null) ?: forms.getValue("other"), *args)
    }
    override fun arr(id: Int): List<String> = arrays.getValue(names.getValue(id))

    companion object {
        private fun file(name: String) = listOf("src/main/res/values/$name", "app/src/main/res/values/$name").map(::File).first { it.exists() }
        /** The automations' strings files (and the workflows'), as the phone has them. */
        private val docs = listOf("strings_automations.xml", "strings_automations2.xml", "strings_workflows.xml").map { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(it)) }
        private fun Element.each(tag: String): List<Element> = getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }
        private fun text(raw: String): String {
            val quoted = raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")
            val t = if (quoted) raw.substring(1, raw.length - 1) else raw.trim().replace(Regex("\\s+"), " ")
            return t.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
        val strings: Map<String, String> = docs.flatMap { d -> d.documentElement.each("string").map { it.getAttribute("name") to text(it.textContent) } }.toMap()
        val plurals: Map<String, Map<String, String>> = docs.flatMap { d -> d.documentElement.each("plurals").map { p -> p.getAttribute("name") to p.each("item").associate { it.getAttribute("quantity") to text(it.textContent) } } }.toMap()
        val arrays: Map<String, List<String>> = docs.flatMap { d -> d.documentElement.each("string-array").map { a -> a.getAttribute("name") to a.each("item").map { text(it.textContent) } } }.toMap()
        private fun ids(c: Class<*>) = c.fields.associate { it.getInt(null) to it.name }
        val names: Map<Int, String> = ids(R.string::class.java) + ids(R.plurals::class.java) + ids(R.array::class.java)
    }
}
