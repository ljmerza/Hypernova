package com.prudnikweb.hypernova.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Voice on the phone (docs/specs/spec-voice.md, 3.6; docs/PROTOCOL.md, "Speech to text"). The
 * phone's microphone streams to the Mac's daemon, which relays it to Soniox, or straight to
 * Hyperscribe Companion on the Mac; both speak the Companion's protocol v1. Nothing runs until a
 * press, nothing is written down, and words are never lost: whatever ends a dictation, what was
 * heard goes into the box.
 *
 * The Android side of the audio (AudioRecord, audio focus, a Bluetooth headset) is in
 * VoiceAudio.kt; this file has no Android audio in it, so its logic runs in JVM tests.
 */

// ─── Routes ──────────────────────────────────────────────────────────────────

/**
 * Which way speech goes (spec 2.1): Soniox through the daemon while it's ready, else the Companion
 * the Mac knows of, else nowhere (no mic). A daemon too old to say anything about speech ([stt]
 * null) gets no mic either.
 */
fun voiceRoute(stt: Stt?, companionUrl: String?): VoiceRoute = when {
    stt == null -> VoiceRoute.None
    stt.ready -> VoiceRoute.Soniox
    companionUrl != null -> VoiceRoute.Companion
    else -> VoiceRoute.None
}

/**
 * When to ask the Mac for the Companion's address (`GET /v1/speech/companion`): only while Soniox
 * isn't ready, on each snapshot and each change of the Mac's voice, and at most once a minute
 * (each ask reads the Companion's settings and Tailscale's state on the Mac). The answer is kept
 * in memory only: its url carries the Companion's token.
 */
class CompanionLookup(private val everyMs: Long = 60_000L) {
    var url: String? = null
        private set
    private var askedAt: Long? = null

    /** True when an ask is due now; it then counts as asked. */
    fun due(stt: Stt?, now: Long): Boolean {
        if (stt == null || stt.ready) return false
        val last = askedAt
        if (last != null && now - last < everyMs) return false
        askedAt = now
        return true
    }

    fun answered(url: String?) { this.url = url }

    /** A new Mac, or none: nothing known about its Companion. */
    fun forget() { url = null; askedAt = null }
}

/**
 * The daemon's (or the Companion's) error codes as the problem the phone names (spec 3.5). Codes
 * the Companion has of its own all read as "couldn't reach Hyperscribe Companion".
 */
fun voiceProblem(code: String?, companion: Boolean): VoiceProblem? = when (code) {
    null, "" -> null
    "no_key" -> VoiceProblem.NoKey
    "bad_key" -> VoiceProblem.BadKey
    "no_credit" -> VoiceProblem.NoCredit
    "busy" -> if (companion) VoiceProblem.CompanionBusy else VoiceProblem.Busy
    "unreachable" -> if (companion) VoiceProblem.Companion else VoiceProblem.Unreachable
    "soniox", "lost" -> if (companion) VoiceProblem.Companion else VoiceProblem.Soniox
    "too_long" -> VoiceProblem.TooLong
    "idle", "bad_audio", "replaced" -> VoiceProblem.Mic
    else -> if (companion) VoiceProblem.Companion else VoiceProblem.Soniox
}

/** The words as the box gets them: settled text, then what may still change, with one space between. */
fun heardText(final: String, interim: String): String =
    listOf(final.trim(), interim.trim()).filter { it.isNotEmpty() }.joinToString(" ")

/** Where the part that may still change starts in [heardText] (its length when nothing may). */
fun unsettledFrom(final: String, interim: String): Int {
    val f = final.trim()
    val i = interim.trim()
    return when {
        i.isEmpty() -> f.length
        f.isEmpty() -> 0
        else -> f.length + 1
    }
}

// ─── Level ───────────────────────────────────────────────────────────────────

/**
 * How loud each 40 ms frame is, for the rings round the capsule's planet: RMS in dBFS mapped onto
 * 0..1 (−60 dB is silence, −10 dB a raised voice), smoothed so it rises with a syllable (30 ms)
 * and falls back slowly (180 ms), as a VU meter does.
 */
class LevelMeter(frameMs: Float = 40f) {
    private val up = 1f - exp(-frameMs / 30f)
    private val down = 1f - exp(-frameMs / 180f)
    var level = 0f
        private set

    /** 16-bit little-endian mono PCM. */
    fun next(pcm: ByteArray, size: Int = pcm.size): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < size) {
            val s = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += s * s
            count++
            i += 2
        }
        val rms = if (count == 0) 0.0 else sqrt(sum / count) / 32768.0
        val db = if (rms < 1e-6) -120.0 else 20.0 * log10(rms)
        val target = ((db + 60.0) / 50.0).toFloat().coerceIn(0f, 1f)
        level += (target - level) * (if (target > level) up else down)
        return level
    }
}

