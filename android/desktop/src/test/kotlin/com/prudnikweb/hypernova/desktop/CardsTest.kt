package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TerminalAsk
import com.prudnikweb.hypernova.data.TodoNow
import com.prudnikweb.hypernova.data.Wire
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** What a card says and offers, by the phone's rule (HomeCardTest.noBlindAllow, WidgetModelTest). */
class CardsTest {
    private fun ask(kind: String, tool: String, input: String) = Wire.approval(
        JSONObject("""{"id":"ap1","sessionId":"s1","kind":"$kind","toolName":"$tool","summary":"$tool: …","input":$input}"""),
    )

    private fun session(state: SessionState, name: String = "t", id: String = "s1", agents: Int = 0, phase: Phase = Phase.of(state), todo: TodoNow? = null, activity: String = "") =
        Session(id, id, name, "alpha", state, "opus", "default", activity, 0L, phase = phase, agentsRunning = agents, todo = todo)

    @Test fun aWholeCommandCanBeAllowed() {
        assertEquals(CardOffer.Whole("ap1", "Bash", "git push origin main"), CardOffer.of(ask("tool", "Bash", """{"command":"git push origin main"}""")))
        assertEquals(CardOffer.Whole("ap1", "Read", "/p/a.kt"), CardOffer.of(ask("tool", "Read", """{"file_path":"/p/a.kt"}""")))
        // Line breaks and all: it's shown as it will run.
        assertEquals(CardOffer.Whole("ap1", "Bash", "a\nb"), CardOffer.of(ask("tool", "Bash", """{"command":"a\nb"}""")))
    }

    @Test fun noBlindAllow() {
        // Too long to show whole, a secret that would be masked, an edit (only its path shows).
        val long = "echo " + "x".repeat(500)
        assertTrue(CardOffer.of(ask("tool", "Bash", """{"command":"$long"}""")) is CardOffer.NotWhole)
        assertTrue(CardOffer.of(ask("tool", "Edit", """{"file_path":"/p/a.kt","old_string":"a","new_string":"b"}""")) is CardOffer.NotWhole)
        // Characters that hide or reorder what follows.
        assertTrue(CardOffer.of(ask("tool", "Bash", """{"command":"rm -rf ‮/ safe"}""")) is CardOffer.NotWhole)
        assertTrue(CardOffer.of(ask("tool", "Bash", """{"command":"ls​"}""")) is CardOffer.NotWhole)
    }

    @Test fun whatsMaskedIsNeverShownAndNeverAllowed() {
        val offer = CardOffer.of(ask("tool", "Bash", """{"command":"curl -H 'Authorization: Bearer sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789' https://x"}"""))
        assertTrue(offer is CardOffer.NotWhole)
        val detail = (offer as CardOffer.NotWhole).detail
        assertFalse(detail, detail.contains("sk-ant"))
        assertTrue(detail, detail.contains("•••"))
    }

    @Test fun questionsAndPlansAreForThePhone() {
        assertEquals(CardOffer.OnPhone("ap1", plan = false), CardOffer.of(ask("question", "AskUserQuestion", "{}")))
        assertEquals(CardOffer.OnPhone("ap1", plan = true), CardOffer.of(ask("plan", "ExitPlanMode", "{}")))
        assertNull(CardOffer.of(null, session(SessionState.Idle)))
    }

    private fun terminal(command: String?) = TerminalAsk("m1", "Bash command", command, listOf("Do you want to proceed?"), allow = 0, deny = 2, extras = emptyList())

    @Test fun aTerminalsDialogGoesByTheSameRule() {
        assertEquals(CardOffer.Whole("m1", "Bash command", "npm test", terminal("npm test")), CardOffer.of(terminal("npm test")))
        val none = CardOffer.of(terminal(null))
        assertTrue(none is CardOffer.NotWhole)
        val long = CardOffer.of(terminal("x".repeat(500)))
        assertTrue(long is CardOffer.NotWhole)
        assertTrue((long as CardOffer.NotWhole).detail.length <= 400)
    }

