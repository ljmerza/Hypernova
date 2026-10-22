package com.prudnikweb.hypernova.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/*
 * The Android side of a dictation (spec 3.6): the phone's microphone through AudioRecord, the audio
 * focus that pauses music while you talk, a Bluetooth headset's microphone when the owner asked for
 * it, and the WebSocket. Everything here exists only between a press and the end of its dictation:
 * no recorder, focus or headset route is held, prepared or warmed up at rest.
 */

/**
 * The microphone: 16 kHz 16-bit mono, read in 40 ms frames on a thread of urgent audio priority.
 * VOICE_RECOGNITION is the source tuned for speech to text (no call-style processing that thins the
 * voice). A wired or USB headset's microphone is picked by Android itself; a Bluetooth one only
 * with [headset] (Android 12+), because bringing up its route takes a moment and lowers music
 * quality: capture starts on the phone's microphone at once and moves over when the headset is
 * ready, in the same stream.
 */
class AudioCapture(context: Context, private val headset: Boolean) : AudioSource {
    private val app = context.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)
    @Volatile private var running = false
    @Volatile private var swapTo: AudioDeviceInfo? = null
    private var focus: AudioFocusRequest? = null
    private var route: HeadsetRoute? = null
    private var stopped = false

    @SuppressLint("MissingPermission") // The caller asks for RECORD_AUDIO first (ui/voice/MicButton.kt).
    override fun start(frame: (ByteArray) -> Unit, failed: (VoiceProblem) -> Unit, interrupted: () -> Unit) {
        // Music pauses while you talk, and a call coming in ends the dictation (its words kept).
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) interrupted()
            }
            .build()
        focus = req
        runCatching { am?.requestAudioFocus(req) }
        val rec = record(MediaRecorder.AudioSource.VOICE_RECOGNITION, null)
        if (rec == null) {
            stop()
            failed(VoiceProblem.MicBusy)
            return
        }
        try {
            rec.startRecording()
        } catch (_: IllegalStateException) {}
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            stop()
            failed(VoiceProblem.MicBusy)
            return
        }
        running = true
        Thread({ loop(rec, frame, failed) }, "hypernova-mic").start()
        if (headset && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
            route = HeadsetRoute(app, am) { device -> swapTo = device }.also { it.start() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(source: Int, device: AudioDeviceInfo?): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4 * FRAME))
        } catch (_: Exception) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        if (device != null) rec.setPreferredDevice(device)
        return rec
    }

    /** The capture thread: frames until [stop]; the recorder is released here, where it's read. */
    private fun loop(first: AudioRecord, frame: (ByteArray) -> Unit, failed: (VoiceProblem) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var rec = first
        val buf = ByteArray(FRAME)
        try {
            while (running) {
                // The headset is ready: carry on from its microphone, in the same stream.
                swapTo?.let { dev ->
                    swapTo = null
                    val next = record(MediaRecorder.AudioSource.VOICE_COMMUNICATION, dev)
                    if (next != null) {
                        runCatching { next.startRecording() }
                        if (next.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            runCatching { rec.stop() }
                            rec.release()
                            rec = next
                        } else next.release()
                    }
                }
                var got = 0
                while (got < FRAME && running) {
                    val n = rec.read(buf, got, FRAME - got)
                    if (n < 0) {
                        // Taken by another app, or the permission revoked meanwhile.
                        if (running) failed(if (n == AudioRecord.ERROR_INVALID_OPERATION) VoiceProblem.MicBusy else VoiceProblem.Mic)
                        return
                    }
                    if (n == 0) Thread.sleep(5)
                    got += n
                }
                if (got == FRAME) frame(buf.copyOf())
            }
        } catch (_: Exception) {
            if (running) failed(VoiceProblem.Mic)
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        running = false
        route?.stop()
        route = null
        focus?.let { f -> runCatching { am?.abandonAudioFocusRequest(f) } }
        focus = null
    }

    companion object {
        const val RATE = 16_000
        /** 640 samples, 40 ms: what Lens sends too. */
        const val FRAME = 1280
    }
}

/**
 * A Bluetooth headset's microphone (Android 12+): the first LE Audio headset, else a classic one
 * (SCO), as the communication device, in communication mode. [ready] is called once Android
 * confirms the route; if it hasn't within 1.5 s the dictation stays on the phone's microphone. At
 * the end the route is cleared and the mode put back.
 */
@RequiresApi(Build.VERSION_CODES.S)
class HeadsetRoute(context: Context, private val am: AudioManager, private val ready: (AudioDeviceInfo) -> Unit) {
    private val executor = context.mainExecutor
    private val main = Handler(Looper.getMainLooper())
    private var mode = AudioManager.MODE_NORMAL
    private var listener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var started = false
    private var confirmed = false
    private val giveUp = Runnable { if (!confirmed) stop() }

    fun start() {
        val devices = am.availableCommunicationDevices
        val dev = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return
        started = true
        mode = am.mode
        val l = AudioManager.OnCommunicationDeviceChangedListener { d ->
            if (!confirmed && d?.id == dev.id) {
                confirmed = true
                ready(dev)
            }
        }
        listener = l
        am.addOnCommunicationDeviceChangedListener(executor, l)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!am.setCommunicationDevice(dev)) {
            stop()
            return
        }
        // Already the route (a call's headset, say): no change will be announced.
        if (am.communicationDevice?.id == dev.id && !confirmed) {
            confirmed = true
            ready(dev)
        }
        main.postDelayed(giveUp, 1_500)
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(giveUp)
        listener?.let { runCatching { am.removeOnCommunicationDeviceChangedListener(it) } }
        listener = null
        runCatching { am.clearCommunicationDevice() }
        runCatching { am.mode = mode }
    }
}

/** A dictation's stream over OkHttp: [connect] opens the WebSocket (the daemon's, or the Companion's). */
class SocketSpeech(private val connect: (WebSocketListener) -> WebSocket) : SpeechConnector {
    override fun open(events: SpeechEvents): SpeechLink {
        val ws = connect(
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = events.opened()
                override fun onMessage(webSocket: WebSocket, text: String) = events.message(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    events.closed(code)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.closed(code)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = events.failed(t)
            },
        )
        return object : SpeechLink {
            override fun send(text: String): Boolean = ws.send(text)
            override fun send(bytes: ByteArray): Boolean = ws.send(bytes.toByteString())
            override fun close(code: Int) {
                ws.close(code, null)
            }
        }
    }
}
