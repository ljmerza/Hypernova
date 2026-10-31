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
 * Which recognizer hears a dictation (Settings → Voice, docs/DECISIONS.md, "The phone's recognizer
 * for dictation", 2026-10-31). [Phone]: the phone's default recognition service, the one the
 * keyboard's voice typing uses (normally Google's; it may send the audio to Google's servers).
 * [OnDevice]: Android's on-device recognizer only; the audio never leaves the phone.
 */
enum class DictationEngine(val key: String) {
    Phone("phone"),
    OnDevice("onDevice");

    companion object {
        /** The setting's default: the phone's recognizer, as accurate as the keyboard's. */
        val DEFAULT = Phone
        fun of(key: String?): DictationEngine = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** The engine choice and the recognizer's extras, pure, so the JVM tests them (DictationChoiceTest). */
object DictationChoice {
    /**
     * The engine a dictation uses for [setting], given what this phone has: [phone] (a default
     * recognition service) and [onDevice] (an on-device recognizer). The phone's recognizer falls
     * back to on-device. "On-device only" never falls back to the phone's recognizer, which may send
     * the audio off the phone. Null: no dictation ("not available").
     */
    fun engine(setting: DictationEngine, phone: Boolean, onDevice: Boolean): DictationEngine? = when (setting) {
        DictationEngine.Phone -> when {
            phone -> DictationEngine.Phone
            onDevice -> DictationEngine.OnDevice
            else -> null
        }
        DictationEngine.OnDevice -> if (onDevice) DictationEngine.OnDevice else null
    }

    /**
     * The recognizer intent's extras for [engine] on Android [sdk]. Both: free-form, partial results,
     * a few seconds of quiet before it ends by itself; from Android 13, punctuation and capitals tuned
     * for quality, and words left unmasked. On-device also asks to stay offline.
     */
    fun extras(engine: DictationEngine, sdk: Int): Map<String, Any> = buildMap {
        put(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        put(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (engine == DictationEngine.OnDevice) put(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Hands-free pauses to think: a few seconds of quiet before it ends on its own.
        put(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
        put(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            put(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            put(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
        }
    }
}

/**
 * Fork: dictation on the phone, the mic's only way: the phone's own recognizer, as the setting
 * chooses ([DictationEngine], [DictationChoice]). A [Dictation], so the mic, the capsule and the box
 * never know where the words come from. Main thread only, as SpeechRecognizer is.
 */
class PhoneDictation private constructor(context: Context, engine: DictationEngine) : Dictation {
    private val _state = MutableStateFlow<DictationState>(DictationState.Opening)
    override val state: StateFlow<DictationState> = _state
    override val startedAt: Long = SystemClock.elapsedRealtime()

    private val main = Handler(Looper.getMainLooper())
    private val recognizer = when {
        engine == DictationEngine.Phone -> SpeechRecognizer.createSpeechRecognizer(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> onDeviceRecognizer(context)
        // Not reached ([engine] gives no on-device before Android 12), and never the phone's recognizer instead.
        else -> error("No on-device recognizer before Android 12")
    }
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
        for ((k, v) in DictationChoice.extras(engine, Build.VERSION.SDK_INT)) when (v) {
            is Boolean -> intent.putExtra(k, v)
            is Long -> intent.putExtra(k, v)
            is String -> intent.putExtra(k, v)
        }
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
        /** The engine [setting] gets on this phone, or null when it has neither recognizer it may use. */
        fun engine(context: Context, setting: DictationEngine): DictationEngine? =
            DictationChoice.engine(setting, phone = SpeechRecognizer.isRecognitionAvailable(context), onDevice = onDeviceAvailable(context))

        /** This phone can dictate with [setting]. */
        fun available(context: Context, setting: DictationEngine): Boolean = engine(context, setting) != null

        /** A new dictation, listening now; null when [setting] has no recognizer on this phone. */
        fun start(context: Context, setting: DictationEngine): Dictation? = engine(context, setting)?.let { PhoneDictation(context, it) }

        /** An on-device recognizer (Android 12 and later, when the system ships one). */
        private fun onDeviceAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        @RequiresApi(Build.VERSION_CODES.S)
        private fun onDeviceRecognizer(context: Context): SpeechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

        private fun best(b: Bundle?): String = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    }
}
