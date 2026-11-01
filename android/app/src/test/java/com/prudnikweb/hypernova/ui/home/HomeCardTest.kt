package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.TermLink
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.Wire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Home card answers its session's approval by the widget's rule (Allow only beside the exact
 * command, whole), and its long-press menu offers what the Mac can do (docs/PROTOCOL.md, "Session menu").
 */
class HomeCardTest {
    private fun ask(kind: String, tool: String, input: String) = Wire.approval(
        JSONObject("""{"id":"ap1","sessionId":"s1","kind":"$kind","toolName":"$tool","summary":"$tool: …","input":$input}"""),
    )

    private fun session(state: SessionState, phase: Phase, job: String?, agent: String = AgentKind.CLAUDE, running: Boolean? = null) =
        Session("s1", "s1", "t", "alpha", state, "opus", "default", "", 0L, phase = phase, agentJob = job, agent = agent, agentRunning = running)

    @Test fun aWholeCommandCanBeAllowedOnTheCard() {
        assertEquals(CardAsk.Exact("ap1", "Bash", "git push origin main"), CardAsk.of(ask("tool", "Bash", """{"command":"git push origin main"}""")))
        assertEquals(CardAsk.Exact("ap1", "Read", "/p/a.kt"), CardAsk.of(ask("tool", "Read", """{"file_path":"/p/a.kt"}""")))
    }

    @Test fun noBlindAllow() {
        // Too long to show whole, a secret that would be masked, an edit (only its path shows).
        val long = "echo " + "x".repeat(500)
        assertEquals(CardAsk.Partial("ap1"), CardAsk.of(ask("tool", "Bash", """{"command":"$long"}""")))
        assertEquals(CardAsk.Partial("ap1"), CardAsk.of(ask("tool", "Bash", """{"command":"curl -H 'Authorization: Bearer sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789'"}""")))
        assertEquals(CardAsk.Partial("ap1"), CardAsk.of(ask("tool", "Edit", """{"file_path":"/p/a.kt","old_string":"a","new_string":"b"}""")))
    }

    @Test fun questionsAndPlansOpenTheSession() {
        assertEquals(CardAsk.Open("ap1"), CardAsk.of(ask("question", "AskUserQuestion", "{}")))
        assertEquals(CardAsk.Open("ap1"), CardAsk.of(ask("plan", "ExitPlanMode", "{}")))
        assertNull(CardAsk.of(null))
    }

    @Test fun theMenuOffersWhatTheMacCanDo() {
        // A claude agents job at work: all of them.
        assertEquals(MenuOffer(rename = true, stop = true, delete = true, restart = true), MenuOffer.of(session(SessionState.Working, Phase.Working, "j1")))
        assertTrue(MenuOffer.of(session(SessionState.NeedsYou, Phase.NeedsYou, "j1")).stop)
        // Idle with its process still running: Stop too (owner, 2026-10-30), and the rest.
        assertEquals(MenuOffer(rename = true, stop = true, delete = true, restart = true), MenuOffer.of(session(SessionState.Idle, Phase.Idle, "j1", running = true)))
        // Done or stopped already (no process): nothing to stop, still deleted from here.
        assertEquals(MenuOffer(rename = true, stop = false, delete = true), MenuOffer.of(session(SessionState.Idle, Phase.Idle, "j1", running = false)))
        // An older Mac doesn't say: idle offers no Stop, as before.
        assertFalse(MenuOffer.of(session(SessionState.Idle, Phase.Idle, "j1")).stop)
        // Ended: never, whatever it says.
        assertFalse(MenuOffer.of(session(SessionState.Idle, Phase.Ended, "j1", running = true)).stop)
        // Not a claude agents job, at work: only a rename.
        assertEquals(MenuOffer(rename = true, stop = false, delete = false), MenuOffer.of(session(SessionState.Working, Phase.Working, null)))
        assertEquals(MenuOffer(rename = true, stop = false, delete = false), MenuOffer.of(session(SessionState.NeedsYou, Phase.NeedsYou, null)))
        // At rest: Delete too, nothing to stop (owner, 2026-11-01: he got only Rename).
        assertEquals(MenuOffer(rename = true, stop = false, delete = true), MenuOffer.of(session(SessionState.Idle, Phase.Idle, null)))
        assertEquals(MenuOffer(rename = true, stop = false, delete = true), MenuOffer.of(session(SessionState.Idle, Phase.Ended, null)))
    }

