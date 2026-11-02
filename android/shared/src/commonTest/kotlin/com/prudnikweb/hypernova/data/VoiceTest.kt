package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Voice's logic on the JVM: the words as the box gets them. The phone's own dictation
 * (PhoneDictation.kt) is Android's recognizer: its engine choice and extras are DictationChoiceTest's,
 * its going on through pauses ContinuousDictationTest's and PhoneDictationTest's.
 */
class VoiceTest {

    @Test
    fun words_join_with_one_space_and_mark_what_may_change() {
        assertEquals("Add a dark mode", heardText("Add a dark", "mode"))
        assertEquals("mode", heardText("", "mode"))
        assertEquals("Add", heardText("Add ", ""))
        assertEquals(11, unsettledFrom("Add a dark", "mode"))
        assertEquals(0, unsettledFrom("", "mode"))
        assertEquals(3, unsettledFrom("Add", ""))
    }
}
