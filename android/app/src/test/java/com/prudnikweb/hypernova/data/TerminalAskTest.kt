package com.prudnikweb.hypernova.data

import com.prudnikweb.hypernova.ui.home.CardAsk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fork: Claude Code's own permission dialog in a session's terminal (`menu.permission`) is drawn as
 * an approval: Allow its Yes, Deny its last No, other options as they are, on Home too. Any other
 * menu keeps its menu card; Codex's terminal and a session with no shared terminal have none.
 */
class TerminalAskTest {
    /** The Arc session's dialog as the daemon sends it (2026-10-30). */
    private val arc = """{"id":"30ccf6a3eb6b","title":"Bash command",
        "body":["Smoke-check all live pages","Ask rule Bash(python:*) overrides auto mode for this command.","/permissions to let auto mode decide","Do you want to proceed?"],
        "options":[{"label":"Yes","detail":null,"current":false},{"label":"No","detail":null,"current":false}],
        "cursor":0,"more":false,"cancel":true,"alt":null,
        "permission":{"tool":"Bash command","command":"/p/.venv/bin/python /p/check.py http://h:8108 /healthz","question":"Do you want to proceed?"}}"""

    private fun wire(menu: String, extra: String = """"term":{"id":"t1","mac":0,"phone":1}""") =
        Wire.session(JSONObject("""{"id":"s1","cwd":"/p","state":"needs_you",$extra,"menu":$menu}"""))

    private fun session(menu: TermMenu?, term: Boolean = true, agent: String = AgentKind.CLAUDE) =
        Session("s1", "s1", "t", "p", SessionState.NeedsYou, "opus", "auto", "", 0L, term = if (term) TermLink("t1", 0, 1) else null, menu = menu, agent = agent)

    @Test fun theArcDialogIsAnApproval() {
        val m = wire(arc).menu
        assertEquals(TermMenu.Permission("Bash command", "/p/.venv/bin/python /p/check.py http://h:8108 /healthz", "Do you want to proceed?"), m?.permission)
        val ask = TerminalAsk.of(session(m))!!
        assertEquals("30ccf6a3eb6b", ask.menuId)
        assertEquals(0, ask.allow)
        assertEquals(1, ask.deny)
        assertEquals(emptyList<TerminalAsk.Extra>(), ask.extras)
        assertTrue(ask.isBash)
        assertEquals("Do you want to proceed?", ask.lines.last())
    }

    @Test fun moreOptionsStayAsTheyAre() {
        val m = TermMenu(
            "m2", "Bash command", listOf("Do you want to proceed?"),
            listOf(
                TermMenu.Option("Yes", null, false),
                TermMenu.Option("Yes, and always allow access to /tmp/x from this project", null, false),
                TermMenu.Option("Yes, and switch to auto mode", "auto mode handles these prompts for you", false),
                TermMenu.Option("No", null, false),
            ),
            0, false, true, null, TermMenu.Permission("Bash command", "touch /tmp/x/ok", "Do you want to proceed?"),
        )
        val ask = TerminalAsk.of(m)!!
        assertEquals(0, ask.allow)
        assertEquals(3, ask.deny)
        assertEquals(listOf(1, 2), ask.extras.map { it.choice })
        assertEquals("auto mode handles these prompts for you", ask.extras[1].detail)
    }

    @Test fun otherMenusKeepTheirCard() {
        // /model, or an older daemon (no permission): the menu card as before.
        val model = wire("""{"id":"m3","title":"Select model","body":[],"options":[{"label":"Opus"},{"label":"Sonnet"}],"cursor":0,"more":false,"cancel":true}""").menu
        assertNull(model?.permission)
        assertNull(TerminalAsk.of(session(model)))
        // Without a Yes first and a No, it isn't one to Allow or Deny.
        val odd = wire(arc).menu!!.copy(options = listOf(TermMenu.Option("Maybe", null, false), TermMenu.Option("No", null, false)))
        assertNull(TerminalAsk.of(odd))
    }

    @Test fun onlyInAClaudeCodeTerminal() {
        val m = wire(arc).menu
        assertNull(TerminalAsk.of(session(m, term = false)))
        assertNull(TerminalAsk.of(session(m, agent = AgentKind.CODEX)))
    }

    @Test fun homeCardAllowsAndDeniesIt() {
        val m = wire(arc).menu
        val card = CardAsk.of(null, session(m))
        assertTrue(card is CardAsk.Terminal)
        assertEquals("30ccf6a3eb6b", card!!.id)
        // An approval waiting comes first (the daemon shows no menu then anyway).
        val approval = Wire.approval(JSONObject("""{"id":"ap1","sessionId":"s1","kind":"tool","toolName":"Bash","summary":"Bash: ls","input":{"command":"ls"}}"""))
        assertEquals(CardAsk.Exact("ap1", "Bash", "ls"), CardAsk.of(approval, session(m)))
        assertNull(CardAsk.of(null, session(null)))
    }
}
