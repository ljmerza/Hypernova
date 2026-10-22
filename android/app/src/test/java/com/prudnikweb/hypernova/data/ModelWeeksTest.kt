package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * The weeks some models have of their own (docs/PROTOCOL.md, "Per-model weeks"): read off the
 * wire, rolled over at their reset, filled by a model limit Claude Code hit, and how each meter
 * says when it resets ("[FABLE] 7 DAYS 41%  ↻ Tue 20:00"), and how TalkBack says it.
 */
class ModelWeeksTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val week = 7 * 24 * hour

    @Test fun readsTheModelsAndOlderMacsHaveNone() {
        val u = Wire.usage(
            JSONObject(
                """{"fiveHour":{"pct":23,"resetsAt":1},"sevenDay":null,"source":"official","observedAt":5,
                   "models":[{"label":"Sonnet","pct":3,"resetsAt":1800000600000},{"label":"Fable","pct":41.5,"resetsAt":null},{"pct":9},{"label":"Odd","pct":"x"}]}""",
            ),
        )!!
        assertEquals(listOf(WModelWeek("Sonnet", 3.0, 1_800_000_600_000L), WModelWeek("Fable", 41.5, null)), u.models)
        assertEquals(emptyList<WModelWeek>(), Wire.usage(JSONObject("""{"fiveHour":null,"sevenDay":null,"source":"none","observedAt":null}"""))!!.models)
    }

    @Test fun aWeekPastItsResetIsEmptyAndAModelLimitFillsItsOwnWeek() {
        val wire = listOf(WModelWeek("Sonnet", 12.0, now - hour), WModelWeek("Fable", 41.0, now + 50 * hour))
        val calm = ModelWeeks.of(wire, Account(), now)
        assertEquals(ModelWeek("Sonnet", 0f, now - hour + week), calm[0])
        assertEquals(ModelWeek("Fable", 0.41f, now + 50 * hour), calm[1])
        assertFalse(calm[1].full)
        // Claude Code stopped Fable ("Fable limit"): its week is full until that reset, Sonnet's isn't.
        val limited = Account(state = Account.LIMITED, limit = Account.Limit("model", "Fable limit", now + 49 * hour, null, false), since = now - 60_000)
        val hit = ModelWeeks.of(wire, limited, now)
        assertEquals(ModelWeek("Fable", 1f, now + 49 * hour), hit[1])
        assertTrue(hit[1].full)
        assertEquals(0f, hit[0].pct)
        // Past a limit on usage credits, nothing is stopped: the week reads as the Mac said.
        val credits = limited.copy(limit = limited.limit!!.copy(usingCredits = true))
        assertEquals(0.41f, ModelWeeks.of(wire, credits, now)[1].pct)
        // /usage may name the week after the model's full name ("Fable 5.1"): "Fable limit" still fills it,
        // and a limit on another model ("Opus limit") fills none.
        val full = listOf(WModelWeek("Fable 5.1", 41.0, now + 50 * hour))
        assertEquals(1f, ModelWeeks.of(full, limited, now)[0].pct)
        val opus = limited.copy(limit = limited.limit!!.copy(label = "Opus limit"))
        assertEquals(0.41f, ModelWeeks.of(full, opus, now)[0].pct)
    }

    @Test fun theMeterSaysTheWeekdayFarOffHowLongNearAndTheLimit() {
        assertEquals(ModelWeeks.Trail.On(now + 50 * hour), ModelWeeks.trail(ModelWeek("Fable", 0.41f, now + 50 * hour), now))
        assertEquals(ModelWeeks.Trail.In(5 * hour), ModelWeeks.trail(ModelWeek("Fable", 0.41f, now + 5 * hour), now))
        assertEquals(ModelWeeks.Trail.Limit(5 * hour), ModelWeeks.trail(ModelWeek("Fable", 1f, now + 5 * hour), now))
        assertEquals(ModelWeeks.Trail.None, ModelWeeks.trail(ModelWeek("Fable", 0.41f, null), now))
        // Tuesday 20 October 2026, 20:00 in Moscow.
        val tue = 1_792_515_600_000L
        val msk = TimeZone.getTimeZone("Europe/Moscow")
        assertEquals("Tue 20:00", ModelWeeks.weekdayTime(tue, is24 = true, Locale.ENGLISH, msk))
        assertEquals("Tue 8:00 PM", ModelWeeks.weekdayTime(tue, is24 = false, Locale.ENGLISH, msk))
    }

    @Test fun talkBackHearsTheDayInFullAndTheTimeApart() {
        // Tuesday 20 October 2026, 20:00 in Moscow: "resets on Tuesday at 8:00 PM", never "resets in Tue 8:00 PM".
        val tue = 1_792_515_600_000L
        val msk = TimeZone.getTimeZone("Europe/Moscow")
        assertEquals("Tuesday" to "8:00 PM", ModelWeeks.weekdayTimeSaid(tue, is24 = false, Locale.ENGLISH, msk))
        assertEquals("Tuesday" to "20:00", ModelWeeks.weekdayTimeSaid(tue, is24 = true, Locale.ENGLISH, msk))
    }
}
