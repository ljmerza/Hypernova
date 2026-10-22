package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Cron as the Mac reads it (docs/PROTOCOL.md, "Workflows", Cron), for the demo's check and its next three times. */
class CronTest {
    private val zone = ZoneId.of("Europe/Moscow")
    /** Thursday 16 October 2026, 11:40. */
    private val now = ZonedDateTime.of(LocalDate.of(2026, 10, 16), java.time.LocalTime.of(11, 40), zone).toInstant().toEpochMilli()
    private fun at(ms: Long): LocalDateTime = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
    private fun next(expr: String, n: Int = 3) = Cron.next(Cron.parse(expr), now, zone, n).map(::at)
    private fun bad(expr: String) {
        try { Cron.parse(expr); fail("“$expr” should be refused") } catch (_: Cron.Bad) { }
    }

    @Test fun everyQuarterHourOnWeekdayDaytimes() {
        assertEquals(
            listOf(LocalDateTime.of(2026, 10, 16, 11, 45), LocalDateTime.of(2026, 10, 16, 12, 0), LocalDateTime.of(2026, 10, 16, 12, 15)),
            next("*/15 9-18 * * 1-5"),
        )
        // Friday evening runs on to Monday morning.
        val late = ZonedDateTime.of(LocalDate.of(2026, 10, 16), java.time.LocalTime.of(18, 50), zone).toInstant().toEpochMilli()
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), at(Cron.next(Cron.parse("*/15 9-18 * * 1-5"), late, zone, 1).single()))
    }

    @Test fun namesMacrosAndSundays() {
        assertEquals(next("0 9 * * mon-fri"), next("0 9 * * 1-5"))
        assertEquals(next("0 9 * * 0"), next("0 9 * * 7"))
        assertEquals(next("0 9 * * sun"), next("0 9 * * 0"))
        assertEquals(LocalDateTime.of(2026, 10, 18, 9, 0), next("0 9 * * sun").first())
        assertEquals(next("0 0 * * *"), next("@daily"))
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), next("@monthly").first())
        assertEquals(LocalDateTime.of(2026, 10, 16, 12, 0), next("@hourly").first())
        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0), next("0 0 1 jan *").first())
    }

    @Test fun eitherDayFieldIsEnough() {
        // The 1st of the month, or any Monday: cron's own rule when both are narrowed.
        assertEquals(
            listOf(LocalDateTime.of(2026, 10, 19, 8, 0), LocalDateTime.of(2026, 10, 26, 8, 0), LocalDateTime.of(2026, 11, 1, 8, 0)),
            next("0 8 1 * 1"),
        )
        assertEquals(LocalDateTime.of(2026, 11, 1, 8, 0), next("0 8 1 * *").first())
    }

    @Test fun listsAndStepsInRanges() {
        assertEquals(listOf(LocalDateTime.of(2026, 10, 16, 12, 5), LocalDateTime.of(2026, 10, 16, 12, 35), LocalDateTime.of(2026, 10, 16, 14, 5)), next("5,35 12,14 * * *"))
        assertEquals(listOf(LocalDateTime.of(2026, 10, 16, 13, 0), LocalDateTime.of(2026, 10, 16, 15, 0), LocalDateTime.of(2026, 10, 16, 17, 0)), next("0 9-17/2 * * *"))
    }

    @Test fun whatIsRefused() {
        bad("")
        bad("* * * *")
        bad("* * * * * *")
        bad("60 * * * *")
        bad("* 24 * * *")
        bad("* * 0 * *")
        bad("* * * 13 *")
        bad("* * * * 8")
        bad("*/0 * * * *")
        bad("5-1 * * * *")
        bad("* * * * funday")
        bad("1,,2 * * * *")
    }

    @Test fun tooOften() {
        assertTrue(Cron.tooOften(Cron.parse("* * * * *"), now, zone))
        assertTrue(Cron.tooOften(Cron.parse("*/2 * * * *"), now, zone))
        assertTrue("two minutes apart once an hour", Cron.tooOften(Cron.parse("0,3 * * * *"), now, zone))
        assertFalse(Cron.tooOften(Cron.parse("*/5 * * * *"), now, zone))
        assertFalse(Cron.tooOften(Cron.parse("*/15 9-18 * * 1-5"), now, zone))
    }

    @Test fun theDemoClockKnowsCron() {
        assertEquals(LocalDateTime.of(2026, 10, 16, 11, 45), at(DemoClock.next(Trigger.Cron("*/15 9-18 * * 1-5"), now, zone)!!))
        assertEquals(null, DemoClock.next(Trigger.Cron("not a schedule"), now, zone))
    }
}
