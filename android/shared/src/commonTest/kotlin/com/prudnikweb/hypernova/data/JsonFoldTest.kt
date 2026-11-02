package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folding JSON text: what's drawn changes, and the text never does. */
class JsonFoldTest {
    private val doc = """
        {
          "theme": "dark",
          "hooks": {
            "Stop": [
              { "type": "command" },
              {
                "type": "other"
              }
            ],
            "Start": [],
            "Inline": [1, 2]
          },
          "env": {
            "A": "1",
            "B": "2"
          }
        }
    """.trimIndent() + "\n"

    private fun regions(text: String = doc) = (JsonFold.parse(text) as JsonFold.Parsed.Ok).regions

    @Test fun regionsSayWhereWhatAndHowMany() {
        val r = regions().associateBy { it.path }
        assertEquals(setOf("hooks", "hooks.Stop", "hooks.Stop[1]", "env"), r.keys)
        assertEquals(3, r["hooks"]!!.count)
        assertTrue(r["hooks"]!!.isObject)
        assertEquals(2, r["hooks.Stop"]!!.count)
        assertTrue(!r["hooks.Stop"]!!.isObject)
        assertEquals(2, r["hooks"]!!.startLine)
        assertEquals(11, r["hooks"]!!.endLine)
        assertEquals(1, r["env"]!!.depth)
        assertEquals(2, r["hooks.Stop"]!!.depth)
    }

    @Test fun whateverIsFoldedJoiningGivesTheTextBack() {
        val all = regions().map { it.path }
        val sets = listOf(emptySet(), setOf("hooks"), setOf("env"), setOf("hooks.Stop"), setOf("hooks.Stop[1]"), all.toSet(), setOf("not-there"))
        for (folded in sets) {
            val (rows, bad) = JsonRows.build(doc, folded)
            assertNull(bad)
            assertEquals(folded.toString(), doc, JsonRows.join(rows))
        }
        for (other in listOf("{}", "{\n}", "{\n  \"a\": [\n    1\n  ]\n}", "{\r\n  \"a\": {\r\n    \"b\": 1\r\n  }\r\n}\r\n", "\n\n{\n\"a\": {\n}\n}\n\n", "[\n[\n1\n],\n[\n2\n]\n]")) {
            val parsed = JsonFold.parse(other)
            val paths = (parsed as JsonFold.Parsed.Ok).regions.map { it.path }.toSet()
            assertEquals(other, other, JsonRows.join(JsonRows.build(other, paths).first))
            assertEquals(other, other, JsonRows.join(JsonRows.build(other, emptySet()).first))
        }
    }

    @Test fun aFoldedRowHidesItsInsideAndAnEditAroundItKeepsThat() {
        val (rows, _) = JsonRows.build(doc, setOf("hooks"))
        val folded = rows.single { it.region?.path == "hooks" }
        assertEquals("  \"hooks\": {", folded.text)
        assertTrue(folded.hidden!!.contains("\"Stop\""))
        assertEquals("},", JsonRows.closing(folded))
        assertEquals(setOf(null, "hooks", "env"), rows.map { it.region?.path }.toSet())
        // Edit the row before it and the row with it: what's folded comes back in place.
        val edited = rows.map { if (it.region == null && it.firstLine == 1) it.copy(text = it.text.replace("dark", "light")) else if (it === folded) it.copy(text = "  \"hooks2\": {") else it }
        val text = JsonRows.join(edited)
        assertEquals(doc.replace("dark", "light").replace("\"hooks\"", "\"hooks2\""), text)
        assertTrue(JsonFold.parse(text) is JsonFold.Parsed.Ok)
    }

    @Test fun lineNumbersCountWhatsFoldedAndFollowAnEdit() {
        val (rows, _) = JsonRows.build(doc, setOf("hooks"))
        assertEquals(1, rows.first().firstLine)
        assertEquals(3, rows.single { it.region?.path == "hooks" }.firstLine)
        assertEquals(13, rows.single { it.region?.path == "env" }.firstLine)
        val more = JsonRows.renumber(rows.map { if (it.firstLine == 1) it.copy(text = it.text + "\n  \"new\": 1,") else it })
        assertEquals(4, more.single { it.region?.path == "hooks" }.firstLine)
        assertEquals(14, more.single { it.region?.path == "env" }.firstLine)
    }

    @Test fun aLongFileOpensWithItsTopLevelShowingAndAShortOneOpen() {
        assertEquals(emptySet<String>(), JsonFold.opening(doc, regions()))
        val long = "{\n" + (1..30).joinToString(",\n") { "  \"k$it\": {\n    \"a\": 1\n  }" } + "\n}\n"
        val open = JsonFold.opening(long, regions(long))
        assertEquals(30, open.size)
        val (rows, _) = JsonRows.build(long, open)
        assertTrue(rows.count { it.hidden != null } == 30)
        assertEquals(long, JsonRows.join(rows))
    }

    @Test fun anUnfoldedParentShowsItsChildrenStillFolded() {
        val all = regions().map { it.path }.toSet()
        val (rows, _) = JsonRows.build(doc, all - "hooks")
        assertNull(rows.single { it.region?.path == "hooks" }.hidden)
        assertNotNull(rows.single { it.region?.path == "hooks.Stop" }.hidden)
    }

    @Test fun notJsonIsOnePlainRowAndSaysWhereNothingLost() {
        val bad = "{\n  \"a\": 1,\n  oops\n}"
        val (rows, why) = JsonRows.build(bad, setOf("a"))
        assertEquals(1, rows.size)
        assertEquals(bad, JsonRows.join(rows))
        assertEquals(3, why!!.line)
        assertEquals(3, why.column)
    }

    @Test fun theParserIsStrictAsTheServersIs() {
        for (t in listOf("", "{", "{\"a\":}", "{\"a\":1,}", "[1,]", "{a:1}", "{\"a\":01}", "{\"a\":\"\n\"}", "{\"a\":\"\\x\"}", "{} {}", "{\"a\":tru}", "{\"a\":1}x")) {
            assertTrue(t, JsonFold.parse(t) is JsonFold.Parsed.Bad)
        }
        for (t in listOf("{}", "[]", "1", "\"x\"", "null", "{\"a\":-1.5e3}", "{\"a\":\"\\u00e9\\n\"}", "  {\n} \n")) {
            assertTrue(t, JsonFold.parse(t) is JsonFold.Parsed.Ok)
        }
        assertEquals(JsonFold.Parsed.Bad(1, 7), JsonFold.parse("{\"a\":1"))
        assertEquals(JsonFold.Parsed.Bad(2, 1), JsonFold.parse("{\"a\":1,\n}"))
    }
}
