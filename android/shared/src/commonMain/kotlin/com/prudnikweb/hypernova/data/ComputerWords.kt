package com.prudnikweb.hypernova.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * The paired computer in words (audit P10): the app's strings say "your Mac", "the Mac", "Forget
 * this Mac"; paired with a Linux server or PC (docs/LINUX.md) they say "server" instead, and the
 * menu-bar instructions give way to the server's own ("Run hypernova pair on it"). The word is
 * [WORD] (owner decision pending between "server" and "computer": the daemon's own sentences say
 * "server", so this does too).
 *
 * Done once, where the strings come from: the platform's strings are reworded while the paired
 * computer isn't a Mac (on Android, the app's and the Activity's Resources: data/ComputerResources.kt),
 * so every screen, notification and widget line follows without each string knowing. Paired with a Mac
 * (or with nothing yet) nothing is wrapped and nothing changes. [platform] is remembered on the
 * device, so a process that starts cold (the background service, a widget) words things right before
 * the computer has said.
 */
object ComputerWords {
    const val WORD = "server"

    /** State, so the Activity can be made again with the new words when the computer says what it is. */
    var platform: String by mutableStateOf(Mac.DARWIN)
        private set

    private const val PREFS = "hypernova"
    private const val KEY = "computerPlatform"

    fun load(storage: Storage) {
        platform = storage.prefs(PREFS).getString(KEY, Mac.DARWIN) ?: Mac.DARWIN
    }

    /** The computer said what it is (its snapshot): remembered. True when it changed. */
    fun set(storage: Storage, p: String): Boolean {
        val next = if (p == Mac.LINUX) Mac.LINUX else Mac.DARWIN
        if (next == platform) return false
        platform = next
        storage.prefs(PREFS).edit().putString(KEY, next).apply()
        return true
    }

    /** Unpaired: back to the Mac's words (onboarding names both ways itself). */
    fun clear(storage: Storage) = set(storage, Mac.DARWIN)

    /**
     * Strings that say what to do on a Mac (its menu bar), and the server's own way, used in their
     * place on Linux. The rest only change their word ([reword]).
     */
    val INSTEAD: Map<Str, Str> = mapOf(
        Str.problem_not_running to Str.problem_not_running_linux,
        Str.problem_tls to Str.problem_tls_linux,
        Str.unpaired_body to Str.unpaired_body_linux,
        Str.problem_unpaired to Str.problem_unpaired_linux,
        Str.problem_code_expired to Str.problem_code_expired_linux,
    )

    private val ARTICLE = Regex("""\b(your|Your|the|The|this|This|a|A)\s+Mac\b(?!Book)""")
    private val BARE = Regex("""\bMac\b(?!Book|OS)""")

    /**
     * "your Mac" → "your server", "The Mac's" → "The server's", "Mac voice" → "Server voice", "MAC"
     * stays as it's only ever the label's own word upper-cased by the screen. MacBook and macOS are
     * left alone, as are words that only begin with Mac ("Machines").
     */
    fun reword(text: String, word: String = WORD): String {
        if (!text.contains("Mac")) return text
        val withArticle = ARTICLE.replace(text) { m ->
            val art = m.groupValues[1]
            val a = if (art == "a" || art == "A") (if (word.first().lowercaseChar() in "aeiou") "${art}n" else art) else art
            "$a $word"
        }
        return BARE.replace(withArticle) { m ->
            val atStart = m.range.first == 0 || withArticle.substring(0, m.range.first).trimEnd().let { it.isEmpty() || it.last() in ".!?:\n(" }
            if (atStart) word.replaceFirstChar { it.uppercase() } else word
        }
    }

    /**
     * [base] worded for [computer] ([Mac.LINUX] or not), whichever computer is on screen: a hub's
     * own messages (LiveHub's toasts and errors) say what its own computer is, a Linux server's
     * "server" even next to a Mac, and from its first snapshot on, not only after a restart.
     */
    fun wordedFor(base: Strings, computer: String): Strings {
        val plain = base.plain
        return if (computer == Mac.LINUX) Reworded(plain) else plain
    }

    /** [base], its text reworded. */
    private class Reworded(val base: Strings) : Strings {
        override fun text(key: Str): String {
            val instead = INSTEAD[key]
            return if (instead != null) base.text(instead) else reword(base.text(key))
        }
        override fun quantityText(key: Plural, quantity: Int): String = reword(base.quantityText(key, quantity))
        override val locale: Locale get() = base.locale
        override val plain: Strings get() = base
        override fun timePattern(withDay: Boolean): String = base.timePattern(withDay)
    }
}
