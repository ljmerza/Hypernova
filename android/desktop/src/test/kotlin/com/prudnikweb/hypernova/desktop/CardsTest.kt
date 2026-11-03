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
        assertEquals(CardOffer.InChat("ap1", plan = false), CardOffer.of(ask("question", "AskUserQuestion", "{}")))
        assertEquals(CardOffer.InChat("ap1", plan = true), CardOffer.of(ask("plan", "ExitPlanMode", "{}")))
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
        assertFalse(answerCard(c.hub, "s1", CardOffer.InChat("ap1", plan = false), allow = true) { })
        assertFalse(answerCard(c.hub, "s1", CardOffer.InChat("ap1", plan = false), allow = false) { })
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

    // ─── The list and the pane ───

    private val zone = java.time.ZoneOffset.UTC

    @Test fun theArrowsWalkTheListAndStopAtItsEnds() {
        val order = listOf("a", "b", "c")
        assertEquals("b", neighbour(order, "a", 1))
        assertEquals("a", neighbour(order, "b", -1))
        assertEquals("c", neighbour(order, "c", 1))
        assertEquals("a", neighbour(order, "a", -1))
        assertEquals("from nothing, down", "a", neighbour(order, null, 1))
        assertEquals("from nothing, up", "c", neighbour(order, null, -1))
        assertEquals("from a row that's gone", "a", neighbour(order, "gone", 1))
        assertNull(neighbour(emptyList(), null, 1))
    }

    @Test fun aRowsItemIsCountedPastTheSeams() {
        val g = groupsOf(listOf(session(SessionState.NeedsYou, "n", "1"), session(SessionState.Working, "a", "2"), session(SessionState.Working, "b", "3"), session(SessionState.Idle, "c", "4")))
        assertEquals(listOf("1", "2", "3", "4"), orderOf(g))
        // seam, 1, seam, 2, 3, seam, 4
        assertEquals(1, itemIndexOf(g, "1"))
        assertEquals(3, itemIndexOf(g, "2"))
        assertEquals(4, itemIndexOf(g, "3"))
        assertEquals(6, itemIndexOf(g, "4"))
        assertNull(itemIndexOf(g, "gone"))
    }

    @Test fun aRowsLineIsOneLineAndNeverShowsASecret() {
        val whole = CardOffer.of(ask("tool", "Bash", """{"command":"git status\ngit push"}"""))
        assertEquals("git status git push", rowLine(whole, session(SessionState.NeedsYou)).text)
        val secret = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789"
        val masked = rowLine(CardOffer.of(ask("tool", "Bash", """{"command":"curl -H 'Authorization: Bearer $secret' https://x"}""")), session(SessionState.NeedsYou))
        assertFalse(masked.text, masked.text.contains(secret))
        val question = rowLine(CardOffer.of(ask("question", "AskUserQuestion", "{}")), session(SessionState.NeedsYou))
        assertEquals(Ui.desk_ask_question.text(), question.text)
        assertEquals(RowLine.Tone.Quiet, rowLine(null, session(SessionState.Working, activity = "Bash: ls")).tone)
        assertEquals("Bash: ls", rowLine(null, session(SessionState.Working, activity = "Bash: ls")).text)
    }

    @Test fun aRowHasAChipOnlyForWhatItsGroupDoesntSay() {
        assertFalse(rowHasChip(session(SessionState.Working), null))
        assertFalse(rowHasChip(session(SessionState.NeedsYou), null))
        assertFalse(rowHasChip(session(SessionState.Idle), null))
        assertTrue(rowHasChip(session(SessionState.Idle, phase = Phase.Ended), null))
        assertTrue(rowHasChip(session(SessionState.Idle, phase = Phase.Error), null))
        assertTrue(rowHasChip(session(SessionState.Idle), com.prudnikweb.hypernova.data.Blocked("limit", "Limit reached")))
    }

    private fun fact(facts: List<Fact>, label: Ui) = facts.firstOrNull { it.label == label.text() }?.value

    @Test fun theFactsAreTheSessionsOwn() {
        val now = 1_800_000_000_000L
        val s = session(SessionState.Working).copy(
            cwd = "/home/me/proj", model = "claude-opus-5-5", mode = "acceptEdits", origin = "hypernova",
            createdAt = now - 3_600_000 * 2 - 60_000 * 5, updatedAt = now - 120_000, turnStartedAt = now - 300_000, agentsRunning = 2, queued = 1, contextPct = 44.4f,
        )
        val f = factsOf(s, now, zone)
        assertEquals("/home/me/proj", fact(f, Ui.session_sheet_folder))
        assertEquals("Opus 5.5", fact(f, Ui.model))
        assertEquals(Ui.mode_label_accept.text(), fact(f, Ui.mode))
        assertEquals(Ui.session_sheet_runs_mac.text(), fact(f, Ui.session_sheet_runs_in))
        assertEquals(Ui.claude.text(), fact(f, Ui.session_sheet_agent))
        assertEquals(Ui.agents_running.format(2), fact(f, Ui.session_sheet_agents))
        assertEquals(Ui.queued.format(1), fact(f, Ui.queued_label))
        assertEquals("44%", fact(f, Ui.st_top_bar_context))
        assertEquals("2h 5m", fact(f, Ui.session_sheet_running_for))
        assertEquals("5:00", fact(f, Ui.session_sheet_this_turn))
        assertEquals("2 minutes ago", fact(f, Ui.session_sheet_last_activity))
    }

    @Test fun whatIsntKnownIsLeftOut() {
        val f = factsOf(session(SessionState.Idle).copy(updatedAt = 0L), 1_000L, zone)
        for (u in listOf(Ui.session_sheet_folder, Ui.session_sheet_started, Ui.session_sheet_running_for, Ui.session_sheet_this_turn, Ui.session_sheet_last_activity, Ui.session_sheet_agents, Ui.queued_label, Ui.st_top_bar_context)) {
            assertNull(u.name, fact(f, u))
        }
    }

    @Test fun anEndedSessionRanAndHasNoTurn() {
        val now = 1_800_000_000_000L
        val s = session(SessionState.Idle, phase = Phase.Ended).copy(createdAt = now - 600_000, updatedAt = now - 60_000, turnStartedAt = now - 100_000, agentsRunning = 3)
        val f = factsOf(s, now, zone)
        assertEquals("9:00", fact(f, Ui.session_sheet_ran_for))
        assertNull(fact(f, Ui.session_sheet_running_for))
        assertNull(fact(f, Ui.session_sheet_this_turn))
        assertNull(fact(f, Ui.session_sheet_agents))
    }

    @Test fun aModeIsInPlainWordsAndAnUnknownOneAsItIs() {
        assertEquals(Ui.mode_label_default.text(), modeWord("default"))
        assertEquals(Ui.mode_label_bypass.text(), modeWord("bypassPermissions"))
        assertEquals(Ui.cx_mode_label_read_only.text(), modeWord("read-only"))
        assertEquals("newOne", modeWord("newOne"))
    }

    @Test fun agoAndClockWords() {
        assertEquals(Ui.meta_ago_now.text(), agoWords(59_000))
        assertEquals("1 minute ago", agoWords(60_000))
        assertEquals("3 hours ago", agoWords(3 * 3_600_000L))
        assertEquals("1 day ago", agoWords(24 * 3_600_000L))
        val now = java.time.ZonedDateTime.of(2026, 11, 2, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val time = java.time.format.DateTimeFormatter.ofPattern(DesktopStrings.timePattern(false), DesktopStrings.locale)
        val fourteen = java.time.LocalTime.of(14, 5)
        assertEquals("today, the time alone", time.format(fourteen), clockWords(now - 55 * 60_000, now, zone))
        assertTrue("this week, with its day", clockWords(now - 86_400_000, now, zone).endsWith(time.format(java.time.LocalTime.of(15, 0))))
        assertTrue("before, with its date", clockWords(now - 20 * 86_400_000L, now, zone).contains("Oct"))
    }
}
