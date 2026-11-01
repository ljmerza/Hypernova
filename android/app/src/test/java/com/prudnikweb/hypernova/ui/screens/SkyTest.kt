package com.prudnikweb.hypernova.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.ui.fx.Cosmos
import com.prudnikweb.hypernova.ui.fx.CosmosState
import com.prudnikweb.hypernova.ui.fx.SkyPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sky drawn (ui/fx/Cosmos.kt): the far sky (stars, nebulae, the deep-sky objects) and the solar
 * system over it, the sun as the 5-hour usage with its readout, planets for the projects that
 * matter now and the belt of quiet ones. Home hides the system now (owner, 2026-10-30), but the
 * drawing is still the app's, and a move to another toolkit must keep it. Everything it draws from
 * is pinned: the sky's seed, the wall clock (the test's own, moving with the frames), the samples.
 */
@RunWith(RobolectricTestRunner::class)
class SkyTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    private companion object {
        /** The wall clock at the start (2027-01-15), as the contract fixtures' times. */
        const val T = 1_800_000_000_000L
        const val HOUR = 3_600_000L
    }

    @Before fun setUp() {
        resetPrefs()
        Samples.NOW = T
        // The sky's own seed (SkyPictures reads it once): the same stars and galaxies every run.
        context.getSharedPreferences("sky", Context.MODE_PRIVATE).edit().putLong("seed", 0x5eedL).commit()
    }

    @Test fun theSunThePlanetsAndTheFarSky() {
        val sessions = Samples.sessions()
        val plan = SkyPlanner.plan(sessions, T)
        val usage = Usage(0.42f, 0.18f, T + 2 * HOUR + 10 * 60_000L + 30_000L, T + 4 * 24 * HOUR)
        val state = CosmosState()
        val start = rule.mainClock.currentTime
        rule.setContent {
            Harness(spy) {
                Cosmos(sessions, usage, state, Modifier.fillMaxSize(), plan = plan, wallClock = { T + rule.mainClock.currentTime - start })
            }
        }
        rule.waitForIdle()
        // Drawn once (Robolectric draws only when asked): the sky starts making its pictures, off
        // the main thread; then their fade-in plays out on the test's clock, drawn again each second.
        rule.onRoot().captureToImage()
        val deadline = System.currentTimeMillis() + 20_000
        while (state.sky.pictures.doneAt == 0L) {
            check(System.currentTimeMillis() < deadline) { "the sky's pictures never came" }
            Thread.sleep(20)
        }
        repeat(6) {
            rule.mainClock.advanceTimeBy(1_000)
            rule.onRoot().captureToImage()
        }
        rule.waitForIdle()
        // Planets for the projects that matter now, most urgent first; the readout under the sun.
        assertEquals(plan.planets.map { it.name }, state.projects())
        assertTrue(plan.planets.isNotEmpty())
        assertTrue("the band is laid out", state.usedBottomPx > 0f)
        assertTrue("the readout is drawn", state.readoutBottomPx > 0f)
        rule.snap("sky")
    }
}
