package com.prudnikweb.hypernova.ui.term

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/** The terminal pages' host: the app's own assets, served by TerminalScreen's WebViewClient. */
internal const val TERM_HOST = "appassets.androidplatform.net"

/**
 * What draws a session's live terminal: xterm.js (assets/term/, the default), or, as a trial,
 * Ghostty's terminal core (assets/term-ghostty/: ghostty-web, Ghostty's VT code compiled to
 * WebAssembly). The same bridge, socket and keys either way; only the page differs.
 */
enum class TermEngine(val dir: String) {
    Xterm("term"),
    Ghostty("term-ghostty");

    val page: String get() = "https://$TERM_HOST/$dir/index.html"

    companion object {
        fun of(name: String?): TermEngine = entries.firstOrNull { it.name == name } ?: Xterm
    }
}

/**
 * Settings → Terminal engine (testing only: a debug build, or seven taps on the build), kept on this
 * phone. A terminal already open keeps its page; the next one opened takes the new one.
 */
object TermEnginePref {
    private const val PREFS = "hypernova_terminal"
    private const val KEY = "engine"
    private var current: MutableState<TermEngine>? = null

    fun state(context: Context): State<TermEngine> = holder(context)

    fun set(context: Context, value: TermEngine) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
        holder(context).value = value
    }

    private fun holder(context: Context): MutableState<TermEngine> = current
        ?: mutableStateOf(TermEngine.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))).also { current = it }
}

/**
 * The asset a terminal page asks for at [path] (`/term/xterm.js`, `/term-ghostty/ghostty-web.js`):
 * a file directly in one of the engines' folders, so the Ghostty page shares term/'s font. Null for
 * anything else (another folder, a subfolder, `..`).
 */
internal fun termAsset(path: String?): String? {
    if (path == null) return null
    val dir = TermEngine.entries.firstOrNull { path.startsWith("/${it.dir}/") }?.dir ?: return null
    val name = path.removePrefix("/$dir/")
    if (name.isEmpty() || name.contains("..") || name.contains('/')) return null
    return "$dir/$name"
}