    /** A hub that records what's asked of it: only its answering calls should ever be reached here. */
    private class Calls {
        val approvals = mutableListOf<Pair<String, Answer>>()
        val menus = mutableListOf<Int?>()
        val hub: Hub = Proxy.newProxyInstance(Hub::class.java.classLoader, arrayOf(Hub::class.java)) { _, m, args ->
            // A value class in its signature mangles the name: answerMenu-yxL6bBk.
            when (m.name.substringBefore('-')) {
                "answerApproval" -> { approvals += (args[0] as String) to (args[1] as Answer); true }
                // A suspend Result<Unit> comes back as its value.
                "answerMenu" -> { menus += args[2] as Int?; Unit }
                else -> error("unexpected ${m.name}")
            }
        } as Hub
    }

    @Test fun allowIsRefusedForWhatCantBeReadWhoeverAsks() = runBlocking {
        val c = Calls()
        assertFalse(answerCard(c.hub, "s1", CardOffer.NotWhole("ap1", "Bash", "…"), allow = true) { })
        assertFalse(answerCard(c.hub, "s1", CardOffer.OnPhone("ap1", plan = false), allow = true) { })
        assertFalse(answerCard(c.hub, "s1", CardOffer.OnPhone("ap1", plan = false), allow = false) { })
        assertTrue("nothing went out", c.approvals.isEmpty())
    }

    @Test fun denyGoesForAnyToolAndAllowForAWholeCommand() = runBlocking {
        val c = Calls()
        assertTrue(answerCard(c.hub, "s1", CardOffer.NotWhole("a", "Bash", "…"), allow = false) { })
        assertTrue(answerCard(c.hub, "s1", CardOffer.Whole("b", "Bash", "ls"), allow = true) { })
        assertEquals(listOf("a" to Answer.Deny, "b" to Answer.Allow), c.approvals)
    }

    @Test fun aTerminalsDialogIsAnsweredThroughItsMenu() = runBlocking {
        val c = Calls()
        val whole = CardOffer.of(terminal("npm test"))
        assertTrue(answerCard(c.hub, "s1", whole, allow = true) { })
        assertTrue(answerCard(c.hub, "s1", whole, allow = false) { })
        assertEquals("its Yes, then its last No", listOf<Int?>(0, 2), c.menus)
        assertTrue("never the approval route", c.approvals.isEmpty())
        // No Allow for one that can't be read, and Deny still.
        val notWhole = CardOffer.of(terminal(null))
        assertFalse(answerCard(c.hub, "s1", notWhole, allow = true) { })
        assertEquals(listOf<Int?>(0, 2), c.menus)
        assertTrue(answerCard(c.hub, "s1", notWhole, allow = false) { })
        assertEquals(listOf<Int?>(0, 2, 2), c.menus)
    }

    @Test fun theGroupsAreThePhones() {
        assertEquals(Group.NeedsInput, groupOf(session(SessionState.NeedsYou)))
        assertEquals(Group.Working, groupOf(session(SessionState.Working)))
        assertEquals("its agents count, though its main agent is idle", Group.Working, groupOf(session(SessionState.Idle, agents = 2)))
        assertEquals(Group.Completed, groupOf(session(SessionState.Idle)))
        assertEquals(Group.Completed, groupOf(session(SessionState.Idle, phase = Phase.Ended)))
    }

    @Test fun groupsGoInOrderEmptyOnesOutAToZWithin() {
        val g = groupsOf(listOf(session(SessionState.Idle, "z", "1"), session(SessionState.Working, "b", "2"), session(SessionState.Working, "A", "3")))
        assertEquals(listOf(Group.Working, Group.Completed), g.map { it.first })
        assertEquals(listOf("A", "b"), g[0].second.map { it.name })
    }

    @Test fun aWorkingCardSaysItsTodoElseItsActivity() {
        assertEquals("Bash: npm test", doingLine(session(SessionState.Working, activity = "Bash: npm test")))
        assertEquals("Running the tests 3/7", doingLine(session(SessionState.Working, activity = "x", todo = TodoNow("Running the tests", 3, 7))))
        assertEquals("x", doingLine(session(SessionState.Working, activity = "x", todo = TodoNow(" ", 3, 7))))
    }
}
