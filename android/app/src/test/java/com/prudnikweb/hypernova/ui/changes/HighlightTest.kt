package com.prudnikweb.hypernova.ui.changes

import androidx.compose.ui.graphics.Color
import com.prudnikweb.hypernova.ui.theme.HN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What changed's diff colouring (Highlight.kt): one line at a time, by the file's extension. */
class HighlightTest {
    /** Each coloured piece of [text] with its colour. */
    private fun spans(text: String, ext: String): List<Pair<String, Color>> {
        val a = Highlight.line(text, ext)
        assertEquals("the words never change", text, a.text)
        return a.spanStyles.map { a.text.substring(it.start, it.end) to it.item.color }
    }

    @Test fun keywordsStringsNumbersAndAComment() {
        val s = spans("""export const parse = (s: string) => s.trim() + "x" + 42 // done""", "ts")
        val keyword = HN.accentBright
        assertEquals(listOf("export", "const"), s.filter { it.second == keyword }.map { it.first })
        assertTrue(s.any { it.first == "\"x\"" && it.second != keyword && it.second != HN.inkDim })
        assertTrue(s.any { it.first == "42" })
        assertEquals("// done" to HN.inkDim, s.last())
        // `parse` and `s` are names, left as they are.
        assertTrue(s.none { it.first == "parse" || it.first == "s" })
    }

    @Test fun eachLanguagesOwnComment() {
        assertEquals("# note" to HN.inkDim, spans("x = 1 # note", "py").last())
        assertEquals("-- note" to HN.inkDim, spans("select 1 -- note", "sql").last())
        assertEquals("<!-- note -->" to HN.inkDim, spans("<a/><!-- note -->", "html").last())
        // A hash isn't a comment in Kotlin.
        assertTrue(spans("val tag = \"#1\"", "kt").none { it.second == HN.inkDim })
    }

    @Test fun plainTextAndVeryLongLinesAreLeftAlone() {
        assertTrue(spans("if this were code it would be coloured", "md").isEmpty())
        assertTrue(spans("val x = 1;".repeat(70), "kt").isEmpty())
    }

    @Test fun theExtensionOfAPathAndADockerfile() {
        assertEquals("ts", Highlight.extOf("src/Parser.TS"))
        assertEquals("dockerfile", Highlight.extOf("deploy/Dockerfile"))
        assertEquals("", Highlight.extOf("Makefile"))
        assertEquals("# base" to HN.inkDim, spans("FROM node # base", Highlight.extOf("Dockerfile")).last())
    }
}
