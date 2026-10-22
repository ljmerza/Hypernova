package com.prudnikweb.hypernova.ui.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tail's glide (ui/session/SessionScreen.kt): no animations means one jump, otherwise steps that add up. */
class GlideTest {
    @Test fun scaleZeroJumpsOnce() = runBlocking {
        val moves = ArrayList<Float>()
        glide(300f, 30, 0f, { moves += it; it }, { error("no frames") })
        assertEquals(listOf(300f), moves)
    }

    @Test fun otherwiseItStepsAndArrives() = runBlocking {
        var t = 0L
        var total = 0f
        var steps = 0
        glide(300f, 30, 1f, { total += it; steps++; it }, { t += 8_333_333L; t })
        assertEquals(300f, total, 0.01f)
        assertTrue("steps: $steps", steps > 1)
    }
}
