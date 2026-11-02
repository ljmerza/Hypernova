package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' Enter key and what one Enter does in a box that sends (data/EnterKey.kt). */
class EnterKeyTest {
    @Test fun sendUnlessSomethingElseWasSaved() {
        assertEquals(EnterKey.Send, EnterKey.DEFAULT)
        assertEquals(EnterKey.Send, EnterKey.of(null))
        assertEquals(EnterKey.Send, EnterKey.of("Sideways"))
        assertEquals(EnterKey.Send, EnterKey.of(EnterKey.Send.name))
        assertEquals(EnterKey.NewLine, EnterKey.of(EnterKey.NewLine.name))
    }

    @Test fun onSendEnterSendsAndShiftEnterIsANewLine() {
        assertEquals(EnterDoes.Send, EnterKeys.decide(EnterKey.Send, shift = false, canSend = true))
        assertEquals(EnterDoes.NewLine, EnterKeys.decide(EnterKey.Send, shift = true, canSend = true))
        assertEquals(EnterDoes.NewLine, EnterKeys.decide(EnterKey.Send, shift = true, canSend = false))
    }

    @Test fun onSendAnEmptyBoxDoesNothing() {
        // No send, and no new line either.
        assertEquals(EnterDoes.Nothing, EnterKeys.decide(EnterKey.Send, shift = false, canSend = false))
    }

    @Test fun onNewLineEnterIsAlwaysANewLine() {
        for (shift in listOf(false, true)) for (canSend in listOf(false, true)) {
            assertEquals(EnterDoes.NewLine, EnterKeys.decide(EnterKey.NewLine, shift, canSend))
        }
    }

    @Test fun aNewLineTypedOnItsOwn() {
        assertTrue(EnterKeys.typedNewline("hello", "hello\n"))
        assertTrue(EnterKeys.typedNewline("helo", "he\nlo"))
        assertTrue(EnterKeys.typedNewline("", "\n"))
        assertTrue(EnterKeys.typedNewline("a\n", "a\n\n"))
        // A letter, a paste of several lines, a deletion, a new line that replaced something: not Enter.
        assertFalse(EnterKeys.typedNewline("hello", "hellos"))
        assertFalse(EnterKeys.typedNewline("hi", "hi\nthere\n"))
        assertFalse(EnterKeys.typedNewline("hello\n", "hello"))
        assertFalse(EnterKeys.typedNewline("hello", "hell\n"))
        assertFalse(EnterKeys.typedNewline("ab", "a\nc"))
        assertFalse(EnterKeys.typedNewline("same", "same"))
    }
}
