package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.prudnikweb.hypernova.data.ChatTimes
import com.prudnikweb.hypernova.data.WEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The words of the time over the chat (session/ChatTimeLabel.kt, ChatClock), and which row it speaks for ([topTime]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatClockTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val zone = ZoneId.of("America/New_York")
    private val us12 = ChatClock(Locale.US, is24 = false, zone)
    private val us24 = ChatClock(Locale.US, is24 = true, zone)

    private fun at(y: Int, mo: Int, d: Int, h: Int = 21, mi: Int = 14, s: Int = 7) = ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    /** Monday, November 2, 2026, 9:14:07 PM. */
    private val monday = at(2026, 11, 2)

    // ─── While scrolling ───

    @Test fun todayAndYesterdaySayThatNotTheDate() {
        assertEquals("Today · 9:14 PM", us12.scrolled(res, monday, at(2026, 11, 2, 23, 0)))
        assertEquals("Yesterday · 9:14 PM", us12.scrolled(res, monday, at(2026, 11, 3, 0, 30)))
    }

    @Test fun thisYearNeedsNoYear() {
        assertEquals("Mon Nov 2 · 9:14 PM", us12.scrolled(res, monday, at(2026, 11, 4, 9, 0)))
    }

    @Test fun anOlderOneHasItsYear() {
        assertEquals("Mon Nov 3, 2025 · 9:14 PM", us12.scrolled(res, at(2025, 11, 3), at(2026, 11, 4, 9, 0)))
    }

    @Test fun yesterdayAcrossNewYearIsStillYesterday() {
        assertEquals("Yesterday · 9:14 PM", us12.scrolled(res, at(2025, 12, 31), at(2026, 1, 1, 9, 0)))
    }

    @Test fun theTwentyFourHourClockHasNoAmOrPm() {
        assertEquals("Today · 21:14", us24.scrolled(res, monday, at(2026, 11, 2, 23, 0)))
        assertEquals("Mon Nov 2 · 21:14", us24.scrolled(res, monday, at(2026, 11, 4, 9, 0)))
        assertEquals("Mon Nov 3, 2025 · 21:14", us24.scrolled(res, at(2025, 11, 3), at(2026, 11, 4, 9, 0)))
    }

    @Test fun theLocaleChoosesTheOrderAndTheWords() {
        val german = ChatClock(Locale.GERMANY, is24 = true, zone).scrolled(res, monday, at(2026, 11, 4, 9, 0))
        assertTrue(german, german.contains("Mo"))
        assertTrue(german, german.contains("Nov"))
        assertTrue(german, german.indexOf('2') < german.indexOf("Nov"))
        assertTrue(german, german.endsWith("21:14"))
    }

    @Test fun midnightIsTodayFromItsFirstMinute() {
        assertEquals("Today · 12:00 AM", us12.scrolled(res, at(2026, 11, 2, 0, 0, 0), at(2026, 11, 2, 0, 1)))
    }

    // ─── The menu's exact line ───

    @Test fun theMenusLineIsTheDateItselfToTheSecond() {
        assertEquals("Mon Nov 2 · 9:14:07 PM", us12.exact(res, monday, at(2026, 11, 2, 23, 0)))
        assertEquals("Mon Nov 2 · 21:14:07", us24.exact(res, monday, at(2026, 11, 2, 23, 0)))
        assertEquals("Mon Nov 3, 2025 · 9:14:07 PM", us12.exact(res, at(2025, 11, 3), at(2026, 11, 4, 9, 0)))
    }

    @Test fun talkBackHearsTheMenusLineInWords() {
        assertEquals("Monday, November 2 at 9:14:07 PM", us12.exactSaid(res, monday, at(2026, 11, 2, 23, 0)))
        assertEquals("Monday, November 3, 2025 at 21:14:07", us24.exactSaid(res, at(2025, 11, 3), at(2026, 11, 4, 9, 0)))
    }

    // ─── A quiet session ───

    @Test fun underThresholdIsNotQuiet() {
        val now = at(2026, 11, 2, 23, 0)
        assertNull(us12.quiet(res, now - 1_000, now))
        assertNull(us12.quiet(res, now - QUIET_AFTER_MS + 1, now))
    }

    @Test fun atTheThresholdItSaysHours() {
        val now = at(2026, 11, 2, 23, 0)
        assertEquals(3 * 3_600_000L, QUIET_AFTER_MS)
        assertEquals("Last message 3 hours ago", us12.quiet(res, now - QUIET_AFTER_MS, now))
        assertEquals("Last message 23 hours ago", us12.quiet(res, now - 23 * 3_600_000L, now))
    }

    @Test fun afterADayItGoesByTheCalendar() {
        assertEquals("Last message yesterday", us12.quiet(res, at(2026, 11, 2, 9, 0), at(2026, 11, 3, 10, 0)))
        assertEquals("Last message 2 days ago", us12.quiet(res, at(2026, 11, 1, 21, 0), at(2026, 11, 3, 22, 0)))
        assertEquals("Last message 30 days ago", us12.quiet(res, at(2026, 10, 4, 9, 0), at(2026, 11, 3, 10, 0)))
    }

    @Test fun overAMonthItNamesTheDay() {
        assertEquals("Last message Sep 14", us12.quiet(res, at(2026, 9, 14, 9, 0), at(2026, 11, 3, 10, 0)))
        assertEquals("Last message Sep 14, 2025", us12.quiet(res, at(2025, 9, 14, 9, 0), at(2026, 11, 3, 10, 0)))
    }

    @Test fun aMessageFromTheFutureBySomeClockIsNotQuiet() {
        val now = at(2026, 11, 2, 23, 0)
        assertNull(us12.quiet(res, now + 3_600_000L, now))
    }

    // ─── Which row it speaks for ───

    private class Row(override val key: Any, override val offset: Int, override val size: Int, override val index: Int = 0) : LazyListItemInfo {
        override val contentType: Any? = null
    }

    private fun info(vararg rows: Row) = object : LazyListLayoutInfo {
        override val visibleItemsInfo: List<LazyListItemInfo> = rows.toList()
        override val viewportStartOffset = -300
        override val viewportEndOffset = 2000
        override val totalItemsCount = rows.size
        override val viewportSize = IntSize(1000, 2300)
        override val orientation = Orientation.Vertical
        override val reverseLayout = false
        override val beforeContentPadding = 300
        override val afterContentPadding = 300
        override val mainAxisItemSpacing = 0
    }

    private val times = ChatTimes.of(listOf(WEvent.User("a", 1_000L, "a"), WEvent.User("b", 2_000L, "b"), WEvent.User("c", 3_000L, "c")))

    @Test fun aRowWhollyUnderTheTopBarDoesntCount() {
        assertEquals(2_000L, topTime(info(Row("a", -200, 200), Row("b", 0, 100)), times))
    }

    @Test fun aRowPartlyInViewCounts() {
        assertEquals(1_000L, topTime(info(Row("a", -200, 201), Row("b", 1, 100)), times))
    }

    @Test fun aRowWithNoRealTimeGivesWayToTheNextOnScreen() {
        assertEquals(1_000L, topTime(info(Row("earlier", 0, 50), Row("queued:1", 50, 50), Row("a", 100, 50)), times))
    }

    @Test fun nothingOnScreenWithARealTimeSaysNothing() {
        assertNull(topTime(info(Row("queued:1", 0, 50), Row("asks", 50, 50)), times))
        assertNull(topTime(info(), times))
    }

    @Test fun aFoldedRunSpeaksByItsFirstCall() {
        val run = ChatRow.Run(listOf(com.prudnikweb.hypernova.data.Item.Tool("b", "Read", "x")))
        assertEquals(2_000L, topTime(info(Row(run.key, 0, 50)), times))
    }
}
