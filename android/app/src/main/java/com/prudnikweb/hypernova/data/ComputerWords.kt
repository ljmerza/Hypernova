package com.prudnikweb.hypernova.data

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.R

/**
 * The paired computer in words (audit P10): the app's strings say "your Mac", "the Mac", "Forget
 * this Mac"; paired with a Linux server or PC (docs/LINUX.md) they say "server" instead, and the
 * menu-bar instructions give way to the server's own ("Run hypernova pair on it"). The word is
 * [WORD] (owner decision pending between "server" and "computer": the daemon's own sentences say
 * "server", so this does too).
 *
 * Done once, where the strings come from: [wrap] gives the app (and the Activity) Resources whose
 * text is reworded while the paired computer isn't a Mac, so every screen, notification and widget
 * line follows without each string knowing. Paired with a Mac (or with nothing yet) nothing is
 * wrapped and nothing changes. [platform] is remembered on the phone, so a process that starts cold
 * (the background service, a widget) words things right before the computer has said.
 */
object ComputerWords {
    const val WORD = "server"

    /** State, so the Activity can be made again with the new words when the computer says what it is. */
    var platform: String by mutableStateOf(Mac.DARWIN)
        private set

    private const val PREFS = "hypernova"
    private const val KEY = "computerPlatform"

    fun load(context: Context) {
        platform = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, Mac.DARWIN) ?: Mac.DARWIN
    }

    /** The computer said what it is (its snapshot): remembered. True when it changed. */
    fun set(context: Context, p: String): Boolean {
        val next = if (p == Mac.LINUX) Mac.LINUX else Mac.DARWIN
        if (next == platform) return false
        platform = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next).apply()
        return true
    }

    /** Unpaired: back to the Mac's words (onboarding names both ways itself). */
    fun clear(context: Context) = set(context, Mac.DARWIN)

    /**
     * Strings that say what to do on a Mac (its menu bar), and the server's own way, used in their
     * place on Linux. The rest only change their word ([reword]).
     */
    private val INSTEAD = mapOf(
        R.string.problem_not_running to R.string.problem_not_running_linux,
        R.string.problem_tls to R.string.problem_tls_linux,
        R.string.unpaired_body to R.string.unpaired_body_linux,
        R.string.problem_unpaired to R.string.problem_unpaired_linux,
        R.string.problem_code_expired to R.string.problem_code_expired_linux,
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

    /** [base] as is for a Mac; for a Linux computer, Resources that reword their text. */
    fun wrap(base: Resources): Resources {
        if (platform != Mac.LINUX) return base
        synchronized(made) {
            val cached = made[base]
            if (cached != null && cached.config == base.configuration) return cached
            return Reworded(base).also { made[base] = it }
        }
    }

    /**
     * One wrapper per Resources (the app's, the Activity's), made again when its configuration
     * changes; the few newest only (Resources compare by identity), so an Activity made again lets the old go.
     */
    private val made = object : java.util.LinkedHashMap<Resources, Reworded>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Resources, Reworded>?) = size > 4
    }

    /** The app's Resources, their text reworded; everything else read from the same assets and configuration. */
    @Suppress("DEPRECATION")
    private class Reworded(val base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        val config: Configuration = Configuration(base.configuration)
        override fun getText(id: Int): CharSequence {
            val use = INSTEAD[id] ?: id
            val t = base.getText(use)
            return if (use != id) t else reword(t.toString())
        }
        override fun getText(id: Int, def: CharSequence?): CharSequence? = runCatching { getText(id) }.getOrDefault(def)
        override fun getQuantityText(id: Int, quantity: Int): CharSequence = reword(base.getQuantityText(id, quantity).toString())
        override fun getString(id: Int): String = getText(id).toString()
        override fun getString(id: Int, vararg formatArgs: Any?): String = String.format(base.configuration.locales[0], getString(id), *formatArgs)
        override fun getQuantityString(id: Int, quantity: Int): String = getQuantityText(id, quantity).toString()
        override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
            String.format(base.configuration.locales[0], getQuantityString(id, quantity), *formatArgs)
    }
}