// ─── The link and the microphone, as LiveDictation sees them ─────────────────

/** One open stream to the speech service. Sends never block (OkHttp queues them). */
interface SpeechLink {
    fun send(text: String): Boolean
    fun send(bytes: ByteArray): Boolean
    fun close(code: Int)
}

/** What a stream says back. Called from any thread. */
interface SpeechEvents {
    fun opened()
    fun message(text: String)
    /** The connection failed (never opened, or broke). */
    fun failed(t: Throwable?)
    fun closed(code: Int)
}

/** Opens a stream: the daemon's `/v1/stt/ws` with the device token, or the Companion's own address. */
fun interface SpeechConnector {
    fun open(events: SpeechEvents): SpeechLink
}

/** The microphone: 16 kHz 16-bit mono PCM, in 40 ms frames. */
interface AudioSource {
    /**
     * Starts capturing now. [frame] gets each 1280-byte frame on the capture thread; [failed] once
     * if the microphone can't start or stops working; [interrupted] when something else takes the
     * audio (a call): the words so far are kept.
     */
    fun start(frame: (ByteArray) -> Unit, failed: (VoiceProblem) -> Unit, interrupted: () -> Unit)
    /** Stops and lets the microphone (and any headset route, and the audio focus) go. Idempotent. */
    fun stop()
}

// ─── One dictation ───────────────────────────────────────────────────────────

/**
 * One press of the mic, from touch-down to the words in the box (spec 2.2, 3.6).
 *
 * - The microphone and the stream open together at touch-down. Until the stream can take audio,
 *   frames wait here (10 s at most, the oldest dropped), so the first syllable is never lost to the
 *   time the link takes. Soniox through the daemon takes `start` and audio at once (the daemon
 *   holds them while it reaches Soniox); the Companion gets `start` after its `hello` and audio
 *   after `started`, as Lens' client does.
 * - [finish]: the box gets everything heard at once ([DictationState.Settling]); the microphone
 *   keeps 100 ms more (the tail of the last syllable), then `stop` goes. The settled words end it;
 *   no answer within 1.5 s (3 s while nothing at all was heard) ends it with what was heard.
 * - [cancel]: the microphone stops and the stream closes without `stop`; nothing is kept.
 * - Any error, from the Mac or the link, ends it with the words heard so far and the problem.
 *
 * Thread-safe: the capture thread, OkHttp's threads, timers on [scope] and the UI all come through
 * one lock. The level is a volatile float the capsule reads while drawing.
 */
