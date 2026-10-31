package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.Aside
import com.prudnikweb.hypernova.data.AsideWire
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.SlashCommand
import com.prudnikweb.hypernova.data.TermLink
import com.prudnikweb.hypernova.data.Wire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session sheet's Commands tab (ui/session/CommandsTabModel.kt) and the side question it reads (data/Aside.kt). */
class CommandsTabTest {
    private fun cmd(name: String, hint: String = "", builtin: Boolean = true, terminal: Boolean = false, aliases: List<String> = emptyList()) =
        SlashCommand(name, "about $name", hint, aliases, builtin, terminal)

    private val list = listOf(
        cmd("compact", "<optional custom summarization instructions>"),
        cmd("model", "<model>"),
        cmd("usage", aliases = listOf("cost", "stats"), terminal = true),
        cmd("config", terminal = true),
        cmd("context"),
        cmd("clear", "[name]"),
        cmd("btw", "[question]"),
        cmd("logout"),
        cmd("hyper-cosmos", builtin = false),
        cmd("code-review", "[base]", builtin = false),
    )

    private fun session(phase: Phase = Phase.Idle, origin: String? = "terminal", term: TermLink? = null, agentJob: String? = null, agent: String = AgentKind.CLAUDE) =
        Session("00", "s", "s", "p", SessionState.Idle, "claude-opus-5-5", "default", "", 0L, phase = phase, origin = origin, term = term, agentJob = agentJob, agent = agent)

    @Test fun whatACommandTakesComesFromItsHint() {
        assertEquals(ArgNeed.None, argNeed(""))
        assertEquals(ArgNeed.Optional, argNeed("[name]"))
        assertEquals(ArgNeed.Optional, argNeed("<optional custom summarization instructions>"))
        assertEquals(ArgNeed.Required, argNeed("<model>"))
        assertEquals(ArgNeed.Required, argNeed("<low|medium|high|xhigh|max|auto>"))
        assertEquals(ArgNeed.Optional, argNeed("(--wizard posture=… scope=…)"))
    }

    @Test fun aTapSendsOneThatTakesNothingAndOpensAFieldForTheOthers() {
        assertTrue(sendsOnTap(cmd("context")))
        assertFalse(sendsOnTap(cmd("clear", "[name]")))
        assertFalse(sendsOnTap(cmd("model", "<model>")))
        // One that must take something waits for it; one that may goes without.
        assertFalse(canSendCommand(cmd("model", "<model>"), "  "))
        assertTrue(canSendCommand(cmd("model", "<model>"), "sonnet"))
        assertTrue(canSendCommand(cmd("clear", "[name]"), ""))
    }

    @Test fun theLineSentIsTheCommandAndWhatWasTypedAfterIt() {
        assertEquals("/context", commandLine(cmd("context")))
        assertEquals("/clear", commandLine(cmd("clear", "[name]"), "   "))
        assertEquals("/compact keep the API notes", commandLine(cmd("compact"), "  keep the API notes \n"))
    }

    @Test fun effortModelRenameAndCompactOpenTheirHelpersWhereTheirRoutesReach() {
        val all = HelperReach(effort = true, model = true, rename = true)
        assertEquals(ArgHelper.Effort, argHelper(cmd("effort", "<low|medium|high|xhigh|max|auto>"), all))
        assertEquals(ArgHelper.Model, argHelper(cmd("model", "<model>"), all))
        assertEquals(ArgHelper.Rename, argHelper(cmd("rename", "[name]"), all))
        assertEquals(ArgHelper.Compact, argHelper(cmd("compact", "<optional custom summarization instructions>"), all))
        // The rest keep their field; a skill named as one of Claude Code's is the owner's, a field too.
        assertEquals(ArgHelper.Field, argHelper(cmd("clear", "[name]"), all))
        assertEquals(ArgHelper.Field, argHelper(cmd("context"), all))
        assertEquals(ArgHelper.Field, argHelper(cmd("model", "[name]", builtin = false), all))
        // Without its route, its field as before; /compact's sheet needs none.
        val none = HelperReach(effort = false, model = false, rename = false)
        assertEquals(ArgHelper.Field, argHelper(cmd("effort", "<low|medium|high|xhigh|max|auto>"), none))
        assertEquals(ArgHelper.Field, argHelper(cmd("model", "<model>"), none))
        assertEquals(ArgHelper.Field, argHelper(cmd("rename", "[name]"), none))
        assertEquals(ArgHelper.Compact, argHelper(cmd("compact"), none))
    }

    @Test fun theRoutesReachAsTheOverviewsPickersDo() {
        // A shared terminal: all three.
        assertEquals(HelperReach(effort = true, model = true, rename = true), HelperReach.of(session(term = TermLink("t", 0, 1)), changeable = true, demo = false))
        // One the Mac runs itself: the model and the effort, but /rename has no terminal to be typed into.
        assertEquals(HelperReach(effort = true, model = true, rename = false), HelperReach.of(session(origin = "hypernova"), changeable = true, demo = false))
        // A job: the name too, through claude attach.
        assertTrue(HelperReach.of(session(agentJob = "feedbeef"), changeable = true, demo = false).rename)
        // Where the mode can't be changed, neither can the model nor the effort.
        assertEquals(HelperReach(effort = false, model = false, rename = true), HelperReach.of(session(term = TermLink("t", 0, 1)), changeable = false, demo = false))
        // Ended: none.
        assertEquals(HelperReach(effort = false, model = false, rename = false), HelperReach.of(session(phase = Phase.Ended, term = TermLink("t", 0, 1)), changeable = false, demo = false))
    }

