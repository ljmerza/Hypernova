package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An approval card shows what an edit would change (data/Items.kt `change`), and how one answered
 * on another device first reads (not "Denied").
 */
class ApprovalChangeTest {
    private fun lines(l: List<DiffLine>?) = l?.joinToString("|") { "${it.kind}${it.text}" }

    @Test fun anEditShowsWhatGoesOutAndWhatComesIn() {
        val c = Items.change("Edit", mapOf("file_path" to "/p/a.kt", "old_string" to "a\nb\nc", "new_string" to "a\nB\nc"), emptyList())
        assertEquals(" a|-b|+B| c", lines(c))
        // Long shared starts and ends keep two lines of context each.
        val long = Items.replaced("1\n2\n3\n4\nold\n5\n6\n7", "1\n2\n3\n4\nnew\n5\n6\n7")
        assertEquals(" 3| 4|-old|+new| 5| 6", lines(long))
        // Text added where there was none.
        assertEquals("+x|+y", lines(Items.replaced("", "x\ny")))
    }

    @Test fun aMultiEditShowsEachEditAndAWriteItsContent() {
        val m = Items.change("MultiEdit", emptyMap(), listOf("a" to "b", "c" to "d"))
        assertEquals("-a|+b|~|-c|+d", lines(m))
        assertEquals("+line 1|+line 2", lines(Items.change("Write", mapOf("content" to "line 1\nline 2\n"), emptyList())))
        // Other tools, or the text didn't come: no change to show.
        assertNull(Items.change("Bash", mapOf("command" to "ls"), emptyList()))
        assertNull(Items.change("Edit", mapOf("file_path" to "/p/a.kt"), emptyList()))
        assertNull(Items.change("MultiEdit", emptyMap(), emptyList()))
    }

    @Test fun theMacsEditsReachTheApprovalCard() {
        val a = Wire.approval(
            JSONObject(
                """{"id":"ap1","sessionId":"s","kind":"tool","toolName":"MultiEdit","summary":"MultiEdit: a.kt",
                "input":{"file_path":"/p/a.kt","edits":[{"old_string":"x","new_string":"y"},{"old_string":"p","new_string":"q"}]},"canAlways":true}""",
            ),
        )
        assertEquals(listOf("x" to "y", "p" to "q"), a.edits)
        val items = Items.build(listOf(WEvent.Approval("e1", 1L, "ap1", a.summary, null)), mapOf("ap1" to a), emptyMap(), emptyMap(), "s", "/p", now = 2L)
        val card = items.single() as Item.Approval
        assertEquals("-x|+y|~|-p|+q", lines(card.change))
    }

    @Test fun anApprovalAnsweredElsewhereFirstIsNeutral() {
        val items = Items.build(listOf(WEvent.Approval("e1", 1L, "ap1", "Bash: ls", null)), emptyMap(), mapOf("ap1" to Resolved("elsewhere", null)), emptyMap(), "s", "/p", now = 2L)
        assertEquals(Resolution.AnsweredElsewhere, (items.single() as Item.Approval).resolved)
    }

    @Test fun aSessionIsBrowsableUnlessTheMacSaysNot() {
        assertTrue(Wire.session(JSONObject("""{"id":"s1","cwd":"/p"}""")).browsable)
        assertEquals(false, Wire.session(JSONObject("""{"id":"s1","cwd":"/Users/n","browsable":false}""")).browsable)
    }
}
