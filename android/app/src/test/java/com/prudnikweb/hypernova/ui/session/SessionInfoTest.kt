package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Finished
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.automations.XmlWords
import com.prudnikweb.hypernova.data.ChangedFile
import com.prudnikweb.hypernova.data.ChangesRepo
import com.prudnikweb.hypernova.data.WorkChanges
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** The session sheet's TIME and GIT parts (ui/session/SessionInfoModel.kt). */
class SessionInfoTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 10, 31, 15, 0)
    private val min = 60_000L

    private fun session(phase: Phase, createdAt: Long, updatedAt: Long, turn: Long = 0L) =
        Session("00", "s", "s", "p", SessionState.Working, "opus", "default", "", updatedAt, phase = phase, createdAt = createdAt, turnStartedAt = turn)

    @Test fun aWorkingSessionShowsEveryRowThisTurnFromTheMacsStart() {
        val rows = sheetTimeRows(session(Phase.Working, now - 134 * min, now - 2 * min, turn = now - 3 * min - 12_000), now, zone)
        assertEquals(
            listOf(TimeRow.Started(now - 134 * min), TimeRow.Running(134 * min, ended = false), TimeRow.Turn(3 * min + 12_000), TimeRow.Last(Finished.Minutes(2))),
            rows,
        )
    }

    @Test fun anIdleSessionRunsOnToNowButHasNoTurn() {
        // The Mac still says a turn began (a stale value): out of a turn, it isn't shown.
        val rows = sheetTimeRows(session(Phase.Idle, now - 60 * min, now - 20 * min, turn = now - 30 * min), now, zone)
        assertEquals(listOf(TimeRow.Started(now - 60 * min), TimeRow.Running(60 * min, false), TimeRow.Last(Finished.Minutes(20))), rows)
    }

    @Test fun needingYouIsStillTheTurn() {
        val rows = sheetTimeRows(session(Phase.NeedsYou, now - 10 * min, now, turn = now - 5 * min), now, zone)
        assertTrue(TimeRow.Turn(5 * min) in rows)
        assertTrue(TimeRow.Last(Finished.Now) in rows)
    }

    @Test fun anEndedSessionRanFromStartToItsLastActivity() {
        val rows = sheetTimeRows(session(Phase.Ended, at(2026, 10, 30, 9, 0), at(2026, 10, 30, 10, 30), turn = 0L), now, zone)
        assertEquals(TimeRow.Running(90 * min, ended = true), rows[1])
        assertEquals(TimeRow.Last(Finished.Yesterday), rows.last())
    }

    @Test fun anUnknownStartLeavesStartedAndRunningOut() {
        val rows = sheetTimeRows(session(Phase.Working, 0L, now - 5_000, turn = now - 40_000), now, zone)
        assertEquals(listOf(TimeRow.Turn(40_000), TimeRow.Last(Finished.Now)), rows)
    }

    @Test fun startedSaysTheClockTimeThenTheDayThenTheDate() {
        val w = XmlWords()
        assertEquals("14:05", startedWords(w, at(2026, 10, 31, 14, 5), now, zone))
        assertEquals("Wed 9:30", startedWords(w, at(2026, 10, 28, 9, 30), now, zone))
        assertEquals("17 Oct 8:00", startedWords(w, at(2026, 10, 17, 8, 0), now, zone))
        assertEquals("2:05 PM", startedWords(XmlWords(h24 = false, locale = Locale.US), at(2026, 10, 31, 14, 5), now, zone))
    }

    @Test fun aheadAndBehindAsMarks() {
        assertEquals("↑2 ↓1", aheadBehind(2, 1))
        assertEquals("↑2", aheadBehind(2, 0))
        assertEquals("↓7", aheadBehind(0, 7))
        assertNull(aheadBehind(0, 0))
        assertNull(aheadBehind(null, null))
    }

    @Test fun linesAddedAndRemovedWithARealMinus() {
        assertEquals("+120" to "−30", lineCounts(120, 30))
        assertEquals("+0" to "−0", lineCounts(0, 0))
    }

    private fun changes(scoped: Boolean, files: Int, dirty: Int?, git: Boolean = true) = WorkChanges(
        git = git, branch = "main", scoped = scoped,
        files = List(files) { ChangedFile("f$it.kt", 'M', false, 10, 3, false, scoped) },
        others = 0, hidden = 0, more = false, totalAdd = 10 * files, totalDel = 3 * files,
        upstream = "origin/main", ahead = 2, behind = 1, dirty = dirty, worktree = "sheet-info",
    )

    @Test fun theGitPartFromTheChanges() {
        val g = sheetGit(changes(scoped = true, files = 7, dirty = 9))!!
        assertEquals("main", g.branch)
        assertEquals("↑2 ↓1", g.marks)
        assertEquals("sheet-info", g.worktree)
        assertEquals(listOf(7, 70, 21), listOf(g.files, g.add, g.del))
        assertTrue(g.showsFiles)
        assertTrue(g.showsDirty)
        assertNull(sheetGit(changes(scoped = false, files = 0, dirty = 0, git = false)))
    }

    @Test fun uncommittedGoesWhenTheLineToWhatChangedSaysTheSame() {
        // Everything changed in the folder, and the same count: said once.
        assertFalse(sheetGit(changes(scoped = false, files = 4, dirty = 4))!!.showsDirty)
        // A credential file too, counted but not listed: both.
        assertTrue(sheetGit(changes(scoped = false, files = 4, dirty = 5))!!.showsDirty)
        // Clean: "none", and no line to What changed.
        val clean = sheetGit(changes(scoped = false, files = 0, dirty = 0))!!
        assertTrue(clean.showsDirty)
        assertFalse(clean.showsFiles)
        // An older Mac doesn't count them.
        assertFalse(sheetGit(changes(scoped = true, files = 2, dirty = null))!!.showsDirty)
    }

    @Test fun theGitFieldsAreReadAndAnOlderMacHasNone() {
        val c = ChangesRepo.parseChanges(
            JSONObject("""{"git":true,"branch":"main","scope":"session","files":[],"others":0,"hidden":0,"more":false,"totalAdd":0,"totalDel":0,
                "upstream":"origin/main","ahead":3,"behind":0,"dirty":5,"worktree":"sheet-info"}"""),
        )
        assertEquals(listOf<Any?>("origin/main", 3, 0, 5, "sheet-info"), listOf(c.upstream, c.ahead, c.behind, c.dirty, c.worktree))
        val gone = ChangesRepo.parseChanges(JSONObject("""{"git":true,"branch":"topic","upstream":"origin/topic","ahead":null,"behind":null,"dirty":0,"worktree":null}"""))
        assertEquals(listOf<Any?>("origin/topic", null, null, 0, null), listOf(gone.upstream, gone.ahead, gone.behind, gone.dirty, gone.worktree))
        val old = ChangesRepo.parseChanges(JSONObject("""{"git":true,"branch":"main"}"""))
        assertEquals(listOf<Any?>(null, null, null, null, null), listOf(old.upstream, old.ahead, old.behind, old.dirty, old.worktree))
    }
}
