package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "Always" covers (AlwaysRules.kt): a rule read and written as Claude Code does (the Mac
 * checks it the same way, daemon/src/always.ts), the quick widenings, the sheet's draft (edits,
 * back to Claude Code's, the scope it starts on, what the answer sends), and the approval's wire.
 */
class AlwaysRulesTest {
    private fun r(s: String) = AlwaysRule.parse(s)

    @Test fun readsARuleAsClaudeCodeDoes() {
        assertEquals(AlwaysRule("Bash", "npm test *"), r("Bash(npm test *)"))
        // The older prefix form, as rules in files still have it.
        assertEquals(AlwaysRule("Bash", "npm test:*"), r("Bash(npm test:*)"))
        assertEquals(AlwaysRule("WebFetch", null), r("  WebFetch "))
        assertEquals(AlwaysRule("mcp__github__create_issue", null), r("mcp__github__create_issue"))
        assertEquals(AlwaysRule("Read", "//home/u/p/**"), r("Read(//home/u/p/**)"))
        assertEquals(AlwaysRule("Bash", null), r("Bash()"))
        assertEquals(AlwaysRule("Bash", null), r("Bash(*)"))
        assertEquals(AlwaysRule("Bash", "echo (hi)"), r("Bash(echo \\(hi\\))"))
        assertEquals(AlwaysRule("Bash", "printf a\\b"), r("Bash(printf a\\\\b)"))
    }

    @Test fun refusesWhatIsNotARule() {
        for (bad in listOf("", "   ", "Bash(", "Bash)", "Bash(npm test", "(npm test)", "Bash(npm) x", "Ba sh", "1Bash", "Bash(a\nb)", "B".repeat(AlwaysRule.MAX + 1))) {
            assertNull("refused: $bad", r(bad))
        }
    }

    @Test fun writesItBackTheSame() {
        for (s in listOf("Bash(npm test *)", "Bash(npm test:*)", "WebFetch", "Bash(echo \\(hi\\))", "Bash(printf a\\\\b)")) assertEquals(s, r(s).toString())
    }

    @Test fun widensACommandToItsPrefixItsFirstTwoWordsAndTheWholeTool() {
        // A prefix as Claude Code's own suggestions now write one: a space, then *.
        assertEquals(listOf("Bash(npm test *)", "Bash"), r("Bash(npm test)")!!.widenings().map { it.toString() })
        assertEquals(
            listOf("Bash(npm test --prefix daemon *)", "Bash(npm test *)", "Bash"),
            r("Bash(npm test --prefix daemon)")!!.widenings().map { it.toString() },
        )
        // Already a prefix (either way Claude Code writes one, :* in older files): only shorter ones and the tool.
        assertEquals(listOf("Bash(git log *)", "Bash"), r("Bash(git log --oneline:*)")!!.widenings().map { it.toString() })
        assertEquals(listOf("Bash(git log *)", "Bash"), r("Bash(git log --oneline *)")!!.widenings().map { it.toString() })
        assertEquals(listOf("Bash"), r("Bash(npm test:*)")!!.widenings().map { it.toString() })
        assertEquals(listOf("Bash"), r("Bash(git status *)")!!.widenings().map { it.toString() })
        // One word: its prefix, then the tool.
        assertEquals(listOf("Bash(ls *)", "Bash"), r("Bash(ls)")!!.widenings().map { it.toString() })
        // Another tool's pattern widens to the tool; a whole tool not at all.
        assertEquals(listOf("Read"), r("Read(//home/u/p/**)")!!.widenings().map { it.toString() })
        assertTrue(r("WebFetch")!!.widenings().isEmpty())
    }

    @Test fun theDraftStartsAtClaudeCodesRuleAndScope() {
        val d = AlwaysDraft.of(listOf("Bash(npm test)"), "project")
        assertEquals(AlwaysScope.Project, d.scope)
        assertTrue(d.valid)
        assertEquals(listOf("Bash(npm test)"), d.rules)
        assertNull(d.suggestedAt(0))
        // No scope from the Mac (or one this app doesn't know): this session, which saves nothing.
        assertEquals(AlwaysScope.Session, AlwaysDraft.of(listOf("Read"), null).scope)
        assertEquals(AlwaysScope.Session, AlwaysDraft.of(listOf("Read"), "somewhere").scope)
        assertEquals(AlwaysScope.Everywhere, AlwaysDraft.of(listOf("Read"), "everywhere").scope)
    }

    @Test fun editingWideningAndGoingBack() {
        var d = AlwaysDraft.of(listOf("Bash(npm test)", "Bash(git status:*)"), "project")
        d = d.set(0, r("Bash(npm test)")!!.widenings().first())
        assertEquals(listOf("Bash(npm test *)", "Bash(git status:*)"), d.rules)
        assertEquals("Bash(npm test)", d.suggestedAt(0))
        d = d.edit(1, "Bash(git status")
        assertFalse(d.valid)
        assertNull(d.parsed[1])
        d = d.edit(1, "Bash(npm test *)")
        // Sent once each.
        assertTrue(d.valid)
        assertEquals(listOf("Bash(npm test *)"), d.rules)
        d = d.edit(0, d.suggestedAt(0)!!)
        assertNull(d.suggestedAt(0))
        assertFalse(AlwaysDraft(emptyList(), emptyList(), AlwaysScope.Session).valid)
    }

    @Test fun theApprovalCarriesTheRulesFromTheMac() {
        val a = Wire.approval(
            JSONObject("""{"id":"a1","sessionId":"s","kind":"tool","toolName":"Bash","summary":"Bash: npm test","input":{"command":"npm test"},"canAlways":true,"alwaysRules":["Bash(npm test *)","",7],"alwaysScope":"project"}"""),
        )
        assertEquals(listOf("Bash(npm test *)"), a.alwaysRules)
        assertEquals("project", a.alwaysScope)
        // An older Mac: none.
        val old = Wire.approval(JSONObject("""{"id":"a2","sessionId":"s","kind":"tool","toolName":"Bash","summary":"Bash: ls","canAlways":true}"""))
        assertTrue(old.alwaysRules.isEmpty())
        assertNull(old.alwaysScope)
    }
}
