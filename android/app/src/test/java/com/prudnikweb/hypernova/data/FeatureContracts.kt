package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * The contract fixtures of Automations, Codex, Read aloud and Files (docs/FEATURES.md, sections 10,
 * 11, 13 and 14; daemon/test/contract.test.ts, "Automations, Codex, read aloud, files"), each run
 * through the parser the app's own code calls for it. [ContractTest] adds these to its own checks.
 */
object FeatureContracts {
    private const val T = 1_800_000_000_000L
    private const val MAIN = "c0ffee00-0000-4000-8000-00000000c0de"
    private const val AU = "au_0000000001"
    private const val RU = "ru_0000000001"

    private fun ws(o: JSONObject, t: String): JSONObject = o.also { assertEquals(t, it.str("t")) }

    /** The workflow the daemon's test made: ask "Deploy now?", then notify "Deploying." when the answer is Deploy. */
    private fun deployCheck(a: Automation) {
        assertEquals(AU, a.id)
        assertEquals("Deploy check", a.name)
        assertEquals(Trigger.Time("09:00", listOf(1, 2, 3, 4, 5)), a.trigger)
        assertEquals(T, a.nextAt)
        // A daemon with workflows: the steps are what the editor shows; the action only says how many.
        assertEquals(Action.Unknown::class, a.action::class)
        val steps = a.steps!!
        assertEquals(listOf("st_q", "st_n"), steps.map { it.id })
        assertEquals(Action.Ask("Deploy now?", listOf("Deploy", "Not now"), "Not now", 10), steps[0].action)
        assertNull(steps[0].guard)
        assertEquals(Action.Notify("Deploying."), steps[1].action)
        assertEquals(Guard("answer", "Deploy"), steps[1].guard)
        assertFalse(a.paid)
        assertNull(a.problem)
    }

    private fun ask(r: RunAsk?) = assertEquals(RunAsk("st_q", "Deploy now?", listOf("Deploy", "Not now"), "Not now", T), r)

    /** A run of it that asks: needs you, the ask step at work, the notify still to come. */
    private fun asking(r: Run) {
        assertEquals(AU, r.automationId)
        assertEquals("manual", r.firedBy)
        assertEquals(Run.NEEDS_YOU, r.state)
        assertTrue(r.working)
        assertEquals(0, r.step)
        ask(r.ask)
        assertEquals(listOf("st_q" to Run.NEEDS_YOU, "st_n" to RunStep.PENDING), r.steps.map { it.id to it.state })
    }

    /** The run answered Deploy from the phone: both steps done, the answer kept with who gave it. */
    private fun answered(r: Run) {
        assertEquals(RU, r.id)
        assertEquals(Run.DONE, r.state)
        assertNull(r.ask)
        assertFalse(r.retriable)
        assertEquals("Deploy", r.result)
        assertEquals(StepAnswer("Deploy", "phone", T), r.steps[0].answer)
        assertEquals("Deploying.", r.steps[1].result)
        assertEquals(1, r.step)
    }

    private fun run(o: JSONObject?): Run = AutomationWire.run(o!!)!!

