package com.prudnikweb.hypernova.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Log
import com.prudnikweb.hypernova.desktop.ui.Scaled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Dimension

/** The rule for how big things are drawn, the zoom's steps and what's kept of them. A window on a display is checked by hand: docs/DEV-SETUP.md. */
class ScaleTest {
    @get:Rule val temp = TemporaryFolder()

    private val log = object : Log {
        override fun d(tag: String, message: String) = Unit
        override fun w(tag: String, message: String) = Unit
    }

    private fun screen(w: Int, h: Int, system: Float = 1f, dpi: Int? = 96, desktopDpi: Int? = null, linux: Boolean = true) =
        ScreenFacts(system, w, h, dpi, desktopDpi, linux)

    // ─── The rule ───

    @Test fun whatTheSystemAlreadyScalesByIsLeftAlone() {
        assertEquals(2f, autoScale(screen(3840, 2160, system = 2f)), 0f)
        assertEquals("a Windows setting off the steps", 1.33f, autoScale(screen(2560, 1440, system = 1.33f)), 0f)
        assertEquals("past the zoom's top too", 4f, autoScale(screen(7680, 4320, system = 4f)), 0f)
        assertEquals("the system's word beats the desktop's", 2f, autoScale(screen(3840, 2160, system = 2f, desktopDpi = 144)), 0f)
    }

    @Test fun onAMacOrWindowsOneMeansOne() {
        assertEquals(1f, autoScale(screen(3840, 2160, linux = false)), 0f)
        assertEquals(1f, autoScale(screen(3840, 2160, dpi = 163, linux = false)), 0f)
    }

    /** X's made-up 96 on the panels people have. */
    @Test fun withNothingButPixelsTheShortEdgeDecides() {
        assertEquals("1080p", 1f, autoScale(screen(1920, 1080)), 0f)
        assertEquals("1200p", 1f, autoScale(screen(1920, 1200)), 0f)
        assertEquals("1440p", 1f, autoScale(screen(2560, 1440)), 0f)
        assertEquals("a 1440p ultrawide", 1f, autoScale(screen(3440, 1440)), 0f)
        assertEquals("1600p", 1f, autoScale(screen(2560, 1600)), 0f)
        assertEquals("a small high-resolution panel", 1.5f, autoScale(screen(3200, 1800)), 0f)
        assertEquals("4K", 2f, autoScale(screen(3840, 2160)), 0f)
        assertEquals("4K on its side", 2f, autoScale(screen(2160, 3840)), 0f)
        assertEquals("a 2160p ultrawide", 2f, autoScale(screen(5120, 2160)), 0f)
        assertEquals("5K", 2f, autoScale(screen(5120, 2880)), 0f)
        assertEquals("8K, the most it goes", 3f, autoScale(screen(7680, 4320)), 0f)
        assertEquals("no DPI at all", 2f, autoScale(screen(3840, 2160, dpi = null)), 0f)
    }

    @Test fun aRealDpiDecidesWhereItCanBeTold() {
        assertEquals("4K at 27 inches", 1.75f, autoScale(screen(3840, 2160, dpi = 163)), 0f)
        assertEquals("4K at 32 inches", 1.5f, autoScale(screen(3840, 2160, dpi = 138)), 0f)
        assertEquals("1440p at 13 inches", 1.5f, autoScale(screen(2560, 1440, dpi = 226)), 0f)
        assertEquals("1440p at 27 inches is about 1, under the line", 1f, autoScale(screen(2560, 1440, dpi = 109)), 0f)
        assertEquals("1080p at 15 inches stays 1", 1f, autoScale(screen(1920, 1080, dpi = 147)), 0f)
        assertEquals("low can't be told from made up", 2f, autoScale(screen(3840, 2160, dpi = 102)), 0f)
    }

    @Test fun theDesktopsOwnScaleDecidesFromOneAndAHalf() {
        assertEquals("a desktop at 150% on 4K", 1.5f, autoScale(screen(3840, 2160, desktopDpi = 144)), 0f)
        assertEquals("before the screen's DPI", 2f, autoScale(screen(3840, 2160, dpi = 138, desktopDpi = 192)), 0f)
        assertEquals("1440p at 150%", 1.5f, autoScale(screen(2560, 1440, desktopDpi = 144)), 0f)
        assertEquals("Large Text says nothing about the panel", 2f, autoScale(screen(3840, 2160, desktopDpi = 120)), 0f)
        assertEquals("the default says nothing", 2f, autoScale(screen(3840, 2160, desktopDpi = 96)), 0f)
        assertEquals("a compositor's 1080p, already scaled up, isn't scaled again", 1f, autoScale(screen(1920, 1080, desktopDpi = 192)), 0f)
    }

