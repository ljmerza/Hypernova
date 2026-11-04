package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRulesTest {
    private fun row(text: String) = RuleRow(0, text, false, null, null, null, null, null)

    @Test fun theFormsHelperMakesEachCommonKind() {
        assertEquals("Bash(npm run test *)", RuleDraft.text(RuleKind.Command, " npm run test ", prefix = true))
        assertEquals("Bash(npm run test)", RuleDraft.text(RuleKind.Command, "npm run test", prefix = false))
        assertEquals("Read(~/x/**)", RuleDraft.text(RuleKind.ReadFiles, "~/x/**"))
        assertEquals("Edit(src/**)", RuleDraft.text(RuleKind.EditFiles, "src/**"))
        assertEquals("WebFetch(domain:github.com)", RuleDraft.text(RuleKind.Site, "github.com"))
        assertEquals("WebFetch(domain:docs.example.com)", RuleDraft.text(RuleKind.Site, "https://me@docs.example.com/a?b#c"))
        assertEquals("mcp__github__create_issue", RuleDraft.text(RuleKind.Mcp, "github", "create_issue"))
        assertEquals("mcp__github__*", RuleDraft.text(RuleKind.Mcp, "github"))
        assertEquals("Anything(at all", RuleDraft.text(RuleKind.Text, " Anything(at all "))
    }

    @Test fun blankFieldsMakeNothingSoAWholeToolIsNeverASlip() {
        for (kind in listOf(RuleKind.Command, RuleKind.ReadFiles, RuleKind.EditFiles, RuleKind.Site, RuleKind.Mcp, RuleKind.Text)) assertEquals(kind.name, "", RuleDraft.text(kind, "  "))
    }

    @Test fun searchIsAnyPartAnyCase() {
        val rows = listOf(row("Bash(npm test)"), row("Read(src/**)"))
        assertEquals(rows, rulesMatching(rows, ""))
        assertEquals(rows, rulesMatching(rows, "  "))
        assertEquals(listOf(rows[1]), rulesMatching(rows, "SRC"))
        assertEquals(emptyList<RuleRow>(), rulesMatching(rows, "nothing"))
    }

    @Test fun aChangeNamesOnlyWhatItNeeds() {
        assertEquals("""{"op":"add","list":"deny","text":"x"}""".let { JSONObject(it).toString().length }, RuleChange.Add("deny", "x").wire().toString().length)
        val move = RuleChange.Move("allow", 3, "ask").wire()
        assertFalse(move.has("text"))
        assertEquals(3, move.getInt("index"))
        assertFalse(RuleChange.Delete("ask", 0).wire().has("text"))
        assertEquals("edit", RuleChange.Edit("ask", 1, "y").wire().getString("op"))
    }

    @Test fun anOlderOrOddReplyDoesntBreakTheReading() {
        val r = AgentRulesReply.wire(JSONObject("""{"supported":true,"where":"w","state":"malformed","hash":null,"lists":null,"undo":null}"""))
        assertNull(r.lists)
        assertFalse(r.editable)
        assertEquals(emptyList<RuleRow>(), r.list(AgentRulesReply.ALLOW).rules)
        val c = AgentRulesReply.wire(JSONObject("""{"agent":"codex","supported":false,"rulesFiles":null}"""))
        assertFalse(c.supported)
        assertNull(c.rulesFiles)
        val ok = AgentRulesReply.wire(JSONObject("""{"supported":true,"state":"ok","whyNot":null,"lists":{"allow":{"rules":null,"others":0}}}"""))
        assertTrue(ok.editable)
        assertNull(ok.list(AgentRulesReply.ALLOW).rules)
        assertEquals(emptyList<RuleRow>(), ok.list(AgentRulesReply.DENY).rules)
    }
}
