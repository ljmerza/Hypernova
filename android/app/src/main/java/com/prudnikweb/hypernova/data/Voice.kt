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
import kotlin.math.sin

/*
 * Voice on the phone (docs/specs/spec-voice.md, 3.6): the mic dictates with Android's on-device
 * recognizer (PhoneDictation.kt), so the audio never leaves the phone. Nothing runs until a press,
 * nothing is written down, and words are never lost: whatever ends a dictation, what was heard goes
 * into the box. This file has no Android audio in it, so its logic runs in JVM tests.
 */

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
 * The one-time hints and the microphone's permission, kept in their own preferences file (spec
 * 3.7). Compose state, so the composers follow each other.
 */
class VoicePrefs private constructor(private val prefs: SharedPreferences) {
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
