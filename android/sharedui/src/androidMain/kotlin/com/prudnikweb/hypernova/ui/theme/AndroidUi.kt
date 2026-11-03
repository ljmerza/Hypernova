package com.prudnikweb.hypernova.ui.theme

import android.content.res.Resources
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings

/**
 * What the shared drawing needs from the phone app and can't reach from here: `R` is the app's. It's handed over before
 * anything draws (HypernovaApp). `Font(id)` only reads its id when a screen uses it, so a plain JVM test that names a style
 * without an Android behind it is still fine.
 */
object AndroidUi {
    internal var manrope = 0
        private set
    internal var mono = 0
        private set
    internal var stringId: (Str) -> Int = { error("AndroidUi.install hasn't run") }
        private set
    internal var pluralId: (Plural) -> Int = { error("AndroidUi.install hasn't run") }
        private set
    internal var words: (Resources) -> Strings = { error("AndroidUi.install hasn't run") }
        private set

    fun install(manrope: Int, mono: Int, stringId: (Str) -> Int, pluralId: (Plural) -> Int, words: (Resources) -> Strings) {
        this.manrope = manrope
        this.mono = mono
        this.stringId = stringId
        this.pluralId = pluralId
        this.words = words
    }
}
