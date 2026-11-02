package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import kotlin.math.exp

/*
 * Read aloud on the phone (docs/PROTOCOL.md, "Read aloud"): the Mac's voice and speed, the plays the
 * phone app streams and plays natively (its own Read aloud), and the wave that shows it speaking.
 * This file has no Android audio in it, so its logic runs in JVM tests; SpeechPlayer.kt plays,
 * SpeechLink.kt talks to the Mac.
 */

/** The Mac's read-aloud setting (snapshot `speech`, then `{t:"speech"}`). Null from an older Mac. */
@Immutable
data class SpeechOut(
    /** The Mac's voice (null: its own default). */
    val macVoice: String?,
    val rate: Float,
) {
    companion object {
        fun parse(o: JSONObject?): SpeechOut? {
            if (o == null || o.str("engine") == null) return null
            return SpeechOut(
                macVoice = o.str("macVoice"),
                rate = (o.dbl("rate") ?: 1.0).toFloat().coerceIn(0.5f, 2f),
            )
        }
    }
}

/** A voice to read with: one of the Mac's. */
@Immutable
data class SpeechVoice(val id: String, val name: String, val lang: String, val gender: String?, val description: String?) {
    companion object {
        fun list(o: JSONObject): List<SpeechVoice> = o.arr("voices").mapObjects { v ->
            val id = v.str("id") ?: return@mapObjects null
            SpeechVoice(id, v.str("name") ?: id, v.str("lang").orEmpty(), v.str("gender"), v.str("description"))
        }.filterNotNull()
    }
}

/** One play: speech the Mac makes and this phone plays (the answer to `POST /v1/speech/play`). */
@Immutable
data class PlayInfo(
    val id: String,
    val sessionId: String?,
    /** "phone": this phone's own Read aloud. */
    val source: String,
    val engine: String,
    val sampleRate: Int,
    /** How long the text is, in characters. */
    val of: Int,
) {
    companion object {
        fun parse(o: JSONObject?): PlayInfo? {
            if (o == null) return null
            val id = o.str("id")?.takeIf { it.matches(Regex("[\\w-]{1,40}")) } ?: return null
            // The phone plays 16-bit mono PCM only; anything else isn't for this app.
            if ((o.str("format") ?: "pcm_s16le") != "pcm_s16le") return null
            val rate = o.int("sampleRate") ?: 24_000
            if (rate !in 8_000..48_000) return null
            return PlayInfo(id, o.str("sessionId"), o.str("source") ?: "phone", o.str("engine") ?: "mac", rate, o.int("of") ?: 0)
        }
    }
}

/** What plays now, for the screens: the wave, the bar, the message being read. */
@Immutable
data class Speaking(
    val play: PlayInfo,
    /** The message being read (its transcript key), for this phone's own Read aloud. */
    val key: String?,
    /** Playing (false while the first audio is on its way). */
    val started: Boolean,
)

/** How a play went, as the phone tells the Mac (`{t:"speech.progress"}`). */
enum class PlayState(val wire: String) { Started("started"), Playing("playing"), Ended("ended"), Stopped("stopped"), Failed("failed") }

/** The progress message for the Mac; level in hundredths, the position in whole milliseconds. */
fun progressMessage(id: String, state: PlayState, ms: Long, level: Float, why: String? = null): String =
    JSONObject()
        .put("t", "speech.progress")
        .put("id", id)
        .put("state", state.wire)
        .put("ms", ms.coerceAtLeast(0))
        .put("level", (level.coerceIn(0f, 1f) * 100).toInt() / 100.0)
        .apply { if (why != null) put("why", why) }
        .toString()

/**
 * The speeds Settings offers, within what the Mac's voice takes (0.5–2).
 */
val SPEECH_RATES = listOf(0.8f, 1f, 1.15f, 1.3f)

/** The step nearest a saved rate, for the picker. */
fun nearestRate(r: Float): Float = SPEECH_RATES.minBy { kotlin.math.abs(it - r) }

/**
 * How loud the voice is as it plays: RMS of each 20 ms of PCM (dBFS −60…−10 onto 0..1), indexed by
 * its frame, so the wave reads the loudness of what the speaker plays now, not what was just made.
 */
class LoudnessTrack(private val rate: Int, private val blockMs: Int = 20) {
    private val framesPerBlock = rate * blockMs / 1000
    private var levels = FloatArray(512)
    private var count = 0
    private var partialSum = 0.0
    private var partialN = 0

    /** Adds 16-bit little-endian mono PCM. */
    @Synchronized
    fun add(pcm: ByteArray, size: Int = pcm.size) {
        var i = 0
        while (i + 1 < size) {
            val s = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768.0
            partialSum += s * s
            partialN++
            if (partialN == framesPerBlock) {
                push(toLevel(partialSum / partialN))
                partialSum = 0.0
                partialN = 0
            }
            i += 2
        }
    }

    private fun push(v: Float) {
        if (count == levels.size) levels = levels.copyOf(levels.size * 2)
        levels[count++] = v
    }

    /** The loudness at [frame] (where the speaker is now); 0 past what was added. */
    @Synchronized
    fun at(frame: Long): Float {
        val b = (frame / framesPerBlock).toInt()
        return if (b in 0 until count) levels[b] else 0f
    }

    companion object {
        fun toLevel(meanSquare: Double): Float {
            val db = 10.0 * kotlin.math.log10(meanSquare + 1e-12)
            return ((db + 60.0) / 50.0).toFloat().coerceIn(0f, 1f)
        }
    }
}

/**
 * The speaking wave: five bars, the middle one the voice's loudness now and each pair outward the
 * same a beat earlier, so each syllable rises in the middle and spreads out like a pulsar's beat.
 * Bars rise quickly (40 ms) and fall slowly (220 ms), as a meter does, and settle to dots in
 * silence. [step] advances it by [dtMs] with the level heard now; [heights] are 0..1 per bar.
 */
class PulsarWave(val bars: Int = 5, private val echoMs: Float = 90f) {
    val heights = FloatArray(bars)
    private val history = FloatArray(16)
    private var historyAt = 0f
    private var smoothed = 0f

    fun step(level: Float, dtMs: Float) {
        val dt = dtMs.coerceIn(0f, 200f)
        val target = level.coerceIn(0f, 1f)
        val k = 1f - exp(-dt / if (target > smoothed) 40f else 220f)
        smoothed += (target - smoothed) * k
        // One slot of the history a beat: what the middle showed [echoMs] ago moves outward.
        historyAt += dt
        while (historyAt >= echoMs) {
            historyAt -= echoMs
            System.arraycopy(history, 0, history, 1, history.size - 1)
        }
        history[0] = smoothed
        val mid = bars / 2
        for (i in 0 until bars) {
            val d = kotlin.math.abs(i - mid)
            // Outer bars are a little shorter, so even a steady tone reads as a wave, not a block.
            heights[i] = (history[d.coerceAtMost(history.size - 1)] * (1f - 0.16f * d)).coerceIn(0f, 1f)
        }
    }

    /** Back to dots at once (a new play starts flat). */
    fun reset() {
        history.fill(0f)
        heights.fill(0f)
        smoothed = 0f
        historyAt = 0f
    }

    /** Silent and settled: nothing left to draw but dots. */
    val still: Boolean get() = heights.all { it < 0.01f } && smoothed < 0.01f

    companion object {
        /** Reduce Motion: a still wave that reads as one, with "Speaking" beside it. */
        val STATIC = floatArrayOf(0.35f, 0.7f, 1f, 0.7f, 0.35f)
    }
}
