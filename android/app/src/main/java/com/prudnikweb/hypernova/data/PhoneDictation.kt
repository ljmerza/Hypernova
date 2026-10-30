package com.prudnikweb.hypernova.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork: dictation on the phone itself, the mic's only way. Android's on-device recognizer only
 * ([SpeechRecognizer.createOnDeviceSpeechRecognizer]):
 * the audio never leaves the phone, and a phone without one offers no voice rather than a cloud
 * recognizer. A [Dictation], as the demo's is, so the mic, the capsule and the box are the same.
 * Main thread only, as SpeechRecognizer is.
 */
@RequiresApi(Build.VERSION_CODES.S)
class PhoneDictation private constructor(context: Context) : Dictation {
    private val _state = MutableStateFlow<DictationState>(DictationState.Opening)
    override val state: StateFlow<DictationState> = _state
    override val startedAt: Long = SystemClock.elapsedRealtime()

    private val main = Handler(Looper.getMainLooper())
    private val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    @Volatile private var lvl = 0f
    private var final = ""
    private var interim = ""
    private var released = false
    private var over = false
    /** The two-minute cap, as the Mac's dictation has. */
    private val cap = Runnable { if (!over) { problemOnEnd = VoiceProblem.TooLong; finish() } }
    /** Let go and the recognizer never answers: what was heard goes in anyway. */
    private val giveUp = Runnable { end(heardText(final, interim), problemOnEnd) }
    private var problemOnEnd: VoiceProblem? = null

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (!over && !released) _state.value = DictationState.Live(final, interim) }
            override fun onBeginningOfSpeech() {}
            // About -2 dB (quiet) to 10 dB (loud), as Android reports it.
            override fun onRmsChanged(rmsdB: Float) { lvl = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { lvl = 0f }
            override fun onPartialResults(partialResults: Bundle?) {
                interim = best(partialResults)
                if (over) return
                _state.value = if (released) DictationState.Settling(heardText(final, interim), unsettledFrom(final, interim))
                else DictationState.Live(final, interim)
            }
            override fun onResults(results: Bundle?) {
                final = heardText(final, best(results).ifEmpty { interim })
                interim = ""
                end(final, problemOnEnd)
            }
            override fun onError(error: Int) {
                // Nothing (more) heard or a quiet end are no problem: what was heard is kept.
                val problem = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> problemOnEnd
                    SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceProblem.MicBusy
                    else -> VoiceProblem.Mic
                }
                end(heardText(final, interim), problem)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Hands-free pauses to think: a few seconds of quiet before it ends on its own.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
        recognizer.startListening(intent)
        main.postDelayed(cap, VoiceTiming.MAX_MS)
    }

    override fun level(): Float = lvl

    override fun finish() {
        if (over || released) return
        released = true
        _state.value = DictationState.Settling(heardText(final, interim), unsettledFrom(final, interim))
        recognizer.stopListening()
        main.postDelayed(giveUp, VoiceTiming.SETTLE_MS)
    }

    override fun cancel() {
        if (over) return
        over = true
        recognizer.cancel()
        _state.value = DictationState.Cancelled
        close()
    }

    private fun end(text: String, problem: VoiceProblem?) {
        if (over) return
        over = true
        _state.value = DictationState.Ended(text, problem)
        close()
    }

    private fun close() {
        main.removeCallbacks(cap)
        main.removeCallbacks(giveUp)
        // Destroyed after the listener call that ended it returns, not from inside it.
        main.post { recognizer.destroy() }
    }

    companion object {
        /** This phone has an on-device recognizer (Android 12 and later, when the system ships one). */
        fun available(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** A new dictation, listening now; null when the phone has no on-device recognizer. */
        fun start(context: Context): Dictation? = if (available(context)) PhoneDictation(context) else null

        private fun best(b: Bundle?): String = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    }
}
