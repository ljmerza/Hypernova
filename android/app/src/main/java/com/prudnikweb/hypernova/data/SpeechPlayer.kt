package com.prudnikweb.hypernova.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.BuildConfig
import okhttp3.Call
import kotlin.math.max

/**
 * Plays read-aloud speech on this phone, natively: the Mac's 24 kHz PCM goes into an AudioTrack as
 * it streams in, so the voice starts while the rest is still being made. It plays through whatever
 * the phone plays to (Bluetooth headphones too), with the screen off or the app in the background
 * (the connection's foreground service keeps the process, a wake lock the CPU, for the play only).
 *
 * Audio focus "may duck", so music dips under the voice and comes back after. A call taking the
 * audio, or headphones unplugged, stops it, as any player does. The Mac hears how it goes: started,
 * then the position and loudness a few times a second, then ended (played to the end) or stopped.
 *
 * One play at a time; a new one stops the last. [now] and [level] are what the screens draw.
 */
object Speaker : SpeechOutput {
    override val speaking: Boolean get() = now != null

    /** What plays now (Compose state, written on the main thread). */
    var now by mutableStateOf<Speaking?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var current: Run? = null

    /** The voice's loudness at what the speaker plays now, 0..1. Cheap: read it while drawing. */
    fun level(): Float = current?.levelNow() ?: 0f

    /**
     * Plays [info]: [open] makes the request for its audio, [report] tells the Mac how it goes
     * (`{t:"speech.progress"}`), [label] names it in the notification. [key]: the message being
     * read, for this phone's own Read aloud.
     */
    override fun play(info: PlayInfo, key: String?, label: String?, open: () -> Call, report: (String) -> Unit) = onMain {
        current?.stop("replaced")
        val run = Run(info, key, label, open, report)
        current = run
        now = Speaking(info, key, started = false)
        run.start()
    }

    /** Stop button, notification: stop whatever plays. */
    override fun stop(why: String) = onMain { current?.stop(why) }

    /** The Mac stopped play [id] (a newer one replaced it). */
    override fun stopPlay(id: String) = onMain { if (current?.info?.id == id) current?.stop("mac") }

    /** At once on the main thread (so a screen never sees a frame without it), else posted there. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun started(run: Run) = main.post { if (current === run) now = now?.copy(started = true) }

    private fun finished(run: Run) = main.post {
        if (current === run) {
            current = null
            now = null
        }
    }

    private const val ACTION_STOP = "com.prudnikweb.hypernova.STOP_SPEAKING"

    /** One play, on a thread of its own: blocking reads from the Mac, blocking writes to the track. */
    private class Run(val info: PlayInfo, val key: String?, val label: String?, val open: () -> Call, val report: (String) -> Unit) {
        @Volatile private var why: String? = null
        @Volatile private var track: AudioTrack? = null
        @Volatile private var call: Call? = null
        private val loud = LoudnessTrack(info.sampleRate)
        private var reportedAt = 0L
        /** The media notification: made and closed on the main thread (a media session needs its looper). */
        private var notice: SpeakingNotice? = null

        fun start() {
            Thread(::run, "hypernova-speaking").apply { isDaemon = true }.start()
        }

        fun stop(reason: String) {
            if (why != null) return
            why = reason
            call?.cancel()
            runCatching { track?.pause(); track?.flush() }
        }

        fun levelNow(): Float {
            val t = track ?: return 0f
            if (why != null) return 0f
            return runCatching { loud.at(t.playbackHeadPosition.toLong() and 0xffffffffL) }.getOrDefault(0f)
        }

        private fun positionMs(): Long {
            val t = track ?: return 0L
            return (t.playbackHeadPosition.toLong() and 0xffffffffL) * 1000L / info.sampleRate
        }

        private fun send(state: PlayState, why: String? = null) {
            runCatching { report(progressMessage(info.id, state, positionMs(), if (state == PlayState.Playing || state == PlayState.Started) levelNow() else 0f, why)) }
        }

        /** The position and loudness, at most every 150 ms. */
        private fun tick() {
            val t = SystemClock.elapsedRealtime()
            if (t - reportedAt < 150) return
            reportedAt = t
            send(PlayState.Playing)
        }

