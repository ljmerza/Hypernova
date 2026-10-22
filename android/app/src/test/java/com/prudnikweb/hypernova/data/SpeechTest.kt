package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read aloud's logic on the JVM (docs/PROTOCOL.md, "Read aloud"): the Mac's setting and plays as the
 * phone reads them, what it tells the Mac, the loudness the wave follows, and the wave itself.
 */
class SpeechTest {

    @Test
    fun `the Mac's setting, and nothing from an older Mac`() {
        val s = SpeechOut.parse(
            JSONObject("""{"engine":"soniox","using":"mac","voice":"Maya","macVoice":null,"rate":9,"model":"tts-rt-v2","soniox":false,"note":"No Soniox key on this Mac, so the Mac’s voice reads.","phone":true}"""),
        )!!
        assertEquals("soniox", s.engine)
        assertEquals("mac", s.using)
        assertEquals("Maya", s.voice)
        assertNull(s.macVoice)
        assertEquals(2f, s.rate)
        assertTrue(s.phone)
        assertTrue(s.note!!.startsWith("No Soniox key"))
        assertNull(SpeechOut.parse(null))
        assertNull(SpeechOut.parse(JSONObject("{}")))
    }

    @Test
    fun `a play is taken only as PCM this phone can play`() {
        val p = PlayInfo.parse(JSONObject("""{"id":"ab_C-9","sessionId":"s1","source":"glasses","engine":"soniox","sampleRate":24000,"format":"pcm_s16le","of":120}"""))!!
        assertTrue(p.forGlasses)
        assertEquals(24_000, p.sampleRate)
        assertEquals(120, p.of)
        assertNull(PlayInfo.parse(JSONObject("""{"id":"x","format":"mp3"}""")))
        assertNull(PlayInfo.parse(JSONObject("""{"id":"x","sampleRate":96000}""")))
        assertNull(PlayInfo.parse(JSONObject("""{"id":"../etc"}""")))
        assertFalse(PlayInfo.parse(JSONObject("""{"id":"me","source":"phone"}"""))!!.forGlasses)
    }

    @Test
    fun `what the phone tells the Mac`() {
        val m = JSONObject(progressMessage("p1", PlayState.Playing, 1234, 0.876f))
        assertEquals("speech.progress", m.getString("t"))
        assertEquals("p1", m.getString("id"))
        assertEquals("playing", m.getString("state"))
        assertEquals(1234, m.getLong("ms"))
        assertEquals(0.87, m.getDouble("level"), 1e-9)
        assertFalse(m.has("why"))
        val stopped = JSONObject(progressMessage("p1", PlayState.Stopped, -5, 3f, "unplugged"))
        assertEquals(0, stopped.getLong("ms"))
        assertEquals(1.0, stopped.getDouble("level"), 1e-9)
        assertEquals("unplugged", stopped.getString("why"))
        val offer = JSONObject(playerMessage(false))
        assertEquals("speech.player", offer.getString("t"))
        assertFalse(offer.getBoolean("on"))
    }

    @Test
    fun `speeds snap to the steps Settings offers`() {
        assertEquals(1f, nearestRate(1.04f))
        assertEquals(1.3f, nearestRate(2f))
        assertEquals(0.8f, nearestRate(0.5f))
        assertEquals(1.15f, nearestRate(1.2f))
    }

    @Test
    fun `loudness by where the speaker is, not what was made last`() {
        val rate = 24_000
        val t = LoudnessTrack(rate)
        // 100 ms of silence, then 100 ms of a loud tone.
        val silence = ByteArray(rate / 10 * 2)
        val loud = ByteArray(rate / 10 * 2)
        for (i in 0 until loud.size / 2) {
            val v = if (i % 24 < 12) 16000 else -16000
            loud[i * 2] = (v and 0xff).toByte()
            loud[i * 2 + 1] = (v shr 8).toByte()
        }
        t.add(silence)
        t.add(loud)
        assertEquals(0f, t.at(0))
        assertTrue(t.at((rate * 0.15).toLong()) > 0.8f)
        // Past what came: nothing.
        assertEquals(0f, t.at(rate.toLong()))
    }

    @Test
    fun `the wave rises in the middle, spreads outward, and settles to dots`() {
        val w = PulsarWave()
        assertTrue(w.still)
        w.step(1f, 60f)
        // A syllable: the middle first.
        assertTrue(w.heights[2] > 0.5f)
        assertTrue(w.heights[0] < 0.05f)
        repeat(4) { w.step(1f, 60f) }
        // A beat later it has spread to the edges, a little lower there.
        assertTrue(w.heights[0] > 0.3f)
        assertTrue(w.heights[0] < w.heights[2])
        assertEquals(w.heights[1], w.heights[3], 1e-6f)
        // Silence: down slowly, then flat.
        w.step(0f, 60f)
        assertTrue(w.heights[2] > 0.3f)
        repeat(60) { w.step(0f, 60f) }
        assertTrue(w.still)
        w.step(1f, 60f)
        w.reset()
        assertTrue(w.still)
        assertEquals(5, PulsarWave.STATIC.size)
    }
}
