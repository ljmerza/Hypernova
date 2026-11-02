package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's answers of 2026-10-16 that live on the phone: the one low-battery rule (20%),
 * Backpack's "+2 h" as the Mac says it, and the thinking label's state in the transcript.
 */
class AnswersTest {
    private fun battery(p: Int, charging: Boolean = false, onAC: Boolean = false) = MacBattery(p, charging, onAC)

    @Test fun lowBatteryIsTwentyPercentOrLessAndNotCharging() {
        assertTrue(battery(20).low)
        assertTrue(battery(15).low)
        assertFalse(battery(21).low)
        assertFalse(battery(12, charging = true, onAC = true).low)
        // Plugged in but not charging (optimised charging) isn't running down either.
        assertFalse(battery(10, onAC = true).low)
    }

    @Test fun readsWhetherBackpackCanBeExtended() {
        val on = MacAwake.parse(JSONObject("""{"mode":"backpack","holding":true,"until":1800000000000,"extendable":true}"""))!!
        assertTrue(on.extendable)
        // An older Mac doesn't say: no button.
        assertFalse(MacAwake.parse(JSONObject("""{"mode":"backpack","holding":true,"until":1800000000000}"""))!!.extendable)
        val ending = BackpackEnding.parse(JSONObject("""{"t":"mac_backpack_ending","inSeconds":600,"until":1800000000000,"extendBy":7200}"""))!!
        assertEquals(600, ending.inSeconds)
        assertEquals(1_800_000_000_000L, ending.until)
        assertNull(BackpackEnding.parse(JSONObject("""{"t":"mac_backpack_ending"}""")))
    }

    private fun build(events: List<WEvent>, active: Boolean) =
        Items.build(events, emptyMap(), emptyMap(), emptyMap(), "s", null, now = 100_000L, active = active)

    @Test fun thinkingIsOngoingOnlyWhileItIsTheNewestThingInARunningSession() {
        val user = WEvent.User("u", 1_000L, "go")
        val thought = WEvent.Thinking("t", 13_000L, 900)
        val running = build(listOf(user, thought), active = true).filterIsInstance<Item.Thinking>().single()
        assertTrue(running.ongoing)
        // 12 s from the prompt it answers: "Thought for 12s".
        assertEquals(12_000L, running.tookMs)
        // Claude moved on (a reply came after it): settled.
        val moved = build(listOf(user, thought, WEvent.Assistant("a", 14_000L, "done", false)), active = true).filterIsInstance<Item.Thinking>().single()
        assertFalse(moved.ongoing)
        assertEquals(12_000L, moved.tookMs)
        // A session that has stopped isn't thinking.
        assertFalse(build(listOf(user, thought), active = false).filterIsInstance<Item.Thinking>().single().ongoing)
        // No prompt right before it (after a tool, say): no duration is guessed.
        val noPrompt = build(listOf(WEvent.Assistant("a0", 2_000L, "x", false), thought), active = true).filterIsInstance<Item.Thinking>().single()
        assertNull(noPrompt.tookMs)
    }
}