        private fun run() {
            val ctx = AndroidCore.app
            val am = ctx.getSystemService(AudioManager::class.java)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        // A call, or another app playing for real: the voice stops, as any player does.
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stop("focus")
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runCatching { track?.setVolume(0.35f) }
                        AudioManager.AUDIOFOCUS_GAIN -> runCatching { track?.setVolume(1f) }
                    }
                }
                .build()
            val noisy = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    // Headphones unplugged (or a headset gone): don't carry on out of the speaker.
                    if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop("unplugged")
                    else if (i.action == ACTION_STOP) stop("phone")
                }
            }
            val wake = ctx.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hypernova:speaking")
            var started = false
            var ended = false
            var failure: String? = null
            try {
                if (am?.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    failure = "focus"
                    return
                }
                runCatching { wake?.acquire(20 * 60_000L) }
                val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY).apply { addAction(ACTION_STOP) }
                ContextCompat.registerReceiver(ctx, noisy, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                main.post { notice = SpeakingNotice.show(ctx, info, label, ACTION_STOP) { stop("phone") } }
                val c = open()
                call = c
                if (why != null) return
                val res = c.execute()
                res.use { r ->
                    if (!r.isSuccessful) {
                        failure = "http_${r.code}"
                        return
                    }
                    val rate = r.header("x-sample-rate")?.toIntOrNull()?.takeIf { it in 8_000..48_000 } ?: info.sampleRate
                    val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val t = AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(max(min * 2, rate / 2))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    track = t
                    val input = r.body?.byteStream() ?: run { failure = "empty"; return }
                    val buf = ByteArray(rate / 10 * 2)
                    var odd: Byte? = null
                    var written = 0L
                    // A fifth of a second in hand before the voice starts, so a slow link doesn't
                    // stutter; never more than half the track's buffer, as a write into a full
                    // buffer of a track that isn't playing yet would wait for ever.
                    val prebuffer = minOf(rate * 2L / 5, t.bufferSizeInFrames * 2L / 2)
                    val go = {
                        t.play()
                        started = true
                        Speaker.started(this)
                        send(PlayState.Started)
                        reportedAt = SystemClock.elapsedRealtime()
                    }
                    while (why == null) {
                        var n = input.read(buf, if (odd != null) 1 else 0, buf.size - (if (odd != null) 1 else 0))
                        if (n < 0) break
                        if (odd != null) {
                            buf[0] = odd!!
                            n += 1
                            odd = null
                        }
                        // Whole samples only.
                        if (n % 2 == 1) {
                            odd = buf[n - 1]
                            n -= 1
                        }
                        if (n == 0) continue
                        loud.add(buf, n)
                        if (!started && written + n > prebuffer) go()
                        var off = 0
                        while (off < n && why == null) {
                            val w = t.write(buf, off, n - off)
                            if (w < 0) {
                                failure = "track"
                                return
                            }
                            off += w
                        }
                        written += n
                        if (started) tick()
                    }
                    if (why != null) return
                    // Short replies: everything came before the prebuffer filled.
                    if (!started && written > 0) go()
                    // What's written plays out. A track in stream mode only (re)starts once its buffer is
                    // full, so a short tail, or one after a stall, would sit unplayed: a buffer of
                    // silence after it pushes it out (the head is counted against the speech alone).
                    val frames = written / 2
                    val pad = ByteArray(t.bufferSizeInFrames * 2)
                    var pw = 0
                    while (pw < pad.size && why == null) {
                        val w = t.write(pad, pw, pad.size - pw)
                        if (w <= 0) break
                        pw += w
                    }
                    // Ended only once the head has passed the last of the speech; a head that stops
                    // moving for 3 s (the speaker never took it) is a stall, never "heard".
                    var head = t.playbackHeadPosition.toLong() and 0xffffffffL
                    var movedAt = SystemClock.elapsedRealtime()
                    while (why == null && head < frames) {
                        Thread.sleep(40)
                        tick()
                        val h = t.playbackHeadPosition.toLong() and 0xffffffffL
                        if (h != head) movedAt = SystemClock.elapsedRealtime()
                        head = h
                        if (SystemClock.elapsedRealtime() - movedAt > 3_000) break
                    }
                    if (why == null && started && head >= frames) ended = true
                    else if (why == null && started) failure = "stalled"
                    else if (why == null) failure = "empty"
                }
            } catch (e: Throwable) {
                if (why == null) failure = if (started) "network" else "unreachable"
                if (BuildConfig.DEBUG) android.util.Log.d("Hypernova", "speaking: ${e.javaClass.simpleName}")
            } finally {
                when {
                    ended -> send(PlayState.Ended)
                    why != null -> if (why != "mac" && why != "replaced") send(PlayState.Stopped, if (why == "phone") "phone" else why)
                    // Never started, or broke off mid-way: not heard whole.
                    !started -> send(PlayState.Failed, failure ?: "failed")
                    else -> send(PlayState.Stopped, failure ?: "network")
                }
                if (BuildConfig.DEBUG) android.util.Log.d("Hypernova", "speaking ${info.source} ${info.engine} ${if (ended) "ended" else why ?: failure} ${positionMs()}ms")
                runCatching { track?.stop() }
                runCatching { track?.release() }
                track = null
                runCatching { am?.abandonAudioFocusRequest(focus) }
                runCatching { ctx.unregisterReceiver(noisy) }
                runCatching { if (wake?.isHeld == true) wake.release() }
                main.post {
                    notice?.close()
                    notice = null
                }
                Speaker.finished(this)
            }
        }
    }
}
