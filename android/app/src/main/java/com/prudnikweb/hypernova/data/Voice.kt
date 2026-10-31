package com.prudnikweb.hypernova.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * Voice on the phone (docs/specs/spec-voice.md, 3.6): the mic dictates with the phone's own
 * recognizer, or Android's on-device one only, as Settings → Voice chooses (PhoneDictation.kt); the
 * audio never goes to the Mac. Nothing runs until a press,
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
    /** Settings → Voice: which recognizer dictation uses, from the next dictation on. */
    var dictationEngine by mutableStateOf(DictationEngine.of(prefs.getString("dictationEngine", null)))
        private set

    fun chooseDictationEngine(e: DictationEngine) { dictationEngine = e; prefs.edit().putString("dictationEngine", e.key).apply() }
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
