package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.AgentStarting
import com.prudnikweb.hypernova.data.WEvent
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A session's agents (docs/PROTOCOL.md, "Agents and workflows"): the board `GET …/agents` fills as the
 * session opens and the socket keeps current, and one agent's transcript as its screen asks for it
 * (`agentEvents`): whole at first, then only what changed since its cursor while it runs, whole again
 * once it has ended, and nothing kept while it has only just started.
 */
@RunWith(RobolectricTestRunner::class)
class AgentsLiveTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    private companion object {
        const val MAIN = "c0ffee00-0000-4000-8000-00000000c0de"
        const val PLAIN = "a0123456789abcdef"
        const val SURVEYOR = "a2222222222222222"
        const val RUN = "wf_0a1b2c3d-4e5"
        const val EVENTS = "/v1/sessions/$MAIN/agents/$PLAIN/events"
    }

    @After fun tearDown() = rig.close()

    /** The main session of the fixtures, opened: its history, and its board from rest-agents.json. */
    private fun open(agents: JSONObject = Fixtures.json("rest-agents")) {
        daemon.snapshot = snapshot(sessions = listOf(session(MAIN, state = "working")))
        rig.connect()
        daemon.ok("GET", "/v1/sessions/$MAIN/events", JSONObject().put("events", JSONArray()))
        daemon.ok("GET", "/v1/sessions/$MAIN/agents", agents)
        rig.hub.opened(MAIN)
        rig.until("the board") { !rig.hub.agents(MAIN).isEmpty() }
    }

    private fun event(id: String, text: String) = JSONObject().put("id", id).put("kind", "assistant").put("ts", 5_000L).put("text", text)

    @Test fun theBoardComesWithTheSessionAndTheSocketKeepsItCurrent() {
        open()
        val board = rig.hub.agents(MAIN)
        assertEquals(setOf(PLAIN, SURVEYOR), board.agents.keys)
        assertTrue(board.agents.getValue(PLAIN).running)
        assertEquals(RUN, board.agents.getValue(SURVEYOR).workflowId)
        assertEquals(setOf(RUN), board.workflows.keys)
        assertEquals(PLAIN, board.byCall["toolu_agent"]?.id)
        assertEquals(1, daemon.count("GET", "/v1/sessions/$MAIN/agents"))

        // {t:"agent"}: the agent moves on, then ends.
        val moved = Fixtures.json("ws-agent")
        moved.getJSONObject("agent").put("activity", "Edit: parser.ts").put("lastActivityAt", 1_900_000_000_000L)
        rig.push(moved) { rig.hub.agents(MAIN).agents.getValue(PLAIN).activity == "Edit: parser.ts" }
        moved.getJSONObject("agent").put("status", "done")
        rig.push(moved) { !rig.hub.agents(MAIN).agents.getValue(PLAIN).running }
        // {t:"workflow"}: its tally.
        val wf = Fixtures.json("ws-workflow")
        wf.getJSONObject("workflow").put("done", 2)
        rig.push(wf) { rig.hub.agents(MAIN).workflows.getValue(RUN).done == 2 }
        // Superseded (its transcript went on as another's): off the board.
        moved.getJSONObject("agent").put("superseded", true)
        rig.push(moved) { PLAIN !in rig.hub.agents(MAIN).agents }
    }

    @Test fun anOlderMacWithoutTheRouteLeavesTheBoardEmpty() {
        daemon.snapshot = snapshot(sessions = listOf(session(MAIN)))
        rig.connect()
        daemon.ok("GET", "/v1/sessions/$MAIN/events", JSONObject().put("events", JSONArray()))
        rig.hub.opened(MAIN)
        rig.until("asked") { daemon.count("GET", "/v1/sessions/$MAIN/agents") == 1 && !rig.hub.loadingTranscript(MAIN) }
        rig.sync()
        assertTrue(rig.hub.agents(MAIN).isEmpty())
    }

    @Test fun whileItRunsOnlyWhatChangedSinceTheCursorComesAndIsMergedIn() {
        open()
        daemon.ok("GET", EVENTS, Fixtures.json("rest-agent-events"))
        val first = rig.call { rig.hub.agentEvents(MAIN, PLAIN) }.getOrThrow()
        assertEquals("limit=300", daemon.calls("GET", EVENTS).last().query)
        assertEquals(listOf(WEvent.User::class, WEvent.Assistant::class, WEvent.Tool::class), first.map { it::class })

        // The next ask carries the cursor; the answer is only a changed reply and a new line.
        val changedId = first[1].id
        daemon.ok("GET", EVENTS, JSONObject().put("events", JSONArray().put(event(changedId, "Reading it again.")).put(event("ev-new", "Found it."))).put("cursor", "cursor-2").put("full", false))
        val second = rig.call { rig.hub.agentEvents(MAIN, PLAIN) }.getOrThrow()
        assertEquals("limit=300&after=cursor", daemon.calls("GET", EVENTS).last().query)
        assertEquals(first.map { it.id } + "ev-new", second.map { it.id })
        assertEquals("Reading it again.", (second[1] as WEvent.Assistant).text)
        assertSame("the task is kept as it was", first[0], second[0])

        // Nothing changed: the same list comes back, so the screen doesn't rebuild.
        daemon.ok("GET", EVENTS, JSONObject().put("events", JSONArray()).put("cursor", "cursor-3").put("full", false))
        val third = rig.call { rig.hub.agentEvents(MAIN, PLAIN) }.getOrThrow()
        assertEquals("limit=300&after=cursor-2", daemon.calls("GET", EVENTS).last().query)
        assertSame(second, third)
    }

    @Test fun onceItHasEndedTheWholeTranscriptIsReadAgain() {
        open()
        daemon.ok("GET", EVENTS, Fixtures.json("rest-agent-events"))
        rig.call { rig.hub.agentEvents(MAIN, PLAIN) }
        val ended = Fixtures.json("ws-agent")
        ended.getJSONObject("agent").put("status", "done").put("lastActivityAt", 1_900_000_000_000L)
        rig.push(ended) { !rig.hub.agents(MAIN).agents.getValue(PLAIN).running }
        daemon.ok("GET", EVENTS, JSONObject().put("events", JSONArray().put(event("only", "The report."))).put("cursor", "c"))
        val whole = rig.call { rig.hub.agentEvents(MAIN, PLAIN) }.getOrThrow()
        assertEquals("no cursor: the whole of it", "limit=300", daemon.calls("GET", EVENTS).last().query)
        assertEquals(listOf("only"), whole.map { it.id })
    }

    @Test fun anOlderMacThatIgnoresTheCursorSendsItWholeAndThatReplacesWhatWasThere() {
        open()
        daemon.ok("GET", EVENTS, Fixtures.json("rest-agent-events"))
        rig.call { rig.hub.agentEvents(MAIN, PLAIN) }
        // No `full` in the answer: whole.
        daemon.ok("GET", EVENTS, JSONObject().put("events", JSONArray().put(event("e1", "Hi."))))
        assertEquals(listOf("e1"), rig.call { rig.hub.agentEvents(MAIN, PLAIN) }.getOrThrow().map { it.id })
    }

    @Test fun justStartedIsAgentStartingAndNothingIsKeptSoTheNextIsWhole() {
        open()
        daemon.ok("GET", EVENTS, Fixtures.json("rest-agent-events-starting"))
        assertTrue(rig.call { rig.hub.agentEvents(MAIN, PLAIN) }.exceptionOrNull() is AgentStarting)
        daemon.ok("GET", EVENTS, Fixtures.json("rest-agent-events"))
        rig.call { rig.hub.agentEvents(MAIN, PLAIN) }
        assertEquals("limit=300", daemon.calls("GET", EVENTS).last().query)
        // Running with a cursor, then starting again (its file went): back to whole.
        daemon.ok("GET", EVENTS, Fixtures.json("rest-agent-events-starting"))
        assertTrue(rig.call { rig.hub.agentEvents(MAIN, PLAIN) }.exceptionOrNull() is AgentStarting)
        assertEquals("limit=300&after=cursor", daemon.calls("GET", EVENTS).last().query)
        daemon.ok("GET", EVENTS, Fixtures.json("rest-agent-events"))
        rig.call { rig.hub.agentEvents(MAIN, PLAIN) }
        assertEquals("limit=300", daemon.calls("GET", EVENTS).last().query)
    }

    @Test fun aFailureIsSaidAndAnAgentGoneIsTheMacsWords() {
        open()
        daemon.on("GET", EVENTS) { FakeDaemon.refusal(404, "no_agent", "That agent isn’t in this session any more.") }
        assertEquals("That agent isn’t in this session any more.", rig.call { rig.hub.agentEvents(MAIN, PLAIN) }.exceptionOrNull()!!.message)
        assertFalse(rig.hub.agents(MAIN).isEmpty())
        // The phone ignores fields it doesn't know.
        daemon.on("GET", EVENTS) { json(200, Fixtures.json("rest-agent-events").put("brandNew", 1)) }
        assertTrue(rig.call { rig.hub.agentEvents(MAIN, PLAIN) }.isSuccess)
    }
}
