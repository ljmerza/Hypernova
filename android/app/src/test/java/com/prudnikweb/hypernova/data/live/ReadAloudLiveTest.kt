package com.prudnikweb.hypernova.data.live

import android.media.AudioTrack
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Speaker
import com.prudnikweb.hypernova.data.SpeechOut
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.ui.session.ReadAloud
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.ByteArrayOutputStream

/**
 * Read aloud on the phone (docs/PROTOCOL.md, "Read aloud"; ui/session/ReadAloud.kt): on a Linux
 * server, which has none of the Mac's voices, the phone's own text-to-speech (Robolectric's fake
 * engine, [ShadowTextToSpeech]); with the Mac's voice, a play streamed as PCM into an [AudioTrack]
 * (Robolectric's, whose play head moves with what was written: a fake player) while the Mac hears
 * how it goes; the setting, its message and the Mac's stop.
 */
@RunWith(RobolectricTestRunner::class)
class ReadAloudLiveTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }
    private val toasts = mutableListOf<String>()
    private val written = ByteArrayOutputStream()
    private val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, bytes, _ -> synchronized(written) { written.write(bytes) } }

    private val linuxSays = "The phone reads aloud with its own voice."
    private val speech = JSONObject().put("engine", "mac").put("using", "mac").put("macVoice", JSONObject.NULL).put("rate", 1)

    /**
     * ReadAloud keeps the phone's engine it made for the life of the process (an object), and the
     * sandbox outlives a test: each test starts without one, so it makes its own.
     */
    @org.junit.Before fun freshEngine() {
        ReadAloud::class.java.getDeclaredField("tts").apply { isAccessible = true }.set(null, null)
    }

    @After fun tearDown() {
        ReadAloud.stop()
        ShadowAudioTrack.removeAudioDataListener(listener)
        rig.pump()
        rig.close()
    }

    private fun connect(linux: Boolean) {
        Core.init(rig.context as android.app.Application)
        daemon.snapshot = snapshot(sessions = listOf(session("s1"))) {
            put("speech", speech)
            if (linux) getJSONObject("mac").put("unavailable", JSONObject().put("macVoice", linuxSays))
        }
        rig.connect()
        // The real daemon's socket opens with the snapshot again (docs/PROTOCOL.md): the read-aloud setting comes with it.
        rig.push(JSONObject().put("t", "snapshot").put("snapshot", daemon.snapshot)) { rig.hub.speech.settings != null }
    }

    private fun tts() = org.robolectric.Shadows.shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    /** The phone's engine made and ready: Robolectric's fake one says so only when told. */
    private fun engineReady() {
        rig.until("the engine") { ShadowTextToSpeech.getLastTextToSpeechInstance() != null }
        tts().onInitListener.onInit(android.speech.tts.TextToSpeech.SUCCESS)
    }

    @Test fun onALinuxServerThePhoneReadsWithItsOwnVoiceAndNeverAsksTheServer() {
        connect(linux = true)
        ReadAloud.toggle(rig.hub, "a1", "## Done\n\nThe **tests** pass.\n\n```\nnpm test\n```", rig.scope, { toasts += it }, "s1")
        assertEquals("a1", ReadAloud.reading)
        engineReady()
        // Its words, not its markdown or its code.
        assertEquals(listOf("Done.\nThe tests pass."), tts().spokenTextList)
        assertEquals(0, daemon.count("POST", "/v1/speech/play"))
        assertEquals(0, daemon.count("POST", "/v1/speech"))
        // The engine's done with the last piece: nothing is being read.
        rig.until("done") { ReadAloud.reading == null }
        assertTrue(toasts.isEmpty())
    }

    @Test fun readAloudOnTheReplyBeingReadIsItsStop() {
        connect(linux = true)
        ReadAloud.toggle(rig.hub, "a1", "The tests pass.", rig.scope, { toasts += it })
        engineReady()
        assertEquals("a1", ReadAloud.reading)
        ReadAloud.toggle(rig.hub, "a1", "The tests pass.", rig.scope, { toasts += it })
        assertNull(ReadAloud.reading)
        assertTrue(tts().isStopped)
        // Another reply then: read with the engine already made, at once.
        ReadAloud.toggle(rig.hub, "a2", "And this one.", rig.scope, { toasts += it })
        rig.until("spoken") { tts().spokenTextList.contains("And this one.") }
    }

    @Test fun anEngineThatWontStartIsSaidInAToast() {
        connect(linux = true)
        ReadAloud.toggle(rig.hub, "a1", "The tests pass.", rig.scope, { toasts += it })
        rig.until("the engine") { ShadowTextToSpeech.getLastTextToSpeechInstance() != null }
        tts().onInitListener.onInit(android.speech.tts.TextToSpeech.ERROR)
        rig.until("the toast") { toasts.isNotEmpty() }
        assertEquals(listOf(rig.context.getString(com.prudnikweb.hypernova.R.string.chat_read_failed)), toasts)
        assertNull(ReadAloud.reading)
    }

    @Test fun aLongReplyIsReadInPiecesTheEngineTakes() {
        connect(linux = true)
        val sentence = "This sentence is one of many in a very long reply. "
        val long = sentence.repeat(200)
        ReadAloud.toggle(rig.hub, "a2", long, rig.scope, { toasts += it })
        engineReady()
        val parts = tts().spokenTextList
        assertTrue("in pieces (${parts.size})", parts.size > 1)
        assertTrue(parts.all { it.length <= android.speech.tts.TextToSpeech.getMaxSpeechInputLength() })
        // At most the 8000 characters the older way reads, cut after a sentence, in order.
        val read = parts.joinToString(" ")
        assertTrue(read.length in 7_000..8_000)
        assertTrue(read.endsWith("reply."))
        assertEquals(long.replace(" ", "").substring(0, read.replace(" ", "").length), read.replace(" ", ""))
        // Queued one after another: the first flushes whatever was reading.
        assertEquals(android.speech.tts.TextToSpeech.QUEUE_ADD, tts().queueMode)
    }

    /** About half a second of a 24 kHz voice. */
    private val pcm = ByteArray(24_000) { (if (it % 2 == 0) (it / 2 % 64) else 0).toByte() }

    private fun playAnswer(id: String = "play-0123456789") = Fixtures.json("rest-speech-play").also { it.getJSONObject("play").put("id", id).put("sessionId", "s1") }

    private fun progress(): List<JSONObject> = daemon.received.map(::JSONObject).filter { it.optString("t") == "speech.progress" }

    @Test fun withTheMacsVoiceAPlayStreamsIntoTheTrackAndTheMacHearsItStartAndEnd() {
        connect(linux = false)
        ShadowAudioTrack.addAudioDataListener(listener)
        daemon.ok("POST", "/v1/speech/play", playAnswer())
        daemon.on("GET", "/v1/speech/plays/play-0123456789/audio") { MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/pcm").setHeader("x-sample-rate", "24000").setBody(okio.Buffer().write(pcm)) }
        ReadAloud.toggle(rig.hub, "a1", "The tests pass.", rig.scope, { toasts += it }, "s1")
        rig.until("the play") { daemon.count("POST", "/v1/speech/play") == 1 }
        val b = daemon.calls("POST", "/v1/speech/play").single().json
        assertEquals("The tests pass.", b.getString("text"))
        assertEquals("me", b.getString("to"))
        assertEquals("s1", b.getString("sessionId"))
        rig.until("speaking") { Speaker.now != null }
        assertEquals("a1", ReadAloud.reading)
        assertEquals("a1", Speaker.now!!.key)
        assertEquals("play-0123456789", Speaker.now!!.play.id)

        rig.until("ended") { progress().any { it.getString("state") == "ended" } && Speaker.now == null }
        val states = progress().map { it.getString("state") }
        assertEquals("started", states.first())
        assertEquals("ended", states.last())
        assertTrue(progress().all { it.getString("id") == "play-0123456789" })
        // Everything the Mac sent reached the speaker, then a buffer of silence to push it out.
        val got = synchronized(written) { written.toByteArray() }
        assertArrayEquals(pcm, got.copyOf(pcm.size))
        assertTrue(got.copyOfRange(pcm.size, got.size).all { it == 0.toByte() })
        assertEquals("Bearer tok-z2", daemon.calls("GET", "/v1/speech/plays/play-0123456789/audio").single().auth)
        assertNull(ReadAloud.reading)
    }

    @Test fun theMacsStopEndsThePlayAndTheStopButtonTellsTheMacItWasThePhone() {
        connect(linux = false)
        val gate = java.util.concurrent.CountDownLatch(1)
        daemon.ok("POST", "/v1/speech/play", playAnswer())
        // The audio comes slowly: it is still playing when the Mac says stop.
        daemon.on("GET", "/v1/speech/plays/play-0123456789/audio") { gate.await(5, java.util.concurrent.TimeUnit.SECONDS); MockResponse().setResponseCode(200).setBody(okio.Buffer().write(pcm)) }
        ReadAloud.toggle(rig.hub, "a1", "The tests pass.", rig.scope, { toasts += it })
        rig.until("speaking") { Speaker.now != null }
        rig.push(Fixtures.json("ws-speech-stop")) { Speaker.now == null }
        gate.countDown()
        rig.sync()
        // Stopped by the Mac: the Mac knows, nothing is said back.
        assertTrue(progress().none { it.getString("state") == "stopped" })

        // The phone's own Stop: said as the phone's.
        daemon.ok("POST", "/v1/speech/play", playAnswer("play-2"))
        val gate2 = java.util.concurrent.CountDownLatch(1)
        daemon.on("GET", "/v1/speech/plays/play-2/audio") { gate2.await(5, java.util.concurrent.TimeUnit.SECONDS); MockResponse().setResponseCode(200).setBody(okio.Buffer().write(pcm)) }
        ReadAloud.toggle(rig.hub, "a3", "Another.", rig.scope, { toasts += it })
        rig.until("speaking") { Speaker.now?.play?.id == "play-2" }
        ReadAloud.toggle(rig.hub, "a3", "Another.", rig.scope, { toasts += it })
        gate2.countDown()
        rig.until("stopped") { progress().any { it.getString("id") == "play-2" && it.getString("state") == "stopped" } && Speaker.now == null }
        assertEquals("phone", progress().last { it.getString("id") == "play-2" }.getString("why"))
    }

    @Test fun aMacThatCantStartAPlayLeavesItToThePhonesOwnVoice() {
        connect(linux = false)
        daemon.on("POST", "/v1/speech/play") { refusal(502, "speech_failed", "The voice didn't start.") }
        // Its older whole clip fails too.
        daemon.on("POST", "/v1/speech") { refusal(502, "speech_failed", "No voice.") }
        ReadAloud.toggle(rig.hub, "a1", "The tests pass.", rig.scope, { toasts += it })
        engineReady()
        assertEquals(listOf("The tests pass."), tts().spokenTextList)
        assertEquals(1, daemon.count("POST", "/v1/speech/play"))
    }

    @Test fun theSettingFromTheSnapshotItsMessageASaveAndTheVoices() {
        connect(linux = true)
        val s = rig.hub.speech
        assertEquals(SpeechOut(null, 1f), s.settings)
        rig.push(Fixtures.json("ws-speech")) { s.settings?.rate == 1.15f }
        daemon.ok("POST", "/v1/speech/settings", JSONObject().put("speech", JSONObject(speech.toString()).put("rate", 1.3)))
        assertEquals(1.3f, rig.call { s.save(rate = 1.3f) }.getOrThrow().rate)
        assertEquals(1.3, daemon.calls("POST", "/v1/speech/settings").single().json.getDouble("rate"), 0.001)
        assertEquals(1.3f, s.settings!!.rate)
        daemon.ok("GET", "/v1/speech/voices", Fixtures.json("rest-speech-voices"))
        assertTrue(rig.call { s.voices() }.getOrThrow().isEmpty())
        daemon.on("POST", "/v1/speech/settings") { refusal(400, "bad_rate", "Pick a speed between 0.5 and 2.") }
        assertEquals("Pick a speed between 0.5 and 2.", rig.call { s.save(rate = 9f) }.exceptionOrNull()!!.message)
        assertEquals(1.3f, s.settings!!.rate)
    }
}
