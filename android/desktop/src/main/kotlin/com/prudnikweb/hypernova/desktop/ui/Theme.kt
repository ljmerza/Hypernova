package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Atlas's tokens (docs/DESIGN.md, "Colour", "Type"), a copy of the app's ui/theme/Tokens.kt for now: that
 * file loads its fonts through R, and sharing it for real means a Compose UI module the phone would build
 * on too, which is a bigger step than this app's first (docs/PLATFORMS.md, "The desktop app").
 * WordsTest holds this copy to the app's values. The marks aren't copied: desktop/build.gradle.kts
 * compiles the app's own ui/components/Marks.kt into this app.
 */

object Atlas {
    val space = Color(0xFF060A17)
    val ink = Color(0xFFF1E9D6)
    val dim = Color(0xFF8E8C80)
    val line = Color(0xFF252A3A)
    val live = Color(0xFF6FE0C2)
    val ask = Color(0xFFFFA63C)
    val stop = Color(0xFFEE4468)
    val idle = Color(0xFF7E7D74)
    val light = Color(0xFFE7DAB8)
    val glow = Color(0xFFF8F0DC)
}

private fun mix(a: Color, b: Color, k: Float) = Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k)

/** As the app's [HN], all from [Atlas]: only those this app draws with. */
object HN {
    val bg = Atlas.space
    val bg900 = mix(Atlas.space, Atlas.ink, 0.045f)
    val bg800 = mix(Atlas.space, Atlas.ink, 0.075f)
    val line = Atlas.line
    val lineStrong = mix(Atlas.space, Atlas.ink, 0.26f)
    val ink = Atlas.ink
    val inkMuted = mix(Atlas.dim, Atlas.ink, 0.42f)
    val inkDim = Atlas.dim
    val idle = Atlas.idle
    val accent = Atlas.live
    val accentDim = mix(Atlas.live, Atlas.space, 0.38f)
    val amber = Atlas.ask
    val danger = Atlas.stop
    val chartLight = Atlas.light
}

/** Loaded from the resources the build copies from the app's res/font (their licence: third_party/fonts). */
@OptIn(ExperimentalTextApi::class)
private fun font(file: String, weight: Int) = Font(
    identity = "$file-$weight",
    getData = { checkNotNull(HN::class.java.classLoader.getResourceAsStream("fonts/$file")) { "no font $file" }.use { it.readBytes() } },
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(font("manrope.ttf", 200), font("manrope.ttf", 400), font("manrope.ttf", 500), font("manrope.ttf", 600), font("manrope.ttf", 700))
val JetBrainsMono = FontFamily(font("jetbrains_mono.ttf", 400), font("jetbrains_mono.ttf", 500), font("jetbrains_mono.ttf", 700))

/** The app's type scale ([docs/DESIGN.md], "Type"). */
object Type {
    private const val FEATURES = "ss01, tnum"
    val title = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.015).em, fontFeatureSettings = FEATURES)
    val heading = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.01).em, fontFeatureSettings = FEATURES)
    val body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 15.sp, lineHeight = 22.sp, fontFeatureSettings = FEATURES)
    val small = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = FEATURES)
    val label = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.18.em, fontFeatureSettings = FEATURES)
    val wordmark = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(200), fontSize = 20.sp, lineHeight = 22.sp, letterSpacing = (-0.02).em)
    /** JetBrains Mono joins `--`, `->` and such by default: a command must read as the characters it is. */
    private const val NO_LIGATURES = "liga 0, calt 0"
    val mono = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight(400), fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.em, fontFeatureSettings = NO_LIGATURES)
    val monoSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight(400), fontSize = 11.sp, lineHeight = 16.sp, fontFeatureSettings = NO_LIGATURES)
}

/**
 * How far round the turning marks are, in degrees: one clock for them all, read only in their draw lambdas,
 * so a tick redraws them and recomposes nothing. Still (0) under [reduced] or while the window hasn't the
 * focus. There's no system-wide Reduce Motion a JVM can read: a test or a later setting says it here.
 */
val LocalTurn = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

private const val TURN_FRAME_MS = 33L
private const val TURN_MS = 3_000f

@Composable
fun AtlasTheme(reduced: Boolean, content: @Composable () -> Unit) {
    val turn = remember { mutableFloatStateOf(0f) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(reduced, focused) {
        if (reduced || !focused) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            delay(TURN_FRAME_MS)
            turn.floatValue = (System.currentTimeMillis() - start) % TURN_MS.toLong() / TURN_MS * 360f
        }
    }
    CompositionLocalProvider(LocalTurn provides turn, content = content)
}
