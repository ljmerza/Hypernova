package com.prudnikweb.hypernova.ui.theme

import androidx.compose.ui.text.font.Font

/** One of Manrope's weights, each app's own way to the same file: on Android its `res/font`, on the desktop the copy the build puts in its resources. */
internal expect fun sansFont(weight: Int): Font

/** One of JetBrains Mono's. */
internal expect fun monoFont(weight: Int): Font

/** What a mono style asks of its font: nothing on the phone, as it always was; the desktop turns the ligatures off (docs/DECISIONS.md, "The desktop app"). */
internal expect val MONO_FEATURES: String?