    // ─── The window ───

    @Test fun theFirstWindowFollowsTheScaleAndFitsTheScreen() {
        val fourK = Dimension(3840, 2120)
        assertEquals(Dimension(1920, 1440), firstWindowSize(2f, 1f, fourK))
        assertEquals(Dimension(960, 720), firstWindowSize(1f, 1f, Dimension(1920, 1040)))
        assertEquals("the JVM's own scale comes off: its units are already bigger", Dimension(960, 720), firstWindowSize(2f, 2f, Dimension(1920, 1040)))
        assertEquals("zoomed past the screen", Dimension(1920, 1040), firstWindowSize(3f, 1f, Dimension(1920, 1040)))
        assertEquals("a small laptop", Dimension(960, 688), firstWindowSize(1f, 1f, Dimension(1366, 688)))
    }

    @Test fun theSmallestWindowFollowsTheScaleAndFitsTheScreen() {
        assertEquals(Dimension(520, 420), minimumWindowSize(1f, 1f, Dimension(1920, 1040)))
        assertEquals(Dimension(1040, 840), minimumWindowSize(2f, 1f, Dimension(3840, 2120)))
        assertEquals(Dimension(390, 315), minimumWindowSize(0.75f, 1f, Dimension(1920, 1040)))
        assertEquals("3x on a 1080p screen can still be resized", Dimension(1560, 1040), minimumWindowSize(3f, 1f, Dimension(1920, 1040)))
    }

    // ─── The keys ───

    @Test fun theKeys() {
        assertEquals(ZoomKey.In, zoomKey(Key.Equals, ctrl = true, alt = false, meta = false))
        assertEquals(ZoomKey.In, zoomKey(Key.Plus, ctrl = true, alt = false, meta = false))
        assertEquals(ZoomKey.In, zoomKey(Key.NumPadAdd, ctrl = true, alt = false, meta = false))
        assertEquals(ZoomKey.Out, zoomKey(Key.Minus, ctrl = true, alt = false, meta = false))
        assertEquals(ZoomKey.Out, zoomKey(Key.NumPadSubtract, ctrl = true, alt = false, meta = false))
        assertEquals(ZoomKey.Reset, zoomKey(Key.Zero, ctrl = true, alt = false, meta = false))
        assertEquals(ZoomKey.Reset, zoomKey(Key.NumPad0, ctrl = true, alt = false, meta = false))
        assertNull("typing a dash", zoomKey(Key.Minus, ctrl = false, alt = false, meta = false))
        assertNull("AltGr is Ctrl and Alt on some systems", zoomKey(Key.Zero, ctrl = true, alt = true, meta = false))
        assertNull("the system's", zoomKey(Key.Minus, ctrl = true, alt = false, meta = true))
        assertNull("pasting", zoomKey(Key.V, ctrl = true, alt = false, meta = false))
    }

    /** Found with a real keyboard event: Ctrl and = pressed in the pairing field typed an = too. */
    @Test fun aZoomKeysCharacterIsntTyped() {
        for (c in "=+-0") assertTrue("$c", zoomKeyTyped(c, ctrl = true, alt = false, meta = false))
        for (c in "=+-0") assertFalse("$c typed as itself", zoomKeyTyped(c, ctrl = false, alt = false, meta = false))
        assertFalse("AltGr's characters", zoomKeyTyped('0', ctrl = true, alt = true, meta = false))
        assertFalse("another key", zoomKeyTyped('a', ctrl = true, alt = false, meta = false))
    }

    // ─── The steps ───