    val checks: Map<String, (JSONObject) -> Unit> = mapOf(
        // ── Automations (LiveAutomations, data/AutomationsLive.kt) ──
        // save: the automation is under `automation`.
        "rest-automation" to { o -> deployCheck(AutomationWire.automation(o.obj("automation")!!)!!) },
        "rest-automation-enabled" to { o ->
            val a = AutomationWire.automation(o.obj("automation")!!)!!
            assertEquals(AU, a.id)
            assertFalse(a.enabled)
        },
        "rest-automation-run" to { o -> asking(run(o.obj("run"))) },
        "rest-automation-answer" to { o -> answered(run(o.obj("run"))) },
        // Someone answered first: LiveAutomations.error keeps the run that came with the 409.
        "rest-automation-not-asking" to { o ->
            val e = o.obj("error")!!
            assertEquals("not_asking", e.str("code"))
            assertTrue(e.str("message")!!.isNotBlank())
            answered(run(e.obj("run")))
        },
        "rest-automation-runs" to { o ->
            val runs = o.arr("runs").mapObjects(AutomationWire::run)
            answered(runs.single())
            assertEquals(1, o.int("total"))
            assertEquals(0, o.int("skipped"))
        },
        "rest-automation-stop" to { o ->
            val r = run(o.obj("run"))
            assertEquals(Run.STOPPED, r.state)
            assertTrue(r.retriable)
            assertNull(r.ask)
        },
        "rest-automation-retry" to { o ->
            val r = run(o.obj("run"))
            assertEquals(Run.NEEDS_YOU, r.state)
            assertEquals("ru_0000000002", r.retryOf)
            assertEquals("st_q", r.retryFrom)
            ask(r.ask)
        },
        "rest-automations-paused" to { o -> assertTrue(o.has("paused") && o.bool("paused")) },
        "rest-automations-check" to { o ->
            assertEquals(Trigger.Time("09:00", listOf(1, 2, 3, 4, 5)), AutomationWire.trigger(o.obj("trigger")))
            assertEquals(3, o.arr("next")!!.length())
            assertEquals(T, o.arr("next")!!.optLong(0))
        },
        // The socket's (AutomationWire.message, as LiveHub hands them over).
        "ws-automation" to { o ->
            val m = AutomationWire.message(ws(o, "automation")) as AutoMsg.Changed
            deployCheck(m.a)
            assertTrue(m.a.enabled)
        },
        "ws-automation-removed" to { o -> assertEquals(AU, (AutomationWire.message(ws(o, "automation.removed")) as AutoMsg.Removed).id) },
        "ws-automations" to { o -> assertTrue((AutomationWire.message(ws(o, "automations")) as AutoMsg.Counts).paused) },
        "ws-run" to { o -> answered((AutomationWire.message(ws(o, "run")) as AutoMsg.RunChanged).run) },
        "ws-note" to { o ->
            val n = (AutomationWire.message(ws(o, "note")) as AutoMsg.Note).note
            assertEquals(AutomationNote("no_0000000001", AU, RU, "Deploy check", "Deploy now?", AutomationNote.PHONE, T, n.ask), n)
            ask(n.ask)
        },
        // ── Codex (LiveHub.setCodexEnabled, codexModels, setCodexModel, switchPreview, switchAgent) ──
        "rest-codex" to { o ->
            val c = CodexWire.state(o.obj("codex"))!!
            assertTrue(c.available)
            assertTrue(c.enabled)
            assertTrue(c.canSwitchTo)
            assertEquals("0.163.0", c.version)
            assertEquals("0.163.0", c.min)
            assertNull(c.problem)
        },
        "rest-codex-models" to { o ->
            val m = CodexWire.models(o)
            assertEquals(listOf(CodexModel("gpt-test", "GPT-Test", "The default", listOf("low", "high"), "low", true), CodexModel("gpt-other", "GPT-Other", null, listOf("medium"), "medium", false)), m)
            assertEquals("gpt-test", m.pick(null)?.value)
        },
        "rest-codex-session-model" to { o ->
            assertEquals("gpt-other", o.str("model"))
            assertEquals("now", o.str("applies"))
            val s = Wire.session(o.obj("session")!!)
            assertEquals(AgentKind.CODEX, s.agent)
            assertEquals("gpt-other", s.model)
        },
        "rest-switch-preview" to { o ->
            assertEquals(SwitchPreview("User: [Hypernova test] make notes.txt\nCodex: Made notes.txt", 2, 59, 96 * 1024), CodexWire.preview(o))
        },
        // switchAgent's SwitchRefused: the code, and the most it takes.
        "rest-switch-too-large" to { o ->
            val e = o.obj("error")!!
            assertEquals("context_too_large", e.str("code"))
            assertEquals(96 * 1024, e.int("maxBytes"))
        },
        // …and where it went on.
        "rest-switch-switched" to { o ->
            val e = o.obj("error")!!
            assertEquals("switched", e.str("code"))
            assertEquals("00000000-0000-4000-8000-000000000001", e.str("to"))
        },
        "rest-switch" to { o ->
            val s = Wire.session(o.obj("session")!!)
            assertEquals(AgentKind.CLAUDE, s.agent)
            assertEquals("00000000-0000-4000-8000-000000000002", s.switchedFrom)
            assertEquals("idle", s.state)
        },
        // ── Read aloud (PhoneSpeech, data/SpeechLink.kt; Speaker, data/SpeechPlayer.kt) ──
        "rest-speech-settings" to { o -> assertEquals(SpeechOut(null, 1.15f), SpeechOut.parse(o.obj("speech"))) },
        "ws-speech" to { o -> assertEquals(SpeechOut(null, 1.15f), SpeechOut.parse(ws(o, "speech").obj("speech"))) },
        // A Linux server has none of the Mac's voices.
        "rest-speech-voices" to { o -> assertEquals(emptyList<SpeechVoice>(), SpeechVoice.list(o)) },
        "rest-speech-play" to { o -> assertEquals(PlayInfo("play-0123456789", MAIN, "phone", "mac", 24_000, 23), PlayInfo.parse(o.obj("play"))) },
        "ws-speech-stop" to { o -> assertEquals("play-0123456789", ws(o, "speech.stop").str("id")) },
        // From the phone: the very message progressMessage writes.
        "ws-speech-progress" to { o ->
            val mine = JSONObject(progressMessage("play-0123456789", PlayState.Started, 0, 0.42f))
            assertEquals(o.keys().asSequence().toSortedSet(), mine.keys().asSequence().toSortedSet())
            for (k in o.keys()) assertEquals(k, o.get(k).toString(), mine.get(k).toString())
        },
        // ── Files (LiveHub.files) ──
        "rest-files" to { o ->
            val e = o.arr("entries").mapObjects(Wire::entry)
            assertEquals("/home/me/Projects/files-demo", o.str("path"))
            assertEquals(listOf("src", ".env", "notes.txt", "README.md"), e.map { it.name })
            assertEquals(listOf(true, false, false, false), e.map { it.isDir })
            assertEquals(FileEntry.LOCKED_SECRET, e[1].locked)
            assertNull(e[2].locked)
            assertEquals(8L, e[2].size)
            assertEquals(T, e[2].mtime)
            assertEquals("text/plain; charset=utf-8", e[2].mime)
            assertFalse(o.optBoolean("truncated"))
            assertEquals(4, o.int("total"))
        },
    )
}
