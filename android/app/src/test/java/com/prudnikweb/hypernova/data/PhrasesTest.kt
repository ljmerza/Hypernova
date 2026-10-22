package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a notification says in place of "·"-joined fields: a natural list. */
class PhrasesTest {
    private val and = " and "

    @Test fun aListReadsAsAPhrase() {
        assertEquals("", Phrases.list(emptyList(), and))
        assertEquals("2 working", Phrases.list(listOf("2 working"), and))
        assertEquals("2 working and 1 needs you", Phrases.list(listOf("2 working", "1 needs you"), and))
        assertEquals("2 working, 3 agents and 1 needs you", Phrases.list(listOf("2 working", "3 agents", "1 needs you"), and))
    }

    @Test fun blankPartsAreLeftOut() {
        assertEquals("2 working and 1 needs you", Phrases.list(listOf("2 working", " ", "1 needs you", ""), and))
    }
}