    @Test fun deleteSaysWhatItWillDoForEveryKindOfSession() {
        fun plain(state: SessionState, phase: Phase, origin: String?, term: TermLink? = null, agents: Int = 0) =
            session(state, phase, null).copy(origin = origin, term = term, agentsRunning = agents)
        val link = TermLink("t1", mac = 1, phone = 0)
        // The card in the owner's report: idle in a shared terminal, no job.
        assertEquals(DeleteKind.Terminal, DeleteKind.of(plain(SessionState.Idle, Phase.Idle, "terminal", link)))
        assertEquals(DeleteKind.Terminal, DeleteKind.of(plain(SessionState.Idle, Phase.Idle, "hypernova", link)))
        // Idle in a terminal Hypernova doesn't hold: offered, and it says why it can't.
        assertEquals(DeleteKind.OpenElsewhere, DeleteKind.of(plain(SessionState.Idle, Phase.Idle, "terminal")))
        // Nothing runs: a phone's session at rest, one ended, one a restart cut short, one that failed.
        assertEquals(DeleteKind.Forget, DeleteKind.of(plain(SessionState.Idle, Phase.Idle, "hypernova")))
        assertEquals(DeleteKind.Forget, DeleteKind.of(plain(SessionState.Idle, Phase.Ended, "terminal")))
        assertEquals(DeleteKind.Forget, DeleteKind.of(plain(SessionState.Idle, Phase.Ended, "hypernova")))
        assertEquals(DeleteKind.Forget, DeleteKind.of(plain(SessionState.Idle, Phase.Error, "hypernova")))
        // At work, however it shows it: none.
        assertEquals(DeleteKind.None, DeleteKind.of(plain(SessionState.Working, Phase.Working, "terminal", link)))
        assertEquals(DeleteKind.None, DeleteKind.of(plain(SessionState.NeedsYou, Phase.NeedsYou, "terminal", link)))
        assertEquals(DeleteKind.None, DeleteKind.of(plain(SessionState.Idle, Phase.Idle, "terminal", link, agents = 2)))
        // A job, at rest or not, whose process runs or doesn't; ended, it can't be running.
        assertEquals(DeleteKind.RunningJob, DeleteKind.of(session(SessionState.Idle, Phase.Idle, "j1", running = true)))
        assertEquals(DeleteKind.RunningJob, DeleteKind.of(session(SessionState.Working, Phase.Working, "j1", running = true)))
        assertEquals(DeleteKind.Job, DeleteKind.of(session(SessionState.Idle, Phase.Idle, "j1", running = false)))
        assertEquals(DeleteKind.Job, DeleteKind.of(session(SessionState.Idle, Phase.Idle, "j1")))
        assertEquals(DeleteKind.Job, DeleteKind.of(session(SessionState.Idle, Phase.Ended, "j1", running = true)))
        // Codex's own, whatever else it has.
        assertEquals(DeleteKind.Codex, DeleteKind.of(session(SessionState.Working, Phase.Working, null, AgentKind.CODEX)))
        assertEquals(DeleteKind.Codex, DeleteKind.of(session(SessionState.Idle, Phase.Idle, null, AgentKind.CODEX).copy(term = link)))
    }

    @Test fun theMenuOffersRenameStopAndDeleteForCodex() {
        fun codex(state: SessionState, phase: Phase) = session(state, phase, null, AgentKind.CODEX)
        // At work or waiting on the owner: Stop (its turn's interrupt) with the rest.
        assertEquals(MenuOffer(rename = true, stop = true, delete = true), MenuOffer.of(codex(SessionState.Working, Phase.Working)))
        assertTrue(MenuOffer.of(codex(SessionState.NeedsYou, Phase.NeedsYou)).stop)
        // At rest or ended: nothing to stop; renamed and deleted all the same.
        assertEquals(MenuOffer(rename = true, stop = false, delete = true), MenuOffer.of(codex(SessionState.Idle, Phase.Idle)))
        assertEquals(MenuOffer(rename = true, stop = false, delete = true), MenuOffer.of(codex(SessionState.Idle, Phase.Ended)))
        // Never Restart: that's claude agents' own.
        assertFalse(MenuOffer.of(codex(SessionState.Working, Phase.Working)).restart)
        // The menu's Delete is for good: a Completed Codex card doesn't swipe to it.
        assertNull(CardSwipe.of(HomeGroup.Completed, codex(SessionState.Idle, Phase.Idle)))
        assertEquals(CardSwipe.Delete, CardSwipe.of(HomeGroup.Completed, session(SessionState.Idle, Phase.Idle, "j1", running = false)))
    }

    @Test fun readsTheJob() {
        fun wire(extra: String) = Wire.session(JSONObject("""{"id":"s1","title":"t","cwd":"/p/alpha","state":"idle"$extra}"""))
        assertEquals("j1", wire(""","agentJob":"j1"""").agentJob)
        assertNull(wire("").agentJob)
        assertNull(wire(""","agentJob":""""").agentJob)
        assertEquals(true, wire(""","agentJob":"j1","agentRunning":true""").agentRunning)
        assertEquals(false, wire(""","agentJob":"j1","agentRunning":false""").agentRunning)
        assertNull(wire(""","agentJob":"j1"""").agentRunning)
    }
}
