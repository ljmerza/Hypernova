package com.prudnikweb.hypernova.data

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dictation goes on until the owner ends it (ContinuousDictation.kt; owner, 2026-11-01: "it'll stop
 * recording while I'm talking"): what happens as each recognizer session ends, on the JVM. The clock
 * is passed in, so "at once" and "seconds later" are exact. PhoneDictationTest has it on the recognizer.
 */
class ContinuousDictationTest {
    private val noMatch = SpeechRecognizer.ERROR_NO_MATCH
    private val timeout = SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    private val network = SpeechRecognizer.ERROR_NETWORK
    private val busy = SpeechRecognizer.ERROR_RECOGNIZER_BUSY
    private val audio = SpeechRecognizer.ERROR_AUDIO
    private val client = SpeechRecognizer.ERROR_CLIENT
    private val atOnce = ListenStep.Listen(0)

    private var now = 10_000L
    private fun plain() = ContinuousDictation(ListenMode.Plain).also { it.started(now) }
    private fun segmented() = ContinuousDictation(ListenMode.Segmented).also { it.started(now) }
    /** The session runs [ms], then the next one starts. */
    private fun ContinuousDictation.later(ms: Long = 5_000) { now += ms }

    @Test
    fun a_pause_then_more_words_keeps_listening_and_joins_them() {
        val d = plain()
        d.ready()
        d.partial("Add a dark")
        assertEquals("Add a dark", d.text)
        d.later()
        assertEquals(atOnce, d.results("Add a dark mode", now))
        assertEquals("Add a dark mode", d.final)
        assertEquals("", d.interim)
        assertFalse(d.listening)

        d.started(now)
        d.partial("Make it")
        assertEquals("make it", d.interim)
        assertEquals("Add a dark mode make it", d.text)
        d.later()
        assertEquals(atOnce, d.results("Make it blue", now))
        assertEquals("Add a dark mode make it blue", d.final)
    }

    @Test
    fun several_restarts_lose_nothing_and_repeat_nothing() {
        val d = plain()
        for ((i, w) in listOf("One.", "Two.", "Three.", "Four.").withIndex()) {
            if (i > 0) d.started(now)
            d.partial(w.dropLast(1))
            d.later()
            assertEquals(atOnce, d.results(w, now))
        }
        assertEquals("One. Two. Three. Four.", d.final)
        assertEquals(3, d.restarts)
    }

    @Test
    fun results_with_no_words_keep_what_was_interim() {
        val d = plain()
        d.partial("still here")
        d.later()
        d.results("", now)
        assertEquals("still here", d.final)
        assertEquals("", d.interim)
    }

    @Test
    fun a_blank_partial_does_not_wipe_the_words() {
        val d = plain()
        d.partial("hello")
        d.partial("")
        assertEquals("hello", d.interim)
    }

    @Test
    fun a_no_speech_timeout_then_speech() {
        val d = plain()
        d.later()
        assertEquals(atOnce, d.error(timeout, now))
        assertEquals("", d.text)
        d.started(now)
        d.partial("now I talk")
        d.later()
        d.results("Now I talk", now)
        assertEquals("Now I talk", d.final)
    }

    @Test
    fun a_no_match_keeps_what_was_interim_and_listens_on() {
        val d = plain()
        d.partial("half a thought")
        d.later()
        assertEquals(atOnce, d.error(noMatch, now))
        assertEquals("half a thought", d.final)
        assertEquals("", d.interim)
    }

    @Test
    fun long_silence_keeps_listening_there_is_no_silence_stop() {
        val d = plain()
        d.partial("before")
        d.later()
        d.results("before", now)
        repeat(24) {
            d.started(now)
            d.later()
            assertEquals(atOnce, d.error(if (it % 2 == 0) timeout else noMatch, now))
        }
        assertEquals("before", d.final)
    }

    @Test
    fun finish_between_sessions_ends_now_with_everything() {
        val d = plain()
        d.partial("first part")
        d.later()
        d.results("first part", now)
        assertEquals(ListenStep.End("first part", null), d.finishing())
        assertTrue(d.released)
    }

    @Test
    fun finish_with_a_session_open_waits_for_its_answer() {
        val d = plain()
        d.later()
        d.results("one.", now)
        d.started(now)
        d.partial("two")
        assertEquals(ListenStep.Wait, d.finishing())
        assertEquals("one. Two", d.text)
        assertEquals(ListenStep.End("one. Two three", null), d.results("two three", now))
    }

    @Test
    fun after_finish_no_error_restarts_and_the_words_stay() {
        for (code in listOf(noMatch, timeout, client)) {
            val d = plain()
            d.partial("words")
            d.finishing()
            assertEquals(ListenStep.End("words", null), d.error(code, now))
        }
        val d = plain()
        d.partial("words")
        d.finishing()
        assertEquals(ListenStep.End("words", VoiceProblem.MicBusy), d.error(busy, now))
    }

    @Test
    fun a_real_error_ends_it_with_what_was_heard_and_its_problem() {
        val cases = mapOf(
            network to VoiceProblem.Mic,
            SpeechRecognizer.ERROR_SERVER to VoiceProblem.Mic,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to VoiceProblem.Mic,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to VoiceProblem.Mic,
            audio to VoiceProblem.MicBusy,
            busy to VoiceProblem.MicBusy,
        )
        for ((code, problem) in cases) {
            val d = plain()
            d.ready()
            d.partial("heard")
            assertEquals(ListenStep.End("heard", problem), d.error(code, now))
        }
    }

    @Test
    fun a_real_error_after_a_restart_keeps_the_earlier_words() {
        val d = plain()
        d.later()
        d.results("Earlier words.", now)
        d.started(now)
        d.ready()
        d.partial("and more")
        assertEquals(ListenStep.End("Earlier words. And more", VoiceProblem.Mic), d.error(network, now))
    }

    @Test
    fun a_real_error_on_the_first_session_ends_at_once() {
        for (code in listOf(busy, audio, network)) assertTrue(plain().error(code, now) is ListenStep.End)
        assertEquals(ListenStep.End("", null), plain().error(client, now))
    }

    @Test
    fun sessions_failing_at_once_back_off_then_end_rather_than_loop() {
        val d = plain()
        val waits = mutableListOf<Long>()
        repeat(ContinuousDictation.MAX_RAPID - 1) {
            now += 100
            waits += (d.error(noMatch, now) as ListenStep.Listen).afterMs
            d.started(now)
        }
        assertEquals(listOf(100L, 200L, 400L, 800L), waits)
        now += 100
        assertEquals(ListenStep.End("", VoiceProblem.Mic), d.error(noMatch, now))
    }

    @Test
    fun the_rapid_bound_keeps_the_words() {
        val d = plain()
        d.later()
        d.results("Kept words", now)
        repeat(ContinuousDictation.MAX_RAPID) {
            d.started(now)
            now += 50
            val step = d.error(timeout, now)
            if (it == ContinuousDictation.MAX_RAPID - 1) assertEquals(ListenStep.End("Kept words", VoiceProblem.Mic), step)
        }
    }

    @Test
    fun words_or_a_real_listen_clear_the_rapid_count() {
        val d = plain()
        repeat(ContinuousDictation.MAX_RAPID - 1) {
            now += 100
            d.error(noMatch, now)
            d.started(now)
        }
        // A session that heard words, however short, isn't a failing one.
        d.partial("hello")
        now += 100
        assertEquals(atOnce, d.results("Hello", now))
        repeat(ContinuousDictation.MAX_RAPID - 1) {
            d.started(now)
            now += 100
            assertTrue(d.error(noMatch, now) is ListenStep.Listen)
        }
        // A full second's listening isn't either.
        d.started(now)
        now += ContinuousDictation.RAPID_MS
        assertEquals(atOnce, d.error(noMatch, now))
    }

    @Test
    fun a_new_session_refused_before_it_opens_clears_and_tries_again_within_the_bound() {
        for (code in listOf(busy, audio, client)) {
            val d = plain()
            d.later()
            d.results("Words", now)
            d.started(now)
            assertEquals(ListenStep.Listen(100, clearFirst = true), d.error(code, now))
            d.started(now)
            assertEquals(ListenStep.Listen(200, clearFirst = true), d.error(code, now))
            d.started(now)
            d.ready()
            // It opened, so the next one is a real error (or, for the client, the quiet end).
            assertEquals(ListenStep.End("Words", ContinuousDictation.problem(code)), d.error(code, now))
        }
    }

    @Test
    fun new_sessions_always_refused_end_with_their_problem() {
        val d = plain()
        d.later()
        d.results("Words", now)
        repeat(ContinuousDictation.MAX_RAPID - 1) {
            d.started(now)
            assertTrue(d.error(busy, now) is ListenStep.Listen)
        }
        d.started(now)
        assertEquals(ListenStep.End("Words", VoiceProblem.MicBusy), d.error(busy, now))

        val c = plain()
        c.later()
        c.results("Words", now)
        repeat(ContinuousDictation.MAX_RAPID - 1) {
            c.started(now)
            c.error(client, now)
        }
        c.started(now)
        // The client's error is quiet by itself, but this many in a row is a problem to say.
        assertEquals(ListenStep.End("Words", VoiceProblem.Mic), c.error(client, now))
    }

    @Test
    fun a_stale_callback_between_sessions_starts_nothing_twice() {
        val d = plain()
        d.partial("words")
        d.later()
        assertEquals(atOnce, d.results("words", now))
        assertEquals(ListenStep.Wait, d.error(noMatch, now))
        assertEquals(ListenStep.Wait, d.endOfSession(now))
        d.partial("late partial")
        assertEquals("words", d.text)
        // Late words themselves are still words.
        assertEquals(ListenStep.Wait, d.results("and these", now))
        assertEquals("words and these", d.final)
    }

    // ─── Segmented sessions ──────────────────────────────────────────────────

    @Test
    fun segments_join_and_the_session_goes_on_with_no_restart() {
        val d = segmented()
        d.ready()
        d.partial("first")
        d.segment("First part.")
        assertEquals("First part.", d.final)
        assertEquals("", d.interim)
        assertTrue(d.listening)
        d.partial("second")
        assertEquals("First part. Second", d.text)
        d.segment("Second part.")
        assertEquals("First part. Second part.", d.final)
        assertEquals(0, d.restarts)
        assertEquals(2, d.segments)
        assertEquals(ListenMode.Segmented, d.mode)
    }

    @Test
    fun a_blank_segment_settles_what_was_interim() {
        val d = segmented()
        d.partial("spoken")
        d.segment("")
        assertEquals("spoken", d.final)
        assertEquals("", d.interim)
    }

    @Test
    fun partials_that_repeat_the_sessions_segments_show_only_whats_new() {
        val d = segmented()
        d.segment("go to the")
        d.partial("go to the store")
        assertEquals("store", d.interim)
        assertEquals("go to the store", d.text)
        d.partial("go to the")
        assertEquals("store", d.interim)
        // Only whole words count as a repeat.
        d.partial("go to theatre")
        assertEquals("go to theatre", d.interim)
    }

    @Test
    fun a_segment_is_taken_whole_even_when_it_opens_like_the_last() {
        val d = segmented()
        d.segment("yes")
        d.segment("yes please")
        assertEquals("yes yes please", d.final)
    }

    @Test
    fun the_end_of_a_segmented_session_starts_another_of_the_same_kind() {
        val d = segmented()
        d.segment("One")
        d.partial("not settled")
        d.later()
        assertEquals(atOnce, d.endOfSession(now))
        assertEquals("One not settled", d.final)
        assertEquals(ListenMode.Segmented, d.mode)
        d.started(now)
        d.segment("Two")
        assertEquals("One not settled two", d.final)
    }

    @Test
    fun finish_in_a_segmented_session_takes_the_last_segment_then_ends() {
        val d = segmented()
        d.segment("One")
        d.partial("two")
        assertEquals(ListenStep.Wait, d.finishing())
        d.segment("Two")
        assertEquals(ListenStep.End("One two", null), d.endOfSession(now))
    }

    @Test
    fun whole_session_results_after_segments_add_only_whats_new() {
        val d = segmented()
        d.segment("one")
        d.segment("two")
        d.later()
        d.results("one two three", now)
        assertEquals("one two three", d.final)

        val e = segmented()
        e.segment("one")
        e.segment("two")
        e.later()
        e.results("one two", now)
        assertEquals("one two", e.final)

        val f = segmented()
        f.segment("one")
        f.segment("two")
        f.partial("three")
        f.later()
        f.results("two", now)
        assertEquals("one two three", f.final)
    }

    @Test
    fun results_with_no_segments_mean_the_recognizer_does_not_do_them() {
        val d = segmented()
        d.later()
        assertEquals(atOnce, d.results("Plain words", now))
        assertEquals(ListenMode.Plain, d.mode)
    }

    @Test
    fun an_end_of_session_with_no_segments_falls_back_too() {
        val d = segmented()
        d.partial("words")
        d.later()
        assertEquals(atOnce, d.endOfSession(now))
        assertEquals("words", d.final)
        assertEquals(ListenMode.Plain, d.mode)
    }

    @Test
    fun a_recognizer_that_has_sent_segments_stays_segmented() {
        val d = segmented()
        d.segment("One")
        d.later()
        d.results("One", now)
        assertEquals(ListenMode.Segmented, d.mode)
    }

    @Test
    fun a_segmented_ask_refused_before_it_opens_tries_plain_once() {
        val d = segmented()
        assertEquals(ListenStep.Listen(0, clearFirst = true), d.error(network, now))
        assertEquals(ListenMode.Plain, d.mode)
        d.started(now)
        assertEquals(ListenStep.End("", VoiceProblem.Mic), d.error(network, now))
    }

    @Test
    fun a_real_error_in_an_opened_segmented_session_ends_it() {
        val d = segmented()
        d.ready()
        d.partial("words")
        assertEquals(ListenStep.End("words", VoiceProblem.Mic), d.error(network, now))
    }

    @Test
    fun a_quiet_segmented_session_stays_segmented_a_rapid_one_falls_back() {
        val d = segmented()
        d.later()
        assertEquals(atOnce, d.error(timeout, now))
        assertEquals(ListenMode.Segmented, d.mode)
        d.started(now)
        now += 100
        assertEquals(ListenStep.Listen(100), d.error(noMatch, now))
        assertEquals(ListenMode.Plain, d.mode)
    }

    // ─── The join ────────────────────────────────────────────────────────────

    @Test
    fun an_utterance_reads_on_from_the_one_before() {
        fun j(before: String, words: String) = ContinuousDictation.utteranceAfter(before, words)
        assertEquals("Hello", j("", "Hello"))
        assertEquals("hello", j("", "hello"))
        assertEquals("", j("Before", ""))
        // Mid-sentence: the recognizer's capital goes.
        assertEquals("make it blue", j("Add a dark mode", "Make it blue"))
        assertEquals("make it blue", j("Add a dark mode,", "Make it blue"))
        assertEquals("сделай синим", j("Добавь тему", "Сделай синим"))
        // After a full stop it's a sentence, whichever way it came.
        assertEquals("Make it blue", j("Add a dark mode.", "Make it blue"))
        assertEquals("Make it blue", j("Add a dark mode. ", "make it blue"))
        assertEquals("Why", j("Really?", "why"))
        assertEquals("So", j("Wow!", "so"))
        assertEquals("Then", j("Well…", "then"))
        // "I" and acronyms keep theirs.
        assertEquals("I think so", j("Yes", "I think so"))
        assertEquals("I'm sure", j("Yes", "I'm sure"))
        assertEquals("I’m sure", j("Yes", "I’m sure"))
        assertEquals("I", j("Yes", "I"))
        assertEquals("NASA launched", j("Yes", "NASA launched"))
        assertEquals("is fine", j("Yes", "Is fine"))
        assertEquals("7 days", j("Yes", "7 days"))
    }
}
