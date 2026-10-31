package com.prudnikweb.hypernova.widget

import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Question
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TermLink
import com.prudnikweb.hypernova.data.TermMenu
import com.prudnikweb.hypernova.data.WApproval
import com.prudnikweb.hypernova.widget.WidgetModel.Answering
import com.prudnikweb.hypernova.widget.WidgetModel.Kind
import com.prudnikweb.hypernova.widget.WidgetModel.Source
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen widget's "Needs you" list (widget/WidgetModel.kt): every session that needs the
 * owner on every paired computer, grouped by computer in the switcher's order, computers with
 * nothing waiting left out, the oldest wait first, what each waits on, and which rows get Deny and
 * Allow (a tool's request on a computer that can take the answer; Allow only beside its whole command).
 */
class WidgetModelTest {
    private fun session(
        id: String,
        name: String = id,
        state: SessionState = SessionState.Idle,
        createdAt: Long = 0L,
        menu: TermMenu? = null,
        term: Boolean = menu != null,
        phase: Phase = Phase.of(state),
        agent: String = AgentKind.CLAUDE,
        activity: String = "",
    ) = Session(
        hex = id.take(2), id = id, name = name, project = "proj-$id", state = state, model = "opus", mode = "default",
        activity = activity, updatedAt = createdAt, phase = phase, term = if (term) TermLink("t-$id", 0, 1) else null,
        menu = menu, createdAt = createdAt, agent = agent,
    )

    private fun tool(id: String, sessionId: String, createdAt: Long, command: String, toolName: String = "Bash", expiresAt: Long? = null) = WApproval(
        id = id, sessionId = sessionId, createdAt = createdAt, expiresAt = expiresAt, kind = "tool", toolName = toolName,
        summary = "$toolName: $command", input = mapOf("command" to command), reason = null, canAlways = true,
        questions = emptyList(), plan = null,
    )

    private fun edit(id: String, sessionId: String, createdAt: Long, path: String) = WApproval(
        id = id, sessionId = sessionId, createdAt = createdAt, expiresAt = null, kind = "tool", toolName = "Edit",
        summary = "Edit: $path", input = mapOf("file_path" to path), reason = null, canAlways = true,
        questions = emptyList(), plan = null,
    )

    private fun question(id: String, sessionId: String, createdAt: Long, text: String) = WApproval(
        id = id, sessionId = sessionId, createdAt = createdAt, expiresAt = null, kind = "question", toolName = "AskUserQuestion",
        summary = text, input = emptyMap(), reason = null, canAlways = false,
        questions = listOf(Question(text, "Colour", false, emptyList())), plan = null,
    )

    private fun plan(id: String, sessionId: String, createdAt: Long, text: String) = WApproval(
        id = id, sessionId = sessionId, createdAt = createdAt, expiresAt = null, kind = "plan", toolName = "ExitPlanMode",
        summary = "Plan", input = emptyMap(), reason = null, canAlways = false, questions = emptyList(), plan = text,
    )

    /** Claude Code's own permission dialog in a terminal, as TerminalAskTest has it. */
    private fun dialog(id: String, command: String?) = TermMenu(
        id, "Bash command", listOf("Do you want to proceed?"),
        listOf(TermMenu.Option("Yes", null, false), TermMenu.Option("Yes, and don't ask again", null, false), TermMenu.Option("No", null, false)),
        0, false, true, null, TermMenu.Permission("Bash command", command, "Do you want to proceed?"),
    )

    private fun source(
        id: String,
        sessions: List<Session> = emptyList(),
        approvals: List<WApproval> = emptyList(),
        link: LinkState = LinkState.Connected,
        synced: Boolean = true,
        name: String = id,
    ) = Source(id, name, link, synced, sessions, approvals)

    @Test fun groupsByComputerInTheSwitchersOrderAndLeavesOutTheQuietOnes() {
        val z2 = source("z2", listOf(session("a", state = SessionState.NeedsYou)), listOf(tool("q1", "a", 10, "git push")))
        val quiet = source("shed", listOf(session("b", state = SessionState.Working)))
        val gym = source("gym", listOf(session("c", state = SessionState.NeedsYou)), listOf(tool("q2", "c", 5, "ls")))
        val m = WidgetModel.of(listOf(z2, quiet, gym), live = true)
        assertEquals(listOf("z2", "gym"), m.groups.map { it.server })
        assertEquals(listOf("z2", "gym"), m.groups.map { it.name })
        assertEquals(2, m.count)
        assertEquals(listOf("a"), m.groups[0].rows.map { it.sessionId })
        assertTrue(m.known)
        assertTrue(m.paired)
    }

    @Test fun oneRowPerSessionItsOldestApprovalFirstOldestWaitOnTop() {
        val s = source(
            "z2",
            listOf(session("late", state = SessionState.NeedsYou), session("early", state = SessionState.NeedsYou)),
            listOf(tool("q3", "late", 300, "make"), tool("q2", "late", 200, "npm test"), tool("q1", "early", 100, "git status")),
        )
        val rows = WidgetModel.of(listOf(s), live = true).groups.single().rows
        assertEquals(listOf("early", "late"), rows.map { it.sessionId })
        // The later session's oldest approval, with the other behind it.
        assertEquals("q2", rows[1].askId)
        assertEquals("npm test", rows[1].detail)
        assertEquals(1, rows[1].more)
        assertEquals(0, rows[0].more)
    }

    @Test fun nothingAnywhereIsAQuietEmptyList() {
        val m = WidgetModel.of(listOf(source("z2", listOf(session("a", state = SessionState.Working)))), live = true)
        assertEquals(emptyList<WidgetModel.Group>(), m.groups)
        assertEquals(0, m.count)
        assertTrue(m.known)
        assertTrue(m.paired)
        // No computer at all (or every one forgot this phone): not paired.
        assertFalse(WidgetModel.of(emptyList(), live = true).paired)
        assertFalse(WidgetModel.of(listOf(source("z2", link = LinkState.Unpaired)), live = true).paired)
        // Nothing heard from any computer yet, nothing kept: not known (it's connecting).
        assertFalse(WidgetModel.of(listOf(source("z2", synced = false)), live = true).known)
    }

    @Test fun eachKindSaysWhatItWaitsOn() {
        val s = source(
            "z2",
            listOf(
                session("t", state = SessionState.NeedsYou, createdAt = 1),
                session("q", state = SessionState.NeedsYou, createdAt = 2),
                session("p", state = SessionState.NeedsYou, createdAt = 3),
                session("d", state = SessionState.NeedsYou, createdAt = 4, menu = dialog("m1", "rm -rf build")),
                session("w", state = SessionState.NeedsYou, createdAt = 5, activity = "Waiting for you to come back"),
            ),
            listOf(
                tool("a1", "t", 1, "git push origin main"),
                question("a2", "q", 2, "Which colour for the header?"),
                plan("a3", "p", 3, "# Ship the widget\n\n1. Remove the sun"),
            ),
        )
        val rows = WidgetModel.of(listOf(s), live = true).groups.single().rows.associateBy { it.sessionId }
        assertEquals(Kind.Tool, rows.getValue("t").kind)
        assertEquals("Bash", rows.getValue("t").tool)
        assertEquals("git push origin main", rows.getValue("t").detail)
        assertEquals(Kind.Question, rows.getValue("q").kind)
        assertEquals("Which colour for the header?", rows.getValue("q").detail)
        assertEquals(Kind.Plan, rows.getValue("p").kind)
        assertEquals("Ship the widget", rows.getValue("p").detail)
        val d = rows.getValue("d")
        assertEquals(Kind.Terminal, d.kind)
        assertEquals("Bash command", d.tool)
        assertEquals("rm -rf build", d.detail)
        assertEquals("m1", d.askId)
        assertEquals(0, d.allowChoice)
        assertEquals(2, d.denyChoice)
        val w = rows.getValue("w")
        assertEquals(Kind.Waiting, w.kind)
        assertNull(w.askId)
        assertEquals("Waiting for you to come back", w.detail)
        assertEquals("t", rows.getValue("t").title)
        assertEquals("proj-t", rows.getValue("t").project)
    }

    @Test fun denyAndAllowOnlyWhereTheNotificationsRuleSaysSo() {
        val s = source(
            "z2",
            listOf(
                session("exact", state = SessionState.NeedsYou, createdAt = 1),
                session("masked", state = SessionState.NeedsYou, createdAt = 2),
                session("edit", state = SessionState.NeedsYou, createdAt = 3),
                session("q", state = SessionState.NeedsYou, createdAt = 4),
                session("dlg", state = SessionState.NeedsYou, createdAt = 5, menu = dialog("m1", "touch /tmp/ok")),
                session("dlg2", state = SessionState.NeedsYou, createdAt = 6, menu = dialog("m2", null)),
                session("w", state = SessionState.NeedsYou, createdAt = 7),
            ),
            listOf(
                tool("a1", "exact", 1, "git push origin main"),
                tool("a2", "masked", 2, "curl -u admin:hunter2 https://x.com"),
                edit("a3", "edit", 3, "/p/src/App.kt"),
                question("a4", "q", 4, "Which one?"),
            ),
        )
        val rows = WidgetModel.of(listOf(s), live = true).groups.single().rows.associateBy { it.sessionId }
        fun both(id: String) = rows.getValue(id).let { it.deny to it.allow }
        // The whole command: Deny and Allow.
        assertEquals(true to true, both("exact"))
        // Masked, or only a summary (an edit's path): Deny, and Open instead of Allow.
        assertEquals(true to false, both("masked"))
        assertEquals("curl -u ••• https://x.com", rows.getValue("masked").detail)
        assertEquals(true to false, both("edit"))
        // A question opens.
        assertEquals(false to false, both("q"))
        // The terminal's dialog: Deny, and Allow beside a command it shows.
        assertEquals(true to true, both("dlg"))
        assertEquals(true to false, both("dlg2"))
        assertEquals(false to false, both("w"))
    }

    @Test fun anOfflineComputersRowsOnlyOpen() {
        val s = source(
            "z2", listOf(session("a", state = SessionState.NeedsYou)), listOf(tool("q1", "a", 1, "ls")), link = LinkState.Offline, name = "Studio",
        )
        val m = WidgetModel.of(listOf(s), live = true)
        val g = m.groups.single()
        assertTrue(g.offline)
        assertFalse(g.rows.single().deny)
        assertFalse(g.rows.single().allow)
        assertEquals(listOf("Studio"), m.offline)
    }

    @Test fun answeredHereHidesTheRowAndAnsweringShowsOnIt() {
        val s = source(
            "z2",
            listOf(session("a", state = SessionState.NeedsYou, createdAt = 1), session("b", state = SessionState.NeedsYou, createdAt = 2, menu = dialog("m1", "ls"))),
            listOf(tool("q1", "a", 1, "ls")),
        )
        val going = WidgetModel.of(listOf(s), live = true, answering = mapOf("q1" to Answering.Allowing, "m1" to Answering.Failed))
        assertEquals(listOf(Answering.Allowing, Answering.Failed), going.groups.single().rows.map { it.answering })
        // Answered from the widget: gone before the computer's word arrives (not shown as just "waiting").
        val gone = WidgetModel.of(listOf(s), live = true, done = setOf("q1", "m1"))
        assertEquals(emptyList<WidgetModel.Group>(), gone.groups)
    }

    @Test fun endedSessionsAndCodexTerminalsAreLeftOut() {
        val s = source(
            "z2",
            listOf(
                session("ended", state = SessionState.NeedsYou, phase = Phase.Ended),
                // Codex's terminal has no Claude Code dialog: a session that needs you, to open.
                session("codex", state = SessionState.NeedsYou, menu = dialog("m1", "ls"), agent = AgentKind.CODEX),
            ),
        )
        val rows = WidgetModel.of(listOf(s), live = true).groups.single().rows
        assertEquals(listOf("codex"), rows.map { it.sessionId })
        assertEquals(Kind.Waiting, rows.single().kind)
    }

    @Test fun aComputerNotHeardFromYetShowsWhatWasKeptToOpenOnly() {
        val before = WidgetModel.of(listOf(source("z2", listOf(session("a", state = SessionState.NeedsYou)), listOf(tool("q1", "a", 1, "ls")))), live = true)
        assertTrue(before.groups.single().rows.single().allow)
        val kept = WidgetModel.fromJson(JSONObject(before.copy(updatedAt = 42L).toJson().toString()))!!
        assertEquals(42L, kept.updatedAt)
        val m = WidgetModel.of(listOf(source("z2", synced = false, link = LinkState.Connecting), source("shed", synced = false)), live = false, kept = kept)
        val g = m.groups.single()
        assertTrue(g.kept)
        assertEquals("q1", g.rows.single().askId)
        assertEquals("ls", g.rows.single().detail)
        assertFalse(g.rows.single().deny)
        assertFalse(g.rows.single().allow)
        assertTrue(m.known)
    }

    @Test fun aStalePictureDropsApprovalsThatLeftThePhone() {
        val s = source("z2", listOf(session("a", state = SessionState.NeedsYou)), listOf(tool("q1", "a", 1, "ls", expiresAt = 1_000L)))
        val live = WidgetModel.of(listOf(s), live = true)
        assertEquals(1, live.at(2_000L).count)
        val stale = WidgetModel.of(listOf(s), live = false)
        assertEquals(1, stale.at(500L).count)
        assertEquals(0, stale.at(2_000L).count)
    }

    @Test fun aMultiLineCommandIsWholeWithItsLineBreaks() {
        val cmd = "git add -A\ngit commit -m wip"
        val s = source("z2", listOf(session("a", state = SessionState.NeedsYou)), listOf(tool("q1", "a", 1, cmd)))
        val r = WidgetModel.of(listOf(s), live = true).groups.single().rows.single()
        assertTrue(r.whole)
        assertEquals(cmd, r.detail)
        // Too long to show whole: on one line, cut, and no Allow.
        val long = "echo " + "x".repeat(500)
        val t = source("z2", listOf(session("a", state = SessionState.NeedsYou)), listOf(tool("q1", "a", 1, long)))
        val r2 = WidgetModel.of(listOf(t), live = true).groups.single().rows.single()
        assertFalse(r2.allow)
        assertTrue(r2.deny)
        assertTrue(r2.detail.length <= WidgetModel.DETAIL_MAX)
    }
}
