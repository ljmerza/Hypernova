package com.prudnikweb.hypernova.data

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import com.prudnikweb.hypernova.ui.voice.MicEvent
import com.prudnikweb.hypernova.ui.voice.MicState
import com.prudnikweb.hypernova.ui.voice.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.util.concurrent.TimeUnit

/**
 * PhoneDictation on Robolectric's stand-in for Android's recognizer (ShadowSpeechRecognizer): the
 * sessions it asks for, what it hands over, and that nothing listens on after the end. What a real
 * recognizer does between sessions (the gap, a beep) only a phone can say.
 */
// Android 35, as the data layer's live tests: 36's Robolectric image needs jdk.internal.access opened on JDK 21.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneDictationTest {
    private lateinit var d: PhoneDictation
    private lateinit var rec: ShadowSpeechRecognizer

    @Before
    fun start() {
        d = PhoneDictation(ApplicationProvider.getApplicationContext(), DictationEngine.Phone)
        idle()
        rec = Shadow.extract(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    private fun said(words: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(words)) }
    private val last: Intent get() = rec.lastRecognizerIntent
    private fun Intent.segmented() = hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION)

    /** A recognizer that ends by itself, at each of [utterances], as most do. */
    private fun utterances(vararg utterances: String) {
        for (u in utterances) {
            rec.triggerOnReadyForSpeech(Bundle())
            rec.triggerOnPartialResults(said(u))
            idleFor(3_000)
            rec.triggerOnResults(said(u))
            idle()
        }
    }

    @Test
    fun the_first_session_asks_for_segments_and_is_opening_until_ready() {
        assertTrue(last.segmented())
        assertEquals(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, last.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION))
        assertEquals(VoiceTiming.MAX_MS.toInt(), last.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0))
        assertEquals(DictationState.Opening, d.state.value)
        rec.triggerOnReadyForSpeech(Bundle())
        assertEquals(DictationState.Live("", ""), d.state.value)
    }

    @Test
    fun a_recognizer_that_ends_at_a_pause_is_started_again_and_nothing_ends() {
        val first = last
        rec.triggerOnReadyForSpeech(Bundle())
        rec.triggerOnPartialResults(said("Add a dark"))
        assertEquals(DictationState.Live("", "Add a dark"), d.state.value)
        rec.triggerOnResults(said("Add a dark mode"))
        assertEquals(DictationState.Live("Add a dark mode", ""), d.state.value)
        idle()
        assertNotSame("a new session", first, last)
        assertFalse("it does segments no more", last.segmented())
        assertEquals(4_000L, last.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0))
        rec.triggerOnPartialResults(said("Make it"))
        assertEquals(DictationState.Live("Add a dark mode", "make it"), d.state.value)
    }

    @Test
    fun finish_stops_the_session_and_hands_over_every_utterance() {
        utterances("One.", "Two.", "Three.")
        rec.triggerOnPartialResults(said("four"))
        d.finish()
        assertEquals(DictationState.Settling("One. Two. Three. Four", "One. Two. Three. ".length), d.state.value)
        rec.triggerOnResults(said("four five"))
        assertEquals(DictationState.Ended("One. Two. Three. Four five", null), d.state.value)
        idle()
        assertTrue(rec.isDestroyed)
    }

    @Test
    fun a_no_speech_timeout_then_speech_is_one_dictation() {
        val first = last
        rec.triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        idleFor(0)
        assertEquals(DictationState.Opening, d.state.value)
        idle()
        idleFor(5_000)
        rec.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        idle()
        assertNotSame(first, last)
        utterances("Now I talk")
        d.finish()
        rec.triggerOnResults(said(""))
        assertEquals("Now I talk", (d.state.value as DictationState.Ended).text)
    }

    @Test
    fun finish_with_a_restart_pending_ends_now_with_the_words_and_listens_no_more() {
        rec.triggerOnReadyForSpeech(Bundle())
        rec.triggerOnResults(said("Everything so far"))
        val before = last
        d.finish()
        assertEquals(DictationState.Ended("Everything so far", null), d.state.value)
        idleFor(5_000)
        assertSame("no new session", before, last)
        assertTrue(rec.isDestroyed)
    }

    @Test
    fun cancel_with_a_restart_pending_stops_it_all() {
        rec.triggerOnReadyForSpeech(Bundle())
        rec.triggerOnResults(said("Words"))
        val before = last
        d.cancel()
        assertEquals(DictationState.Cancelled, d.state.value)
        idleFor(5_000)
        assertSame("no new session", before, last)
        assertEquals(DictationState.Cancelled, d.state.value)
        assertTrue(rec.isDestroyed)
    }

    @Test
    fun a_real_error_ends_it_with_the_words_and_its_hint() {
        utterances("Heard this.")
        rec.triggerOnReadyForSpeech(Bundle())
        rec.triggerOnPartialResults(said("and this"))
        rec.triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        assertEquals(DictationState.Ended("Heard this. And this", VoiceProblem.MicBusy), d.state.value)
        val before = last
        idleFor(5_000)
        assertSame(before, last)
    }

    @Test
    fun sessions_that_keep_failing_at_once_end_it() {
        utterances("Kept.")
        rec.triggerOnReadyForSpeech(Bundle())
        var runs = 0
        while (d.state.value !is DictationState.Ended && runs < 20) {
            rec.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
            idleFor(900)
            runs++
        }
        assertEquals(ContinuousDictation.MAX_RAPID, runs)
        assertEquals(DictationState.Ended("Kept.", VoiceProblem.Mic), d.state.value)
    }

    @Test
    fun the_cap_ends_it_with_everything_said_and_says_too_long() {
        var said = 0
        // Spread across the two minutes, so sessions come and go until the cap's last one.
        while (said++ < 39) utterances("Part $said.")
        idleFor(VoiceTiming.MAX_MS - 39 * 3_000)
        assertTrue("settling at the cap", d.state.value is DictationState.Settling)
        rec.triggerOnResults(said(""))
        val end = d.state.value as DictationState.Ended
        assertEquals(VoiceProblem.TooLong, end.problem)
        assertTrue(end.text, end.text.startsWith("Part 1. Part 2. ") && end.text.endsWith("Part 39."))
    }

    @Test
    fun a_cap_landing_between_sessions_ends_it_too() {
        idleFor(VoiceTiming.MAX_MS - 10)
        rec.triggerOnResults(said("Just in time"))
        // The restart is posted now, for now: it runs before the cap's still-pending time.
        idle()
        idleFor(10)
        assertEquals(DictationState.Settling("Just in time", "Just in time".length), d.state.value)
        idleFor(VoiceTiming.SETTLE_MS)
        assertEquals(DictationState.Ended("Just in time", VoiceProblem.TooLong), d.state.value)
    }

    @Test
    fun a_recognizer_that_never_answers_finish_gives_up_on_the_timer_as_before() {
        rec.triggerOnPartialResults(said("waiting"))
        d.finish()
        idleFor(VoiceTiming.SETTLE_MS - 1)
        assertTrue(d.state.value is DictationState.Settling)
        idleFor(1)
        assertEquals(DictationState.Ended("waiting", null), d.state.value)
    }

    @Test
    fun segments_carry_a_session_along_with_no_new_one() {
        val first = last
        rec.triggerOnReadyForSpeech(Bundle())
        rec.triggerOnSegmentResults(said("First part."))
        assertEquals(DictationState.Live("First part.", ""), d.state.value)
        rec.triggerOnPartialResults(said("second"))
        assertEquals(DictationState.Live("First part.", "Second"), d.state.value)
        rec.triggerOnSegmentResults(said("Second part."))
        idleFor(30_000)
        assertSame("the same session", first, last)
        assertEquals(DictationState.Live("First part. Second part.", ""), d.state.value)

        // The recognizer ends the session early: another segmented one.
        rec.triggerOnEndOfSegmentedSession()
        idle()
        assertNotSame(first, last)
        assertTrue(last.segmented())

        d.finish()
        rec.triggerOnSegmentResults(said("Third part."))
        assertEquals(DictationState.Settling("First part. Second part. Third part.", "First part. Second part. Third part.".length), d.state.value)
        rec.triggerOnEndOfSegmentedSession()
        assertEquals(DictationState.Ended("First part. Second part. Third part.", null), d.state.value)
    }

    /** Hands-free in a box that sends, on this dictation. [sent] gets the box's text at Send. */
    private fun handsFree(box: StringBuilder, sent: MutableList<String>): VoiceInput {
        val scope = CoroutineScope(Dispatchers.Main)
        val voice = VoiceInput(VoicePrefs.of(ApplicationProvider.getApplicationContext<android.app.Application>().storage), scope)
        voice.text = { box.toString() }
        voice.setText = { box.setLength(0); box.append(it) }
        voice.onSend = { sent += box.toString() }
        voice.phoneDictate = { d }
        voice.on(MicEvent.A11yStart(append = false, linked = true, permitted = true))
        idle()
        return voice
    }

    /** The app's own Main dispatcher, and what snapshotFlow waits on. */
    private fun settle() {
        idle()
        Snapshot.sendApplyNotifications()
        idle()
    }

    @Test
    fun submit_between_sessions_sends_everything_said() {
        val box = StringBuilder()
        val sent = mutableListOf<String>()
        val voice = handsFree(box, sent)
        utterances("First part.", "Second part.")
        rec.triggerOnReadyForSpeech(Bundle())
        rec.triggerOnResults(said("Third part."))
        // The next session hasn't started when Submit lands.
        voice.on(MicEvent.DoneSend)
        settle()
        assertEquals(listOf("First part. Second part. Third part."), sent)
        assertEquals(MicState.Idle, voice.gesture)
    }

    @Test
    fun submit_with_a_session_open_sends_its_last_words_too() {
        val box = StringBuilder()
        val sent = mutableListOf<String>()
        val voice = handsFree(box, sent)
        utterances("First part.")
        rec.triggerOnReadyForSpeech(Bundle())
        rec.triggerOnPartialResults(said("second"))
        voice.on(MicEvent.DoneSend)
        settle()
        assertEquals("nothing sent until the words settle", emptyList<String>(), sent)
        assertEquals("First part. Second", box.toString())
        rec.triggerOnResults(said("second part"))
        settle()
        assertEquals(listOf("First part. Second part"), sent)
    }

    @Test
    fun a_pause_does_not_end_hands_free() {
        val voice = handsFree(StringBuilder(), mutableListOf())
        utterances("One", "Two", "Three")
        idle()
        assertEquals(MicState.HandsFree, voice.gesture)
        assertEquals(DictationState.Live("One two three", ""), voice.state)
    }

    @Test
    fun a_pause_does_not_end_a_hold_and_letting_go_puts_it_all_in_the_box() {
        val box = StringBuilder()
        val voice = VoiceInput(VoicePrefs.of(ApplicationProvider.getApplicationContext<android.app.Application>().storage), CoroutineScope(Dispatchers.Main))
        voice.text = { box.toString() }
        voice.setText = { box.setLength(0); box.append(it) }
        voice.phoneDictate = { d }
        voice.on(MicEvent.Down(at = 0, send = false, canSend = false, voice = true, linked = true, permitted = true))
        idle()
        utterances("One", "Two")
        idle()
        assertTrue("still held", voice.gesture is MicState.Holding)
        voice.on(MicEvent.Up(at = 7_000))
        rec.triggerOnResults(said("three"))
        settle()
        assertEquals("One two three", box.toString())
    }
}
