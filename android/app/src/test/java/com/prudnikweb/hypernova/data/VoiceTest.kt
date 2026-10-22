package com.prudnikweb.hypernova.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice's logic on the JVM (docs/specs/spec-voice.md, section 7, Android tests 1, 2 and 4): one
 * dictation against a fake stream and a fake microphone, the problem table, and the protocol and
 * route the phone reads from the Mac.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTest {

    /** What a dictation sent, and a way to answer it as the daemon (or the Companion) would. */
    private class Stream : SpeechConnector, SpeechLink {
        lateinit var events: SpeechEvents
        val texts = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        var closed: Int? = null
        override fun open(events: SpeechEvents): SpeechLink { this.events = events; return this }
        override fun send(text: String): Boolean { texts += text; return true }
        override fun send(bytes: ByteArray): Boolean { frames += bytes; return true }
        override fun close(code: Int) { if (closed == null) closed = code }
        fun say(json: String) = events.message(json)
        fun types() = texts.map { JSONObject(it).getString("type") }
    }

    private class Mic : AudioSource {
        var frame: ((ByteArray) -> Unit)? = null
        var failed: ((VoiceProblem) -> Unit)? = null
        var interrupted: (() -> Unit)? = null
        var stopped = false
        var refuse: VoiceProblem? = null
        override fun start(frame: (ByteArray) -> Unit, failed: (VoiceProblem) -> Unit, interrupted: () -> Unit) {
            this.frame = frame; this.failed = failed; this.interrupted = interrupted
            refuse?.let(failed)
        }
        override fun stop() { stopped = true }
        /** [n] frames of 40 ms, each numbered in its first byte. */
        fun speak(n: Int, from: Int = 0) = repeat(n) { k -> frame?.invoke(ByteArray(1280).also { it[0] = (from + k).toByte() }) }
    }

    private fun TestScope.dictation(stream: Stream, mic: Mic = Mic(), companion: Boolean = false, scope: CoroutineScope = backgroundScope) =
        LiveDictation(stream, mic, "auto", companion, scope, startedAt = testScheduler.currentTime, clock = { testScheduler.currentTime }).also { it.start() }

    private fun snapshot(final: String, interim: String, finished: Boolean = false) =
        JSONObject().put("type", "snapshot").put("finalText", final).put("interimText", interim).put("finished", finished).toString()

    // ─── 1. One dictation ──────────────────────────────────────────────────

    @Test
    fun opening_live_finish_settling_ended() = runTest {
        val s = Stream()
        val mic = Mic()
        val d = dictation(s, mic)
        assertEquals(DictationState.Opening, d.state.value)
        // Audio before the link is open waits, then goes after `start`.
        mic.speak(3)
        assertTrue(s.frames.isEmpty())
        s.events.opened()
        assertEquals(listOf("start"), s.types())
        assertEquals("auto", JSONObject(s.texts[0]).getString("language"))
        assertEquals(3, s.frames.size)
        mic.speak(2, from = 3)
        assertEquals(listOf(0, 1, 2, 3, 4), s.frames.map { it[0].toInt() })
        s.say("""{"type":"hello","ok":true}""")
        s.say("""{"type":"started"}""")
        s.say(snapshot("Add a dark", "mode tog"))
        assertEquals(DictationState.Live("Add a dark", "mode tog"), d.state.value)

        d.finish()
        // The box gets everything heard at once, the part that may change marked.
        assertEquals(DictationState.Settling("Add a dark mode tog", 11), d.state.value)
        // The tail: 100 ms more of the microphone, then stop.
        mic.speak(2, from = 5)
        assertFalse(mic.stopped)
        assertFalse("stop" in s.types())
        advanceTimeBy(VoiceTiming.TAIL_MS + 1)
        assertTrue(mic.stopped)
        assertEquals(listOf("start", "stop"), s.types())
        assertEquals(7, s.frames.size)
        s.say(snapshot("Add a dark mode toggle to settings.", "", finished = true))
        assertEquals(DictationState.Ended("Add a dark mode toggle to settings."), d.state.value)
        assertEquals(1000, s.closed)
    }

    @Test
    fun settling_times_out_with_what_was_heard() = runTest {
        val s = Stream()
        val d = dictation(s)
        s.events.opened()
        s.say(snapshot("Ship", "it"))
        d.finish()
        advanceTimeBy(VoiceTiming.TAIL_MS + VoiceTiming.SETTLE_MS - 10)
        assertTrue(d.state.value is DictationState.Settling)
        advanceTimeBy(20)
        assertEquals(DictationState.Ended("Ship it"), d.state.value)
        assertEquals(1000, s.closed)
    }

    @Test
    fun an_error_with_words_keeps_them() = runTest {
        val s = Stream()
        val d = dictation(s)
        s.events.opened()
        s.say(snapshot("Run the", "tests"))
        s.say("""{"type":"error","code":"lost","message":"Soniox closed the stream.","finished":true,"finalText":"Run the tests and","interimText":""}""")
        assertEquals(DictationState.Ended("Run the tests and", VoiceProblem.Soniox), d.state.value)
        // An error without the words still keeps those heard.
        val s2 = Stream()
        val d2 = dictation(s2)
        s2.events.opened()
        s2.say(snapshot("Hello", ""))
        s2.say("""{"type":"error","code":"no_key","message":"Soniox was disconnected on your Mac."}""")
        assertEquals(DictationState.Ended("Hello", VoiceProblem.NoKey), d2.state.value)
    }

    @Test
    fun cancel_never_sends_stop() = runTest {
        val s = Stream()
        val mic = Mic()
        val d = dictation(s, mic)
        s.events.opened()
        mic.speak(4)
        s.say(snapshot("Never", "mind"))
        d.cancel()
        assertEquals(DictationState.Cancelled, d.state.value)
        assertTrue(mic.stopped)
        assertEquals(1000, s.closed)
        assertEquals(listOf("start"), s.types())
        // Nothing after: frames, words and timers all stopped.
        mic.speak(2)
        s.say(snapshot("Never mind", "", finished = true))
        advanceTimeBy(10_000)
        assertEquals(4, s.frames.size)
        assertEquals(DictationState.Cancelled, d.state.value)
    }

    @Test
    fun a_link_that_fails_keeps_the_words() = runTest {
        val s = Stream()
        val d = dictation(s)
        s.events.opened()
        s.say(snapshot("Deploy to", "staging"))
        s.events.failed(java.io.IOException("reset"))
        assertEquals(DictationState.Ended("Deploy to staging", VoiceProblem.Offline), d.state.value)
        // Before any words, on the Companion's route: its own problem.
        val c = Stream()
        val dc = dictation(c, companion = true)
        c.events.failed(java.net.ConnectException("refused"))
        assertEquals(DictationState.Ended("", VoiceProblem.Companion), dc.state.value)
    }

    @Test
    fun the_queue_keeps_ten_seconds_and_drops_the_oldest() = runTest {
        val s = Stream()
        val mic = Mic()
        dictation(s, mic)
        // 12 s of audio while the link is still opening: 300 frames of 40 ms.
        mic.speak(300)
        s.events.opened()
        assertEquals(250, s.frames.size)
        assertEquals((50 until 300).map { it.toByte() }, s.frames.map { it[0] })
    }

    @Test
    fun a_stop_while_the_link_is_opening_waits_for_it() = runTest {
        val s = Stream()
        val mic = Mic()
        val d = dictation(s, mic)
        mic.speak(5)
        d.finish()
        advanceTimeBy(VoiceTiming.TAIL_MS + 1)
        assertTrue(mic.stopped)
        assertTrue(s.texts.isEmpty())
        // The link opens late: start, the audio, then stop, in that order.
        s.events.opened()
        assertEquals(listOf("start", "stop"), s.types())
        assertEquals(5, s.frames.size)
        s.say(snapshot("Yes", "", finished = true))
        assertEquals(DictationState.Ended("Yes"), d.state.value)
    }

    @Test
    fun nothing_heard_waits_longer_then_ends_empty() = runTest {
        val s = Stream()
        val d = dictation(s)
        s.events.opened()
        d.finish()
        assertEquals(DictationState.Settling("", 0), d.state.value)
        advanceTimeBy(VoiceTiming.TAIL_MS + VoiceTiming.SETTLE_MS + 10)
        assertTrue(d.state.value is DictationState.Settling)
        advanceTimeBy(VoiceTiming.SETTLE_MS)
        assertEquals(DictationState.Ended("", null), d.state.value)
    }

    @Test
    fun a_link_that_never_opens_is_offline() = runTest {
        val s = Stream()
        val d = dictation(s)
        d.finish()
        advanceTimeBy(VoiceTiming.TAIL_MS + 2 * VoiceTiming.SETTLE_MS + 10)
        assertEquals(DictationState.Ended("", VoiceProblem.Offline), d.state.value)
    }

    @Test
    fun the_microphone_refusing_ends_it_at_once() = runTest {
        val s = Stream()
        val mic = Mic().apply { refuse = VoiceProblem.MicBusy }
        val d = dictation(s, mic)
        assertEquals(DictationState.Ended("", VoiceProblem.MicBusy), d.state.value)
        // No stream was even opened.
        assertFalse(runCatching { s.events }.isSuccess)
    }

    @Test
    fun two_minutes_end_it_like_done() = runTest {
        val s = Stream()
        val d = dictation(s)
        s.events.opened()
        s.say(snapshot("A long", "story"))
        advanceTimeBy(VoiceTiming.MAX_MS + 1)
        assertTrue(d.state.value is DictationState.Settling)
        advanceTimeBy(VoiceTiming.TAIL_MS + 1)
        assertTrue("stop" in s.types())
        s.say(snapshot("A long story", "", finished = true))
        assertEquals(DictationState.Ended("A long story", VoiceProblem.TooLong), d.state.value)
    }

    @Test
    fun a_call_finishes_it_with_the_words() = runTest {
        val s = Stream()
        val mic = Mic()
        val d = dictation(s, mic)
        s.events.opened()
        s.say(snapshot("Check the", "logs"))
        mic.interrupted?.invoke()
        assertTrue(d.state.value is DictationState.Settling)
        runCurrent()
    }

    @Test
    fun the_companion_gets_start_after_hello_and_audio_after_started() = runTest {
        val s = Stream()
        val mic = Mic()
        dictation(s, mic, companion = true)
        s.events.opened()
        mic.speak(2)
        assertTrue(s.texts.isEmpty())
        s.say("""{"type":"hello","ok":true,"busy":false}""")
        assertEquals(listOf("start"), s.types())
        assertTrue(s.frames.isEmpty())
        s.say("""{"type":"started"}""")
        assertEquals(2, s.frames.size)
    }

    // ─── 2. Problems ───────────────────────────────────────────────────────

    @Test
    fun codes_name_their_problem() {
        val soniox = mapOf(
            "no_key" to VoiceProblem.NoKey, "bad_key" to VoiceProblem.BadKey, "no_credit" to VoiceProblem.NoCredit,
            "busy" to VoiceProblem.Busy, "unreachable" to VoiceProblem.Unreachable, "soniox" to VoiceProblem.Soniox,
            "lost" to VoiceProblem.Soniox, "too_long" to VoiceProblem.TooLong, "idle" to VoiceProblem.Mic,
            "bad_audio" to VoiceProblem.Mic, "replaced" to VoiceProblem.Mic, "something_new" to VoiceProblem.Soniox,
        )
        for ((code, p) in soniox) assertEquals(code, p, voiceProblem(code, companion = false))
        assertNull(voiceProblem(null, false))
        assertEquals(VoiceProblem.CompanionBusy, voiceProblem("busy", companion = true))
        assertEquals(VoiceProblem.Companion, voiceProblem("lost", companion = true))
        assertEquals(VoiceProblem.TooLong, voiceProblem("too_long", companion = true))
    }

    @Test
    fun a_busy_companion_says_so() = runTest {
        val s = Stream()
        val d = dictation(s, companion = true)
        s.events.opened()
        s.say("""{"type":"hello","ok":true,"name":"Hyperscribe Companion","busy":true}""")
        assertEquals(DictationState.Ended("", VoiceProblem.CompanionBusy), d.state.value)
        assertTrue(s.texts.isEmpty())
    }

    // ─── 4. Protocol and route ─────────────────────────────────────────────

    @Test
    fun the_snapshot_says_whether_the_mac_has_voice() {
        val with = Wire.snapshot(JSONObject("""{"mac":{"name":"Studio"},"sessions":[],"stt":{"provider":"soniox","ready":true,"error":null}}"""))
        assertEquals(Stt("soniox", ready = true, error = null), with.stt)
        val older = Wire.snapshot(JSONObject("""{"mac":{"name":"Studio"},"sessions":[]}"""))
        assertNull(older.stt)
        // {t:"stt"}: a refused key.
        val msg = JSONObject("""{"t":"stt","stt":{"provider":"soniox","ready":false,"error":"Soniox didn’t accept the key."}}""")
        assertEquals(Stt("soniox", false, "Soniox didn’t accept the key."), Wire.stt(msg.getJSONObject("stt")))
        val info = Wire.sttInfo(JSONObject("""{"provider":"soniox","ready":true,"error":null,"last4":"a1b2","model":"stt-rt-v5","savedAt":1,"keysUrl":"https://console.soniox.com"}"""))
        assertEquals(SttInfo(Stt("soniox", true, null), "a1b2", "https://console.soniox.com"), info)
    }

    @Test
    fun the_route_soniox_then_the_companion_then_none() {
        val ready = Stt("soniox", true, null)
        val off = Stt(null, false, null)
        assertEquals(VoiceRoute.Soniox, voiceRoute(ready, "wss://mac.ts.net:7443/ws?token=x"))
        assertEquals(VoiceRoute.Companion, voiceRoute(off, "wss://mac.ts.net:7443/ws?token=x"))
        assertEquals(VoiceRoute.None, voiceRoute(off, null))
        // An older daemon: no mic, whatever else it knows.
        assertEquals(VoiceRoute.None, voiceRoute(null, "wss://mac.ts.net:7443/ws?token=x"))
    }

    @Test
    fun the_companion_is_asked_for_at_most_once_a_minute_and_only_without_soniox() {
        val l = CompanionLookup()
        val ready = Stt("soniox", true, null)
        val off = Stt(null, false, null)
        assertFalse(l.due(ready, 0))
        assertFalse(l.due(null, 0))
        assertTrue(l.due(off, 1_000))
        assertFalse(l.due(off, 30_000))
        assertFalse(l.due(off, 60_999))
        assertTrue(l.due(off, 61_000))
        l.answered("wss://mac.ts.net:7443/ws?token=x")
        assertEquals("wss://mac.ts.net:7443/ws?token=x", l.url)
        l.forget()
        assertNull(l.url)
        assertTrue(l.due(off, 61_001))
    }

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
    fun the_level_follows_the_voice() {
        val m = LevelMeter()
        assertEquals(0f, m.next(ByteArray(1280)), 0.001f)
        // A loud tone (−6 dBFS) rises most of the way in one frame and falls back slowly.
        val loud = ByteArray(1280)
        for (i in 0 until 640) {
            val v = (if (i % 2 == 0) 16_384 else -16_384)
            loud[2 * i] = (v and 0xff).toByte(); loud[2 * i + 1] = (v shr 8).toByte()
        }
        val up = m.next(loud)
        assertTrue("rose to $up", up > 0.6f)
        val down = m.next(ByteArray(1280))
        assertTrue("fell to $down", down in 0.5f * up..0.95f * up)
    }
}
