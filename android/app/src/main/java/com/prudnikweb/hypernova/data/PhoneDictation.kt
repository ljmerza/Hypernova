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
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
     * The recognizer intent's extras for [engine] on Android [sdk]. Both: free-form, partial results;
     * from Android 13, punctuation and capitals tuned for quality, and words left unmasked. On-device
     * also asks to stay offline. [ListenMode.Plain] asks for a few seconds of quiet before a session
     * ends by itself, which recognizers may ignore. [ListenMode.Segmented] asks for one session as
     * long as the cap, in segments; the docs say to set that length only for this.
     */
    fun extras(engine: DictationEngine, sdk: Int, mode: ListenMode = ListenMode.Plain): Map<String, Any> = buildMap {
        put(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        put(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (engine == DictationEngine.OnDevice) put(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (mode == ListenMode.Segmented && sdk >= Build.VERSION_CODES.TIRAMISU) {
            put(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
            // An integer, as RecognizerIntent documents this one.
            put(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, VoiceTiming.MAX_MS.toInt())
        } else {
            put(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
            put(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
        }
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            put(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            put(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
        }
    }

    /** How a dictation's first session is asked for on Android [sdk]: segmented sessions came with Android 13. */
    fun firstMode(sdk: Int): ListenMode = if (sdk >= Build.VERSION_CODES.TIRAMISU) ListenMode.Segmented else ListenMode.Plain
}

/**
 * Fork: dictation on the phone, the mic's only way: the phone's own recognizer, as the setting
 * chooses ([DictationEngine], [DictationChoice]). A [Dictation], so the mic, the capsule and the box
 * never know where the words come from. Main thread only, as SpeechRecognizer is.
 *
 * It keeps listening until the owner ends it or the cap does ([ContinuousDictation]; owner,
 * 2026-11-01): the recognizer's own end at a pause only starts its next session.
 */
class PhoneDictation internal constructor(context: Context, private val engine: DictationEngine) : Dictation {
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
    private val words = ContinuousDictation(DictationChoice.firstMode(Build.VERSION.SDK_INT))
    private var over = false
    /** The two-minute cap, as the Mac's dictation has. */
    private val cap = Runnable { if (!over) { problemOnEnd = VoiceProblem.TooLong; finish() } }
    /** Let go and the recognizer never answers: what was heard goes in anyway. */
    private val giveUp = Runnable { end(words.text, problemOnEnd) }
    /** The recognizer's session ended by itself: the next one. */
    private val listenAgain = Runnable { if (!over && !words.released) listen() }
    private var problemOnEnd: VoiceProblem? = null

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (over) return
                words.ready()
                if (!words.released) _state.value = DictationState.Live(words.final, words.interim)
            }
            override fun onBeginningOfSpeech() {}
            // About -2 dB (quiet) to 10 dB (loud), as Android reports it.
            override fun onRmsChanged(rmsdB: Float) { lvl = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { lvl = 0f }
            override fun onPartialResults(partialResults: Bundle?) {
                if (over) return
                words.partial(best(partialResults))
                show()
            }
            // Android 13's segmented session: the words of one segment, and the session goes on.
            override fun onSegmentResults(segmentResults: Bundle) {
                if (over) return
                words.segment(best(segmentResults))
                show()
            }
            override fun onEndOfSegmentedSession() { if (!over) follow(words.endOfSession(SystemClock.elapsedRealtime())) }
            override fun onResults(results: Bundle?) { if (!over) follow(words.results(best(results), SystemClock.elapsedRealtime())) }
            override fun onError(error: Int) { if (!over) follow(words.error(error, SystemClock.elapsedRealtime())) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listen()
        main.postDelayed(cap, VoiceTiming.MAX_MS)
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        for ((k, v) in DictationChoice.extras(engine, Build.VERSION.SDK_INT, words.mode)) when (v) {
            is Boolean -> intent.putExtra(k, v)
            is Int -> intent.putExtra(k, v)
            is Long -> intent.putExtra(k, v)
            is String -> intent.putExtra(k, v)
        }
        words.started(SystemClock.elapsedRealtime())
        recognizer.startListening(intent)
    }

    private fun follow(step: ListenStep) {
        when (step) {
            ListenStep.Wait -> show()
            is ListenStep.End -> end(step.text, step.problem ?: problemOnEnd)
            is ListenStep.Listen -> {
                lvl = 0f
                show()
                if (step.clearFirst) recognizer.cancel()
                // Not from inside the listener call, and at once while all is well: speech in a gap is lost.
                main.removeCallbacks(listenAgain)
                main.postDelayed(listenAgain, step.afterMs)
            }
        }
    }

    /** The words as they stand; still Opening until the recognizer is ready or something was heard. */
    private fun show() {
        _state.value = when {
            words.released -> DictationState.Settling(words.text, unsettledFrom(words.final, words.interim))
            _state.value == DictationState.Opening && words.text.isEmpty() -> DictationState.Opening
            else -> DictationState.Live(words.final, words.interim)
        }
    }

    override fun level(): Float = lvl

    override fun finish() {
        if (over || words.released) return
        val step = words.finishing()
        show()
        if (step is ListenStep.End) {
            // Between sessions: nothing is listening, so nothing more can come.
            end(step.text, problemOnEnd)
            return
        }
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
        // No words in it: which way this phone's recognizer went, for a look with adb logcat.
        Log.i("Hypernova", "dictation: ${words.mode}, ${words.segments} segments, ${words.restarts} restarts")
        close()
    }

    private fun close() {
        main.removeCallbacks(cap)
        main.removeCallbacks(giveUp)
        main.removeCallbacks(listenAgain)
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
