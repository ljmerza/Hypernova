package com.prudnikweb.hypernova.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.R

/**
 * The colours a theme chooses, as few as they can be: everything else derives from them ([HN]). Only
 * Atlas's own set exists today; a theme selector (docs/LATER.md, L109) would swap this one value.
 */
@Immutable
class Palette(
    /** The page, behind everything. */
    val space: Color,
    /** Text, and the chart's lines. */
    val ink: Color,
    /** Quiet text. */
    val dim: Color,
    /** Hairlines. */
    val line: Color,
    /** Working: at work, primary, success. */
    val live: Color,
    /** Needs you: approvals, waiting. */
    val ask: Color,
    /** Limits, errors, deny. */
    val stop: Color,
    /** At rest: an idle mark. */
    val idle: Color,
    /** The chart's stipple. */
    val light: Color,
    /** The brightest highlight. */
    val glow: Color,
)

/**
 * Atlas (owner, 2026-11-01): near-black navy and warm parchment, verdigris for working and amber for
 * needs you. docs/concepts/art on the `art-concepts` branch measured the two apart under colour
 * blindness (OKLab 0.22 in normal vision, 0.15 deutan, 0.17 protan, 0.27 tritan, against today's mint
 * and amber's 0.12 deutan), and each state's mark has a shape of its own besides.
 */
val AtlasColors = Palette(
    space = Color(0xFF060A17),
    ink = Color(0xFFF1E9D6),
    dim = Color(0xFF8E8C80),
    line = Color(0xFF252A3A),
    live = Color(0xFF6FE0C2),
    ask = Color(0xFFFFA63C),
    stop = Color(0xFFEE4468),
    idle = Color(0xFF7E7D74),
    light = Color(0xFFE7DAB8),
    glow = Color(0xFFF8F0DC),
)

/** [a] and [b] mixed by [k] in plain sRGB, as the concept's surfaces are. */
private fun mix(a: Color, b: Color, k: Float) = Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k)

/** The app's colours, all from one [Palette] (the widget's and the terminal's repeat them as text: ui/theme is the source). */
object HN {
    private val p = AtlasColors

    // Surfaces: the page, then ink laid over it. Not pure black.
    val bg = p.space
    val bg950 = mix(p.space, Color.Black, 0.35f)
    val bg900 = mix(p.space, p.ink, 0.045f)
    val bg800 = mix(p.space, p.ink, 0.075f)
    val bg700 = mix(p.space, p.ink, 0.12f)

    val line = p.line
    val lineSoft = bg800
    val lineStrong = mix(p.space, p.ink, 0.26f)

    val ink = p.ink
    val inkMuted = mix(p.dim, p.ink, 0.42f)
    val inkDim = p.dim
    val inkGhost = lineStrong

    /** An idle mark. */
    val idle = p.idle

    // The accent: working, primary, success.
    val accent = p.live
    val accentBright = mix(p.live, p.glow, 0.5f)
    val accentDim = mix(p.live, p.space, 0.38f)

    // "Needs you" and failures.
    val amber = p.ask
    val danger = p.stop

    /** The chart's stipple (fx/Atlas.kt). */
    val chartLight = p.light
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
