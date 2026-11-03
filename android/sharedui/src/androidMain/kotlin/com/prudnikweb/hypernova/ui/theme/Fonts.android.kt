package com.prudnikweb.hypernova.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal actual fun sansFont(weight: Int): Font = Font(
    AndroidUi.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal actual fun monoFont(weight: Int): Font = Font(
    AndroidUi.mono,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

internal actual val MONO_FEATURES: String? = null
