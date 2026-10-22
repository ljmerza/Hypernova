package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Finding an old session in History (data/HistorySearch.kt, audit P9). */
class HistorySearchTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun s(name: String, project: String, ago: Long, activity: String = "") =
        Session(hex = "00", id = name, name = name, project = project, state = SessionState.Idle, model = "opus", mode = "default", activity = activity, updatedAt = now - ago, phase = Phase.Ended)

    private val ended = listOf(
        s("release landing copy", "0stems", 2 * hour, "Pushed to main."),
        s("Fix the widget crash", "hypernova", 30 * hour, "The widget no longer crashes on a cold start."),
        s("agent cards", "hypernova", 5 * hour),
        s("Weather in Lisbon", "zazhimayu-pogoda", 50 * hour, "Added the forecast for tomorrow."),
    )

    @Test fun blankFindsEverythingMostRecentFirst() {
        assertEquals(listOf("release landing copy", "agent cards", "Fix the widget crash", "Weather in Lisbon"), HistorySearch.find(ended, "  ").map { it.name })
    }

    @Test fun findsByTitleProjectOrLastLine() {
        assertEquals(listOf("Fix the widget crash"), HistorySearch.find(ended, "widget").map { it.name })
        assertEquals(listOf("Weather in Lisbon"), HistorySearch.find(ended, "pogoda").map { it.name })
        assertEquals(listOf("Weather in Lisbon"), HistorySearch.find(ended, "forecast").map { it.name })
        // Every word has to be there.
        assertEquals(listOf("Fix the widget crash"), HistorySearch.find(ended, "hypernova crash").map { it.name })
        assertTrue(HistorySearch.find(ended, "kubernetes").isEmpty())
    }

    @Test fun aProjectNarrowsIt() {
        assertEquals(listOf("agent cards", "Fix the widget crash"), HistorySearch.find(ended, "", "hypernova").map { it.name })
        assertEquals(listOf("agent cards"), HistorySearch.find(ended, "cards", "hypernova").map { it.name })
    }

    @Test fun projectChipsOnlyWithSeveralBusiestFirst() {
        assertEquals(listOf("hypernova", "0stems", "zazhimayu-pogoda"), HistorySearch.projects(ended))
        assertTrue(HistorySearch.projects(ended.filter { it.project == "hypernova" }).isEmpty())
        assertEquals(2, HistorySearch.projects(ended, max = 2).size)
    }

    @Test fun daysReadAsTodayYesterdayOrTheDate() {
        val today = LocalDate.of(2026, 10, 18)
        assertEquals("Today", HistorySearch.label(today, today, "Today", "Yesterday", Locale.UK))
        assertEquals("Yesterday", HistorySearch.label(today.minusDays(1), today, "Today", "Yesterday", Locale.UK))
        assertEquals("Tue 13 Oct", HistorySearch.label(LocalDate.of(2026, 10, 13), today, "Today", "Yesterday", Locale.UK))
        assertEquals("Wed 31 Dec 2025", HistorySearch.label(LocalDate.of(2025, 12, 31), today, "Today", "Yesterday", Locale.UK))
        val zone = ZoneId.of("Europe/Moscow")
        assertEquals(HistorySearch.day(now, zone), HistorySearch.day(now - 1_000, zone))
    }
}
