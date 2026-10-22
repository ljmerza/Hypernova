package com.prudnikweb.hypernova.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.R

/** Colour tokens, ported 1:1 from 0stems (tailwind.config.ts / globals.css). */
object HN {
    // Surfaces: warm near-black, not pure black.
    val bg = Color(0xFF14110E)
    val bg950 = Color(0xFF0F0D0A)
    val bg900 = Color(0xFF1B1814)
    val bg800 = Color(0xFF221F1B)
    val bg700 = Color(0xFF2C2823)

    val line = Color(0xFF2C2823)
    val lineSoft = Color(0xFF221F1B)
    val lineStrong = Color(0xFF45403A)

    val ink = Color(0xFFF5F2EB)
    val inkMuted = Color(0xFFB5B0A6)
    val inkDim = Color(0xFF7A7468)
    val inkGhost = Color(0xFF45403A)

    // Mint accent.
    val accent = Color(0xFF7FFFA0)
    val accentBright = Color(0xFFB6FFC9)
    val accentDim = Color(0xFF4A8C5E)

    // "Needs you" and failures.
    val amber = Color(0xFFE8B341)
    val danger = Color(0xFFFF7878)

    /** Star tint used by the cosmos (rgb 127,255,160). */
    val star = Color(0xFF7FFFA0)
}

/** Motion tokens. */
object Motion {
    /** The signature expo-out curve: cubic-bezier(.16, 1, .3, 1). */
    val Expo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    const val FAST = 150
    const val DIALOG = 180
    const val REVEAL = 500
    const val SECTION = 600
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun mono(weight: Int) = Font(
    R.font.jetbrains_mono,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(200), manrope(400), manrope(500), manrope(600), manrope(700))
val JetBrainsMono = FontFamily(mono(400), mono(500), mono(700))

/** Type scale. Weight 500 almost everywhere, as in 0stems. ss01 + tabular figures. */
object Type {
    private const val FEATURES = "ss01, tnum"

    val display = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 34.sp,
        lineHeight = 34.sp * 0.98f, letterSpacing = (-0.03).em, fontFeatureSettings = FEATURES,
    )
    val title = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 22.sp,
        lineHeight = 26.sp, letterSpacing = (-0.015).em, fontFeatureSettings = FEATURES,
    )
    val heading = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 16.sp,
        lineHeight = 21.sp, letterSpacing = (-0.01).em, fontFeatureSettings = FEATURES,
    )
    val body = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 15.sp,
        lineHeight = 22.sp, fontFeatureSettings = FEATURES,
    )
    val small = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 13.sp,
        lineHeight = 18.sp, fontFeatureSettings = FEATURES,
    )
    /** Tiny tracked uppercase eyebrow. Callers uppercase the text. */
    val label = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 10.sp,
        lineHeight = 12.sp, letterSpacing = 0.18.em, fontFeatureSettings = FEATURES,
    )
    val wordmark = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight(200), fontSize = 20.sp,
        lineHeight = 22.sp, letterSpacing = (-0.02).em,
    )
    val mono = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight(400), fontSize = 12.sp,
        lineHeight = 18.sp, letterSpacing = 0.em,
    )
    val monoSmall = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight(400), fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}
