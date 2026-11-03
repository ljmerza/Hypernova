package com.prudnikweb.hypernova.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** Loaded from the resources the desktop build copies from the app's res/font (their licence: third_party/fonts). */
@OptIn(ExperimentalTextApi::class)
private fun font(file: String, weight: Int): Font = Font(
    identity = "$file-$weight",
    getData = { checkNotNull(HN::class.java.classLoader.getResourceAsStream("fonts/$file")) { "no font $file" }.use { it.readBytes() } },
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

internal actual fun sansFont(weight: Int): Font = font("manrope.ttf", weight)

internal actual fun monoFont(weight: Int): Font = font("jetbrains_mono.ttf", weight)

/** JetBrains Mono joins `--`, `->` and such by default: a command must read as the characters it is. */
internal actual val MONO_FEATURES: String? = "liga 0, calt 0"