class LiveDictation(
    private val connector: SpeechConnector,
    private val audio: AudioSource,
    private val language: String,
    /** Hyperscribe Companion rather than the daemon: `start` after `hello`, its own problem words. */
    private val companion: Boolean,
    private val scope: CoroutineScope,
    override val startedAt: Long,
    private val clock: () -> Long,
    /** Debug builds only: how long letting go took to the settled words. Never words or audio. */
    private val log: (String) -> Unit = {},
) : Dictation {
    private val lock = Any()
    private val flow = MutableStateFlow<DictationState>(DictationState.Opening)
    override val state: StateFlow<DictationState> = flow

    @Volatile private var lvl = 0f
    override fun level(): Float = lvl
    private val meter = LevelMeter()

    private enum class Phase { Listening, Tail, Settling, Done }
    private var phase = Phase.Listening
    private var link: SpeechLink? = null
    /** The stream is open. */
    private var open = false
    /** `start` has gone. */
    private var startSent = false
    /** The service takes audio now (Soniox: once `start` went; the Companion: after `started`). */
    private var accepting = false
    private var stopSent = false
    private val queue = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var final = ""
    private var interim = ""
    /** Why it ends, decided before the end (the two-minute cap). */
    private var problem: VoiceProblem? = null
    private var releasedAt = 0L
    private var tailJob: Job? = null
    private var settleJob: Job? = null
    private var capJob: Job? = null

    /** Opens the microphone and the stream. Called once, right after construction. */
    fun start() {
        audio.start(::frame, ::audioFailed, ::finish)
        synchronized(lock) {
            // The microphone may have refused at once: then there's nothing to stream.
            if (phase == Phase.Done) return
            capJob = scope.launch {
                delay((VoiceTiming.MAX_MS - (clock() - startedAt)).coerceAtLeast(0L))
                finish(VoiceProblem.TooLong)
            }
        }
        val l = connector.open(events)
        synchronized(lock) {
            if (phase == Phase.Done) l.close(1000) else link = l
            // Opened before we held it (a fake link in tests can): catch up now.
            if (open && link != null) onOpen()
        }
    }

    private val events = object : SpeechEvents {
        override fun opened() {
            synchronized(lock) {
                open = true
                if (link != null) onOpen()
            }
        }
        override fun message(text: String) = onMessage(text)
        override fun failed(t: Throwable?) { synchronized(lock) { linkLost() } }
        override fun closed(code: Int) { synchronized(lock) { linkLost() } }
    }

    private fun onOpen() {
        if (phase == Phase.Done || startSent) return
        // The Companion speaks first (its hello says whether it's busy); the daemon takes it at once.
        if (!companion) sendStart()
    }

    private fun sendStart() {
        val l = link ?: return
        if (startSent) return
        startSent = true
        l.send(JSONObject().put("type", "start").put("language", language).toString())
        if (!companion) accept()
    }

    /** The service takes audio: what waited goes, and a stop that waited for it too. */
    private fun accept() {
        accepting = true
        val l = link ?: return
        while (queue.isNotEmpty()) l.send(queue.removeFirst())
        queuedBytes = 0
        if (phase == Phase.Settling && !stopSent) sendStop()
    }

    private fun sendStop() {
        stopSent = true
        link?.send("""{"type":"stop"}""")
    }

    /** A frame from the capture thread. */
    private fun frame(pcm: ByteArray) {
        lvl = meter.next(pcm)
        synchronized(lock) {
            if (phase != Phase.Listening && phase != Phase.Tail) return
            val l = link
            if (accepting && l != null) {
                l.send(pcm)
                return
            }
            queue.addLast(pcm)
            queuedBytes += pcm.size
            // Ten seconds waiting at most: the oldest goes first.
            while (queuedBytes > QUEUE_BYTES && queue.isNotEmpty()) queuedBytes -= queue.removeFirst().size
        }
    }

    private fun audioFailed(p: VoiceProblem) {
        synchronized(lock) {
            if (phase == Phase.Done) return
            // Words already heard are kept; a microphone that never started has none.
            end(heardText(final, interim), p)
        }
    }

    private fun onMessage(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        synchronized(lock) {
            if (phase == Phase.Done) return
            when (m.optString("type")) {
                "hello" -> if (companion) {
                    if (m.optBoolean("busy", false)) end(heardText(final, interim), VoiceProblem.CompanionBusy) else sendStart()
                }
                "started" -> if (!accepting) accept()
                "snapshot" -> {
                    final = m.optString("finalText", final)
                    interim = m.optString("interimText", "")
                    if (m.optBoolean("finished", false)) end(heardText(final, interim), problem)
                    else publish()
                }
                "error" -> {
                    if (m.has("finalText")) {
                        final = m.optString("finalText", final)
                        interim = m.optString("interimText", "")
                    }
                    end(heardText(final, interim), voiceProblem(m.optString("code"), companion) ?: problem)
                }
            }
        }
    }

    /** Words as they stand: captions while listening, the box's text once let go. */
    private fun publish() {
        flow.value = when (phase) {
            Phase.Listening -> DictationState.Live(final.trim(), interim.trim())
            else -> DictationState.Settling(heardText(final, interim), unsettledFrom(final, interim))
        }
    }

    /** The link went (closed or failed) before the end: the words so far, and why. */
    private fun linkLost() {
        if (phase == Phase.Done) return
        val why = when {
            // Closed after the stop without a last word: what was heard is all there is.
            stopSent -> problem
            companion -> VoiceProblem.Companion
            else -> VoiceProblem.Offline
        }
        end(heardText(final, interim), why)
    }

    override fun finish() = finish(null)

    private fun finish(why: VoiceProblem?) {
        synchronized(lock) {
            if (phase != Phase.Listening) return
            phase = Phase.Tail
            problem = why
            releasedAt = clock()
            publish()
            tailJob = scope.launch {
                delay(VoiceTiming.TAIL_MS)
                endTail()
            }
        }
    }

    /** The tail is in: the microphone goes, the rest of the audio and `stop` follow. */
    private fun endTail() {
        synchronized(lock) { endTailLocked() }
    }

    private fun endTailLocked() {
        if (phase != Phase.Tail) return
        audio.stop()
        phase = Phase.Settling
        if (accepting) {
            val l = link
            if (l != null) while (queue.isNotEmpty()) l.send(queue.removeFirst())
            queuedBytes = 0
            sendStop()
        }
        val wait = if (heardText(final, interim).isEmpty()) VoiceTiming.SETTLE_MS * 2 else VoiceTiming.SETTLE_MS
        settleJob = scope.launch {
            delay(wait)
            synchronized(lock) {
                if (phase == Phase.Done) return@launch
                // Nothing came back in time: the box keeps what it has. A link that never opened is the problem.
                end(heardText(final, interim), problem ?: if (!open) (if (companion) VoiceProblem.Companion else VoiceProblem.Offline) else null)
            }
        }
    }

    override fun cancel() {
        synchronized(lock) {
            if (phase == Phase.Done) return
            phase = Phase.Done
            stopJobs()
            audio.stop()
            lvl = 0f
            // No stop: the Mac drops what it heard.
            link?.close(1000)
            flow.value = DictationState.Cancelled
        }
    }

    private fun end(text: String, why: VoiceProblem?) {
        phase = Phase.Done
        stopJobs()
        audio.stop()
        lvl = 0f
        link?.close(1000)
        if (releasedAt > 0) log("voice release→ended ${clock() - releasedAt}ms")
        flow.value = DictationState.Ended(text, why)
    }

    private fun stopJobs() {
        tailJob?.cancel(); settleJob?.cancel(); capJob?.cancel()
    }

    companion object {
        /** Ten seconds of 16 kHz 16-bit mono. */
        const val QUEUE_BYTES = 16_000 * 2 * 10
    }
}

