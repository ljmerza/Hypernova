package com.prudnikweb.hypernova.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice's logic on the JVM: the words as the box gets them, and the demo's dictation. The phone's
 * own dictation (PhoneDictation.kt) is Android's recognizer and has no JVM test; its engine choice
 * and extras are DictationChoiceTest's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun the_demo_dictation_speaks_then_settles_on_finish() = runTest {
        val d = FakeDictation(backgroundScope, testScheduler.currentTime) { testScheduler.currentTime }
        assertEquals(DictationState.Opening, d.state.value)
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(d.state.value is DictationState.Live)
        d.finish()
        assertTrue(d.state.value is DictationState.Settling)
        advanceTimeBy(200)
        runCurrent()
        val end = d.state.value as DictationState.Ended
        assertTrue(end.text.startsWith("Add a dark mode"))
    }
}
