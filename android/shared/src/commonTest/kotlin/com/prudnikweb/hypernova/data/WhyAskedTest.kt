package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why it asked (IDEAS PA7; WhyAsked.kt): read from an approval and a terminal dialog's
 * `menu.permission`, the line in runs with the rule in mono, and which asks the Permissions tab
 * says why for. Never on the chat's card: the Item carries it only for the Always sheet.
 */
class WhyAskedTest {
    private val askRule = Why("ask_rule", "Ask rule Bash(python:*) overrides auto mode for this command.", "Bash(python:*)", "claude")

    @Test fun readsFromTheWire() {
        assertEquals(askRule, Why.wire(JSONObject("""{"kind":"ask_rule","text":"Ask rule Bash(python:*) overrides auto mode for this command.","rule":"Bash(python:*)","source":"claude"}""")))
        val mine = Why.wire(JSONObject("""{"kind":"no_rule","text":"No allow rule matches this command.","source":"hypernova"}"""))!!
        assertNull(mine.rule)
        assertTrue(mine.hypernovas)
        // A kind this phone doesn't know is still shown; an unknown source is Claude Code's.
        assertEquals("sandbox", Why.wire(JSONObject("""{"kind":"sandbox","text":"x","source":"elsewhere"}"""))!!.kind)
        assertFalse(Why.wire(JSONObject("""{"kind":"sandbox","text":"x","source":"elsewhere"}"""))!!.hypernovas)
        // No text, nothing to show (an older daemon sends no why at all).
        assertNull(Why.wire(JSONObject("""{"kind":"ask_rule","text":"  "}""")))
        assertNull(Why.wire(null))
    }

    @Test fun theRuleInMonoWhereTheTextNamesIt() {
        assertEquals(
            listOf(Why.Span("Ask rule ", false), Why.Span("Bash(python:*)", true), Why.Span(" overrides auto mode for this command.", false)),
            askRule.spans(),
        )
        // A rule the text doesn't name follows it.
        assertEquals(
            listOf(Why.Span("One of its own checks, most likely.", false), Why.Span(" · ", false), Why.Span("Bash(npm test *)", true)),
            Why("safety", "One of its own checks, most likely.", "Bash(npm test *)", "hypernova").spans(),
        )
        assertEquals(listOf(Why.Span("No allow rule matches this command.", false)), Why("no_rule", "No allow rule matches this command.", null, "hypernova").spans())
        // The rule at the very start: no empty run before it.
        assertEquals(listOf(Why.Span("Bash(x)", true), Why.Span(" asks.", false)), Why("ask_rule", "Bash(x) asks.", "Bash(x)", "claude").spans())
    }

    private fun approval(json: String) = Wire.approval(JSONObject(json))

    @Test fun anApprovalAndADialogCarryIt() {
        val a = approval("""{"id":"a1","sessionId":"s1","kind":"tool","toolName":"Bash","summary":"Bash: python x.py","input":{"command":"python x.py"},"why":{"kind":"ask_rule","text":"Ask rule Bash(python:*) in your user settings matches this command.","rule":"Bash(python:*)","source":"hypernova"}}""")
        assertEquals("Bash(python:*)", a.why!!.rule)
        assertNull(approval("""{"id":"a2","sessionId":"s1","kind":"tool","toolName":"Bash","summary":"Bash: ls","input":{}}""").why)
        val m = Wire.session(
            JSONObject(
                """{"id":"s1","cwd":"/p","state":"needs_you","term":{"id":"t1","mac":0,"phone":1},
                "menu":{"id":"m1","title":"Bash command","body":["Ask rule Bash(python:*) overrides auto mode for this command.","Do you want to proceed?"],
                "options":[{"label":"Yes"},{"label":"No"}],"cursor":0,"more":false,"cancel":true,"alt":null,
                "permission":{"tool":"Bash command","command":"python x.py","question":"Do you want to proceed?","why":{"kind":"ask_rule","text":"Ask rule Bash(python:*) overrides auto mode for this command.","rule":"Bash(python:*)","source":"claude"}}}}""",
            ),
        ).menu!!
        assertEquals(askRule, m.permission!!.why)
    }

    private fun ask(id: String, at: Long, command: String, why: Why?, kind: String = "tool") = WApproval(
        id = id, sessionId = "s", createdAt = at, expiresAt = null, kind = kind, toolName = if (kind == "tool") "Bash" else "AskUserQuestion",
        summary = "Bash: $command", input = mapOf("command" to command), reason = null, canAlways = true, questions = emptyList(), plan = null, why = why,
    )
    private val none = Why("no_rule", "No allow rule matches this command.", null, "hypernova")

    @Test fun thePermissionsTabSaysWhyForTheOneAskAndTheTerminalsDialog() {
        assertEquals(listOf(WhyAsk("Bash", "python x.py", askRule)), whyAsksOf(listOf(ask("a", 1, "python x.py", askRule)), null))
        // An ask that says nothing, and a question, aren't listed.
        assertEquals(emptyList<WhyAsk>(), whyAsksOf(listOf(ask("a", 1, "ls", null), ask("q", 2, "", none, kind = "question")), null))
        // Claude Code's own dialog in the terminal, after the ask (a Bash title reads with "$ ").
        val dialog = TermMenu.Permission("Bash command · from the Explore agent", "make deploy", "Do you want to proceed?", none)
        assertEquals(listOf(WhyAsk("Bash", "make deploy", none)), whyAsksOf(emptyList(), dialog))
        assertEquals(listOf(WhyAsk("Edit file", "Do you want to make this edit?", none)), whyAsksOf(emptyList(), TermMenu.Permission("Edit file", null, "Do you want to make this edit?", none)))
        assertEquals(emptyList<WhyAsk>(), whyAsksOf(emptyList(), TermMenu.Permission("Bash command", "ls", "Do you want to proceed?", null)))
        // Two tool asks: "Asks waiting" says why under each instead.
        assertEquals(emptyList<WhyAsk>(), whyAsksOf(listOf(ask("a", 1, "python x.py", askRule), ask("b", 2, "make", none)), dialog))
    }

    @Test fun theApprovalItemCarriesItForTheAlwaysSheet() {
        val a = ask("ap1", 1, "python x.py", askRule)
        val items = Items.build(listOf(WEvent.Approval("e1", 1L, "ap1", a.summary, null)), mapOf("ap1" to a), emptyMap(), emptyMap(), "s", "/p", now = 2L)
        assertEquals(askRule, (items.single() as Item.Approval).why)
        // Its `reason` (shown on the card) stays Claude Code's own, empty here: the card is unchanged.
        assertEquals("", (items.single() as Item.Approval).reason)
    }
}
