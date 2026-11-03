package com.prudnikweb.hypernova.ui.theme

/**
 * What the shared drawing needs from the phone app and can't reach from here: `R` is the app's. It's handed over before
 * anything draws (HypernovaApp), as `Font(id)` only reads its id when a screen uses it, so a plain JVM test that names a
 * style without an Android behind it is still fine.
 */
object AndroidUi {
    internal var manrope = 0
        private set
    internal var mono = 0
        private set

    fun install(manrope: Int, mono: Int) {
        this.manrope = manrope
        this.mono = mono
    }
}