    @Test fun theStepsGoFromThreeQuartersToThree() {
        var s = Zoom.MIN
        val up = mutableListOf(s)
        while (Zoom.stepUp(s) != s) { s = Zoom.stepUp(s); up += s }
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f), up)
        assertEquals("it stops at the top", 3f, Zoom.stepUp(3f), 0f)
        assertEquals("and at the bottom", 0.75f, Zoom.stepDown(0.75f), 0f)
        assertEquals(1.75f, Zoom.stepDown(2f), 0f)
    }

    @Test fun aScaleOffTheStepsGoesToTheNearestOneThatWay() {
        assertEquals(1.5f, Zoom.stepUp(1.33f), 0f)
        assertEquals(1.25f, Zoom.stepDown(1.33f), 0f)
        assertEquals("a system's 4 can come down", 3f, Zoom.stepDown(4f), 0f)
        assertEquals("but zooming in doesn't shrink it", 4f, Zoom.stepUp(4f), 0f)
    }

    // ─── What's kept ───

    private fun home() = temp.root.toPath().resolve("home")

    @Test fun aChoiceBeatsTheAutomaticOneAndSurvivesARestart() {
        DesktopStorage(home(), log).use { s ->
            val zoom = Zoom(s.prefs(Zoom.PREFS))
            assertNull(zoom.chosen)
            assertEquals("nothing chosen", 2f, zoom.scale(2f), 0f)
            assertEquals(2.25f, zoom.press(ZoomKey.In, 2f), 0f)
            assertEquals(2.5f, zoom.press(ZoomKey.In, 2f), 0f)
            assertEquals(2.25f, zoom.press(ZoomKey.Out, 2f), 0f)
            s.flush()
        }
        DesktopStorage(home(), log).use { s ->
            val zoom = Zoom(s.prefs(Zoom.PREFS))
            assertEquals(2.25f, zoom.chosen)
            assertEquals("on a 1080p screen too", 2.25f, zoom.scale(1f), 0f)
        }
    }

    @Test fun resetForgetsItForGood() {
        DesktopStorage(home(), log).use { s ->
            val zoom = Zoom(s.prefs(Zoom.PREFS))
            zoom.press(ZoomKey.Out, 2f)
            assertEquals(2f, zoom.press(ZoomKey.Reset, 2f), 0f)
            assertNull(zoom.chosen)
            s.flush()
        }
        DesktopStorage(home(), log).use { s -> assertNull(Zoom(s.prefs(Zoom.PREFS)).chosen) }
    }

    @Test fun aSavedScaleOutOfRangeIsIgnored() {
        DesktopStorage(home(), log).use { s ->
            val prefs = s.prefs(Zoom.PREFS)
            for (bad in listOf(0, 10, 74, 301, 90_000, -100)) {
                prefs.edit().putInt("zoomPercent", bad).apply()
                assertNull("$bad", Zoom(prefs).chosen)
            }
            prefs.edit().putString("zoomPercent", "big").apply()
            assertNull("not a number", Zoom(prefs).chosen)
            prefs.edit().putInt("zoomPercent", 175).apply()
            assertEquals(1.75f, Zoom(prefs).chosen)
        }
    }

    // ─── The pointer ───

    /** A click at the very pixel a thing is drawn on, at each scale: only dp changes, not where the pointer is. */
    @OptIn(ExperimentalTestApi::class)
    @Test fun aClickAndTypingLandAtEveryScale() {
        for (scale in listOf(0.75f, 1f, 2f, 3f)) runDesktopComposeUiTest(1600, 1200) {
            var hits by mutableStateOf(0)
            var typed by mutableStateOf("")
            setContent {
                Scaled(scale) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.offset(100.dp, 80.dp).size(40.dp).testTag("target").clickable { hits++ })
                        BasicTextField(typed, { typed = it }, Modifier.offset(100.dp, 200.dp).size(200.dp, 40.dp).testTag("field"))
                    }
                }
            }
            val b = onNodeWithTag("target").fetchSemanticsNode().boundsInRoot
            assertEquals("drawn at $scale px per dp", 100 * scale, b.left, 0.5f)
            onRoot().performMouseInput { click(b.center) }
            assertEquals("the middle of it", 1, hits)
            onRoot().performMouseInput { click(Offset(b.right + 2f, b.center.y)) }
            assertEquals("just past its edge", 1, hits)
            onNodeWithTag("field").performClick()
            onNodeWithTag("field").performTextInput("typed")
            assertEquals("typed", typed)
        }
    }
}
