package com.prudnikweb.hypernova.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Links as the chat draws them (Transcript.kt's markdownLite, LinkConfirm.kt's linked). */
class LinkedTextTest {
    private fun links(s: AnnotatedString) = s.getLinkAnnotations(0, s.length).map { (it.item as LinkAnnotation.Url).url to s.text.substring(it.start, it.end) }

    @Test fun bareAddressesInRepliesAndMessages() {
        val reply = markdownLite("The repo now exists at https://github.com/secondlife/webcorelib, with a worktree.")
        assertEquals("The repo now exists at https://github.com/secondlife/webcorelib, with a worktree.", reply.text)
        assertEquals(listOf("https://github.com/secondlife/webcorelib" to "https://github.com/secondlife/webcorelib"), links(reply))
        val mine = linked("look at www.example.com/x.")
        assertEquals(listOf("www.example.com/x" to "www.example.com/x"), links(mine))
        assertTrue(links(linked("nothing to tap")).isEmpty())
    }

    @Test fun markdownLinksShowTheirWordsAndOtherSchemesNeverLink() {
        val s = markdownLite("Read [the docs](https://a.org/docs), not [this](javascript:alert(1)).")
        assertEquals("Read the docs, not this).", s.text)
        assertEquals(listOf("https://a.org/docs" to "the docs"), links(s))
        // In bold and in code too; Hypernova's own ports never.
        assertEquals(listOf("http://localhost:5173" to "http://localhost:5173"), links(markdownLite("**`http://localhost:5173`**")))
        assertTrue(links(markdownLite("http://localhost:47322/qa")).isEmpty())
        // An agent's card opens the agent: no links in it.
        assertTrue(links(markdownLite("https://a.org", links = false)).isEmpty())
    }
}