// ─── The demo ────────────────────────────────────────────────────────────────

/**
 * The demo's dictation: no microphone, no permission. A phrase arrives three words a second, the
 * last word or two still changing, with a level that rises and falls like speech; letting go
 * settles it 120 ms later.
 */
class FakeDictation(private val scope: CoroutineScope, override val startedAt: Long, private val clock: () -> Long) : Dictation {
    private val words = PHRASE.split(' ')
    private val flow = MutableStateFlow<DictationState>(DictationState.Opening)
    override val state: StateFlow<DictationState> = flow
    private var shown = 0
    private var job: Job? = null
    private var done = false

    init {
        job = scope.launch {
            delay(450)
            while (shown < words.size) {
                shown++
                if (!done) flow.value = DictationState.Live(settled(), changing())
                delay(333)
            }
        }
    }

    private fun settled() = words.take((shown - 2).coerceAtLeast(0)).joinToString(" ")
    private fun changing() = words.subList((shown - 2).coerceAtLeast(0), shown).joinToString(" ")

    override fun level(): Float {
        if (done || shown == 0 || shown >= words.size) return 0.04f
        val t = (clock() - startedAt) / 1000f
        // Syllables about five a second, louder words now and then.
        val syllable = (0.5f + 0.5f * sin(t * 31f)).let { it * it }
        val phrase = 0.55f + 0.45f * sin(t * 2.3f)
        return (0.12f + 0.75f * syllable * phrase).coerceIn(0f, 1f)
    }

    override fun finish() {
        if (done) return
        done = true
        job?.cancel()
        val text = heardText(settled(), changing())
        flow.value = DictationState.Settling(text, unsettledFrom(settled(), changing()))
        scope.launch {
            delay(120)
            flow.value = DictationState.Ended(text)
        }
    }

    override fun cancel() {
        if (done) return
        done = true
        job?.cancel()
        flow.value = DictationState.Cancelled
    }

    companion object {
        const val PHRASE = "Add a dark mode toggle to settings, and make it follow the system."
    }
}

// ─── Preferences ─────────────────────────────────────────────────────────────

/**
 * The phone's voice settings and the one-time hints (spec 3.7). Compose state, so Settings and the
 * composer follow each other; kept in their own preferences file.
 */
class VoicePrefs private constructor(private val prefs: SharedPreferences) {
    /** `"auto"` (the daemon hints English and Russian) or a language code. */
    var language by mutableStateOf(prefs.getString("language", "auto") ?: "auto")
        private set
    /** Use a Bluetooth headset's microphone when one is connected (Android 12+). */
    var headset by mutableStateOf(prefs.getBoolean("headset", false))
        private set
    /** Dictations that ended with words, for the hints. */
    var dictations by mutableIntStateOf(prefs.getInt("dictations", 0))
        private set
    var firstHintShown by mutableStateOf(prefs.getBoolean("firstHintShown", false))
        private set
    var moreHintShown by mutableStateOf(prefs.getBoolean("moreHintShown", false))
        private set
    /** The microphone permission has been asked for once (so a "no" since means "denied for good"). */
    var micAsked by mutableStateOf(prefs.getBoolean("micAsked", false))
        private set

    fun language(v: String) { language = v; prefs.edit().putString("language", v).apply() }
    fun headset(v: Boolean) { headset = v; prefs.edit().putBoolean("headset", v).apply() }
    fun dictated() { dictations++; prefs.edit().putInt("dictations", dictations).apply() }
    fun firstHintDone() { firstHintShown = true; prefs.edit().putBoolean("firstHintShown", true).apply() }
    fun moreHintDone() { moreHintShown = true; prefs.edit().putBoolean("moreHintShown", true).apply() }
    fun askedMic() { micAsked = true; prefs.edit().putBoolean("micAsked", true).apply() }

    companion object {
        @Volatile private var one: VoicePrefs? = null
        fun of(context: Context): VoicePrefs = one ?: synchronized(this) {
            one ?: VoicePrefs(context.applicationContext.getSharedPreferences("voice", Context.MODE_PRIVATE)).also { one = it }
        }
    }
}
