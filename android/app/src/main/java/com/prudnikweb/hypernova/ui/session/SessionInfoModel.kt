package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Finished
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.automations.RunWords
import com.prudnikweb.hypernova.ui.automations.Words
import com.prudnikweb.hypernova.data.WorkChanges
import java.time.ZoneId

/*
 * The session sheet's TIME and GIT parts in its Session tab (SessionInfo.kt; owner, 2026-10-31,
 * ideas SS7 to SS9), as plain data with nothing of Compose in it (unit-tested).
 */

/** A row of the TIME part, in its order. */
internal sealed interface TimeRow {
    /** When it started, on this phone's clock. */
    data class Started(val at: Long) : TimeRow
    /** Start to now while it's live; start to its last activity once it has ended ("Ran for"). */
    data class Running(val ms: Long, val ended: Boolean) : TimeRow
    /** This turn so far, while it's in one: the Mac's `turnStartedAt` to now. */
    data class Turn(val ms: Long) : TimeRow
    /** Its last activity: "just now", "2m ago", "yesterday". */
    data class Last(val ago: Finished) : TimeRow
}

/**
 * [s]'s TIME rows at [now]: Started and Running for once its start is known, This turn only while
 * it's in a turn (starting, working, or stopped for an answer) and the Mac said when that began,
 * then Last activity.
 */
internal fun sheetTimeRows(s: Session, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<TimeRow> = buildList {
    val ended = s.phase == Phase.Ended
    if (s.createdAt > 0L) {
        add(TimeRow.Started(s.createdAt))
        add(TimeRow.Running(((if (ended) s.updatedAt else now) - s.createdAt).coerceAtLeast(0L), ended))
    }
    val inTurn = s.phase == Phase.Starting || s.phase == Phase.Working || s.phase == Phase.NeedsYou
    if (inTurn && s.turnStartedAt > 0L) add(TimeRow.Turn((now - s.turnStartedAt).coerceAtLeast(0L)))
    if (s.updatedAt > 0L) add(TimeRow.Last(Finished.of(s.updatedAt, now, zone)))
}

/** When it started, as the widgets say a time: "14:05" today, "Mon 14:05" this week, "17 Oct 14:05" before. */
internal fun startedWords(w: Words, at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    RunWords.clockWords(w, at, now, zone)

/**
 * The GIT part: the branch (null: a detached HEAD), its [upstream] and how far apart, the linked
 * worktree's name, the uncommitted files in the folder, and the files this session touched
 * ([scoped]; else everything changed in the folder, as What changed lists them) with their +/-.
 */
internal data class SheetGit(
    val branch: String?,
    val upstream: String?,
    val ahead: Int?,
    val behind: Int?,
    val worktree: String?,
    val dirty: Int?,
    val files: Int,
    val add: Int,
    val del: Int,
    val scoped: Boolean,
) {
    /** "↑2 ↓1", "↑2", "↓1"; null when even with the upstream or it isn't known. */
    val marks: String? get() = aheadBehind(ahead, behind)

    /** The line to What changed: only when something changed. */
    val showsFiles: Boolean get() = files > 0

    /**
     * Uncommitted, unless the line to What changed already says it: everything changed in the
     * folder, and the same count. Clean, it says so ("none").
     */
    val showsDirty: Boolean get() = dirty != null && (files == 0 || scoped || dirty != files)
}

/** The GIT part from the changes route's answer; null for a folder that isn't a repository. */
internal fun sheetGit(c: WorkChanges): SheetGit? = if (!c.git) null else SheetGit(
    branch = c.branch?.takeIf { it.isNotBlank() },
    upstream = c.upstream,
    ahead = c.ahead,
    behind = c.behind,
    worktree = c.worktree,
    dirty = c.dirty,
    files = c.files.size,
    add = c.totalAdd,
    del = c.totalDel,
    scoped = c.scoped,
)

/** Commits ahead of and behind the upstream as marks: "↑2 ↓1"; a side with none left out; null for none either way. */
internal fun aheadBehind(ahead: Int?, behind: Int?): String? {
    val parts = buildList {
        if (ahead != null && ahead > 0) add("↑$ahead")
        if (behind != null && behind > 0) add("↓$behind")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/** Lines added and removed, as What changed draws them side by side: "+120" and "−30" (a real minus sign). */
internal fun lineCounts(add: Int, del: Int): Pair<String, String> = "+$add" to "−$del"