    @Test fun compactSendsItsFocusTrimmedOnOneLineOrNothing() {
        assertEquals("/compact", compactLine(""))
        assertEquals("/compact", compactLine("   \n "))
        assertEquals("/compact keep the API notes", compactLine("keep the API notes"))
        assertEquals("/compact keep the API notes", compactLine("  keep the API notes \n"))
        assertEquals("/compact keep the API notes and the bugs", compactLine("keep the API notes\nand  the bugs"))
    }

    @Test fun recentIsMostRecentFirstOnceEachAndPinningToggles() {
        val p = CommandPicks().used("context").used("compact").used("context")
        assertEquals(listOf("context", "compact"), p.recent)
        val many = (1..20).fold(CommandPicks()) { acc, i -> acc.used("c$i") }
        assertEquals(12, many.recent.size)
        assertEquals("c20", many.recent.first())
        val pinned = CommandPicks().pinToggled("model").pinToggled("hyper-cosmos")
        assertEquals(listOf("model", "hyper-cosmos"), pinned.pinned)
        assertTrue(pinned.isPinned("model"))
        assertEquals(listOf("hyper-cosmos"), pinned.pinToggled("model").pinned)
    }

    @Test fun theListIsRecentThenPinnedThenTheRestYoursFirstAndTerminalOnesLast() {
        val picks = CommandPicks(recent = listOf("compact", "model", "gone-skill", "cost"), pinned = listOf("model", "hyper-cosmos"))
        val parts = commandSections(list, picks)
        // Pinned in the order pinned; recent without the pinned ones or one this project lacks, an alias as its command.
        assertEquals(listOf("model", "hyper-cosmos"), parts.pinned.map { it.name })
        assertEquals(listOf("compact", "usage"), parts.recent.map { it.name })
        assertEquals(listOf("code-review", "clear", "context", "config"), parts.all.map { it.name })
        // /btw has its own field, /logout would sign Claude Code out with one tap: not in the tab.
        assertTrue(listOf(parts.recent, parts.pinned, parts.all).flatten().none { it.name in setOf("btw", "logout") })
    }

    @Test fun recentShowsFiveAtMost() {
        val names = listOf("compact", "model", "usage", "config", "context", "clear", "hyper-cosmos")
        val parts = commandSections(list, CommandPicks(recent = names))
        assertEquals(names.take(RECENT_SHOWN), parts.recent.map { it.name })
    }

    @Test fun aSearchIsOneListBestFirst() {
        val parts = commandSections(list, CommandPicks(recent = listOf("context"), pinned = listOf("model")), "/co")
        assertTrue(parts.recent.isEmpty() && parts.pinned.isEmpty())
        // A name that starts with it, then a part of a name or an alias ("cosmos", "cost"); the owner's first in each.
        assertEquals(listOf("code-review", "compact", "config", "context", "hyper-cosmos", "usage"), parts.all.map { it.name })
        assertTrue(commandSections(list, CommandPicks(), "zzz").isEmpty)
    }

    @Test fun aSideQuestionGoesToAnOpenClaudeSessionInATerminalOrAJob() {
        assertTrue(asideReach(session(term = TermLink("t", 0, 1)), demo = false))
        assertTrue(asideReach(session(agentJob = "feedbeef"), demo = false))
        assertFalse(asideReach(session(origin = "hypernova"), demo = false))
        assertFalse(asideReach(session(origin = "terminal"), demo = false))
        assertFalse(asideReach(session(phase = Phase.Ended, agentJob = "feedbeef"), demo = false))
        assertFalse(asideReach(session(term = TermLink("t", 0, 1), agent = AgentKind.CODEX), demo = false))
        // Commands go to one the Mac runs too; a terminal command only where there's a terminal.
        assertTrue(commandReach(session(origin = "hypernova"), demo = false))
        assertFalse(commandReach(session(origin = "terminal"), demo = false))
        assertFalse(terminalReach(session(origin = "hypernova")))
        assertTrue(terminalReach(session(agentJob = "feedbeef")))
    }

    @Test fun theMacsAnswerReadsAsAnAside() {
        val a = AsideWire.of(JSONObject("""{"aside":{"id":"a1","question":"why?","state":"answered","answer":"Because.","error":null,"clipped":true,"askedAt":5,"at":9}}"""))!!
        assertEquals("Because.", a.answer)
        assertEquals(Aside.ANSWERED, a.state)
        assertTrue(a.clipped)
        assertFalse(a.asking)
        assertNull(AsideWire.of(JSONObject("""{"aside":null}""")))
        assertTrue(AsideWire.of(JSONObject("""{"aside":{"id":"a2","question":"q","state":"asking"}}"""))!!.asking)
    }

    @Test fun theListSaysWhichOpenAScreenInTheTerminal() {
        val l = Wire.commands(JSONObject("""{"commands":[{"name":"config","description":"Open settings","argumentHint":"","aliases":[],"builtin":true,"terminal":true},{"name":"context","builtin":true}]}"""))
        assertEquals(listOf(true, false), l.map { it.terminal })
    }
}
