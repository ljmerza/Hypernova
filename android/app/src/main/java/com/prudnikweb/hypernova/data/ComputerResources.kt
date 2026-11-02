package com.prudnikweb.hypernova.data

import android.content.res.Configuration
import android.content.res.Resources

/**
 * The paired computer's words ([ComputerWords]) where Android's strings come from: [wrap] gives the app
 * (and the Activity) Resources whose text is reworded while the paired computer isn't a Mac, so every
 * screen, notification and widget line follows without each string knowing.
 */
object ComputerResources {
    /** Strings that say what to do on a Mac, and the server's own way ([ComputerWords.INSTEAD]). */
    private val INSTEAD: Map<Int, Int> by lazy { ComputerWords.INSTEAD.entries.associate { (from, to) -> from.id to to.id } }

    /** [base] as is for a Mac; for a Linux computer, Resources that reword their text. */
    fun wrap(base: Resources): Resources = if (ComputerWords.platform != Mac.LINUX) base else reworded(base)

    /** [base] worded for [computer] ([ComputerWords.wordedFor], for what the screens read from Resources). */
    fun wordedFor(base: Resources, computer: String): Resources {
        val plain = plain(base)
        return if (computer == Mac.LINUX) reworded(plain) else plain
    }

    /** [base] without a rewording. */
    fun plain(base: Resources): Resources = (base as? Reworded)?.base ?: base

    private fun reworded(base: Resources): Resources {
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
            return if (use != id) t else ComputerWords.reword(t.toString())
        }
        override fun getText(id: Int, def: CharSequence?): CharSequence? = runCatching { getText(id) }.getOrDefault(def)
        override fun getQuantityText(id: Int, quantity: Int): CharSequence = ComputerWords.reword(base.getQuantityText(id, quantity).toString())
        override fun getString(id: Int): String = getText(id).toString()
        override fun getString(id: Int, vararg formatArgs: Any?): String = String.format(base.configuration.locales[0], getString(id), *formatArgs)
        override fun getQuantityString(id: Int, quantity: Int): String = getQuantityText(id, quantity).toString()
        override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
            String.format(base.configuration.locales[0], getQuantityString(id, quantity), *formatArgs)
    }
}
