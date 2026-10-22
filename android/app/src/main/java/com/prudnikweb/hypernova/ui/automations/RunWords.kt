package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Budget
import com.prudnikweb.hypernova.data.BudgetUsed
import com.prudnikweb.hypernova.data.Run
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** How a run's line reads: a drawn glyph and a colour. */
enum class RunTone { Ok, Quiet, Skipped, Failed, Stopped, Active, Alert, Pending }

/** One run in words: [text] (its result or why), and [late] when it ran late. */
data class RunLine(val text: String, val tone: RunTone, val late: String? = null)

/**
 * Runs in words (spec §6, "Run rows and card status lines"), on the Mac's clock. Pure, so the
 * unit test reads every line with the real strings.
 */
object RunWords {
    /** Run.lateWhy: Hypernova wasn't running then (rather than the Mac being asleep). */
    private const val DOWN = "down"

    /** "Today 9:00", "Wed 9:00" within the week, "17 Oct 9:00" before that. */
    fun at(w: Words, t: Long, now: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(t).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = Summary.clock(w, at.toLocalTime())
        val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
        return when {
            days == 0L -> w.s(R.string.au_when_today, time)
            days in -6..6 -> w.s(R.string.au_when_day, DateTimeFormatter.ofPattern("EEE", w.locale).format(at), time)
            else -> w.s(R.string.au_when_day, DateTimeFormatter.ofPattern("d MMM", w.locale).format(at), time)
        }
    }

    /** When it's due, as the widgets say it: "9:00" today, "Mon 9:00" this week, "17 Oct 9:00" later. */
    fun clockWords(w: Words, t: Long, now: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(t).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = Summary.clock(w, at.toLocalTime())
        val days = ChronoUnit.DAYS.between(today, at.toLocalDate())
        return when {
            days == 0L -> time
            // This week either way: a reset ahead, or one a skipped run waited for.
            days in -6..6 -> w.s(R.string.au_when_day, DateTimeFormatter.ofPattern("EEE", w.locale).format(at), time)
            else -> w.s(R.string.au_when_day, DateTimeFormatter.ofPattern("d MMM", w.locale).format(at), time)
        }
    }

    /** "Next Mon 9:00". */
    fun next(w: Words, t: Long, now: Long, zone: ZoneId): String = w.s(R.string.au_next, clockWords(w, t, now, zone))

    /** "45s", "2m 14s", "1h 03m". */
    /** One line of a run's budget: "12 of 30 min", and how much of it is used (null: no limit to measure by). */
    data class BudgetLine(val text: String, val used: Float?)

    /**
     * A run's budget so far ([used], the Mac's `budgetUsed`) against [budget] (its automation's; null
     * when it has none now): its minutes at work ("12 of 30 min"), and the points of the 5-hour
     * window it used ("2.4 of 10 points") when it counts them. A line each, never joined with "·".
     */
    fun budget(w: Words, used: BudgetUsed, budget: Budget?): List<BudgetLine> {
        val out = mutableListOf<BudgetLine>()
        val minutes = used.minutes.toInt().coerceAtLeast(0)
        when {
            budget?.minutes != null -> out += BudgetLine(w.s(R.string.wf_budget_of_min, minutes, budget.minutes), (used.minutes / budget.minutes).toFloat().coerceIn(0f, 1f))
            budget == null -> out += BudgetLine(w.s(R.string.wf_budget_min, minutes), null)
        }
        val pct = used.windowPct
        if (pct != null) {
            val points = if (pct % 1.0 == 0.0) pct.toInt().toString() else String.format(w.locale, "%.1f", pct)
            when {
                budget?.windowPct != null -> out += BudgetLine(w.s(R.string.wf_budget_of_pts, points, budget.windowPct), (pct / budget.windowPct).toFloat().coerceIn(0f, 1f))
                budget == null -> out += BudgetLine(w.s(R.string.wf_budget_pts, points), null)
            }
        }
        return out
    }

    fun duration(w: Words, ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> w.s(R.string.au_dur_s, s.toInt())
            s < 3600 -> w.s(R.string.au_dur_ms, (s / 60).toInt(), (s % 60).toInt())
            else -> w.s(R.string.au_dur_hm, (s / 3600).toInt(), ((s % 3600) / 60).toInt())
        }
    }

    /** The first of a run's steps that failed or stopped, which Retry can start from; null when none did. */
    fun failedStep(r: Run): Int? = r.steps.indexOfFirst { it.state == Run.FAILED || it.state == Run.STOPPED }.takeIf { it >= 0 }

    /** How long a run's step ran (or has so far), when it started. */
    fun ranStep(s: com.prudnikweb.hypernova.data.RunStep, now: Long): Long? {
        val start = s.startedAt ?: return null
        return ((s.endedAt ?: now) - start).coerceAtLeast(0)
    }

    /** How long it ran (or has run so far), when it started. */
    fun ran(r: Run, now: Long): Long? {
        val start = r.startedAt ?: return null
        return ((r.endedAt ?: now) - start).coerceAtLeast(0)
    }

    /**
     * What a run came to. [ask]: what a run that needs you asks ("Bash: git push"); [provider]:
     * its provider and trouble ("OpenRouter: out of credits") for a provider skip; [codex]: Codex
     * runs its session, so a sign-out, a failed start or a missing agent are Codex's.
     */
    fun line(w: Words, r: Run, now: Long, zone: ZoneId, ask: String? = null, provider: String? = null, codex: Boolean = false): RunLine {
        fun clock(t: Long) = Summary.clock(w, Instant.ofEpochMilli(t).atZone(zone).toLocalTime())
        return when (r.state) {
            Run.QUEUED -> RunLine(w.s(R.string.au_run_queued), RunTone.Active)
            Run.WAITING -> when {
                r.reason == "target_busy" -> RunLine(w.s(R.string.au_run_waiting_busy), RunTone.Active)
                r.until != null -> RunLine(w.s(R.string.au_run_waiting_limit, clockWords(w, r.until, now, zone)), RunTone.Active)
                else -> RunLine(w.s(R.string.au_run_waiting_limit_plain), RunTone.Active)
            }
            Run.RUNNING -> RunLine(w.s(R.string.au_run_running, duration(w, ran(r, now) ?: 0L)), RunTone.Active)
            Run.NEEDS_YOU -> RunLine(ask?.let { w.s(R.string.au_run_needs, it) } ?: w.s(R.string.au_state_needs), RunTone.Alert)
            Run.DONE -> {
                val late = if (r.late >= 60_000L) w.s(if (r.lateWhy == DOWN) R.string.au_run_late_down else R.string.au_run_late, clock(r.firedAt)) else null
                when {
                    r.quiet -> RunLine(w.s(R.string.au_run_nothing_new), RunTone.Quiet, late ?: retried(w, r))
                    else -> RunLine(r.result ?: w.s(R.string.au_run_done), RunTone.Ok, late ?: retried(w, r))
                }
            }
            Run.SKIPPED, Run.FAILED, Run.STOPPED -> reason(w, r, now, zone, provider, codex).let { if (it.late == null) it.copy(late = retried(w, r)) else it }
            else -> RunLine(r.state, RunTone.Quiet)
        }
    }

    /** "A retry", under a run Retry made. */
    private fun retried(w: Words, r: Run): String? = if (r.retryOf != null) w.s(R.string.au_run_retry_note) else null

    /**
     * What Run now or Retry did, for its toast: "Started", "In line: …", "Waiting out your limit
     * until 14:00", or what a run that ended at once came to ([codex]: its session is Codex's).
     */
    fun started(w: Words, r: Run, now: Long, zone: ZoneId, codex: Boolean = false): String = when (r.state) {
        Run.RUNNING, Run.NEEDS_YOU -> w.s(R.string.au_started)
        Run.QUEUED -> w.s(R.string.au_now_queued)
        else -> line(w, r, now, zone, codex = codex).text
    }

    /** Run now's or Retry's refusal for the usage limit: when it can go again, when the Mac said. */
    fun limitedUntil(w: Words, until: Long, now: Long, zone: ZoneId): String = w.s(R.string.au_now_limited, clockWords(w, until, now, zone))

    private fun reason(w: Words, r: Run, now: Long, zone: ZoneId, provider: String?, codex: Boolean): RunLine {
        fun clock(t: Long) = Summary.clock(w, Instant.ofEpochMilli(t).atZone(zone).toLocalTime())
        val skipped = RunTone.Skipped
        val failed = RunTone.Failed
        val stopped = RunTone.Stopped
        // A block that stopped a run once it had started: failed, by the block's own reason.
        if (r.state == Run.FAILED) when (r.reason) {
            "signed_out" -> return RunLine(w.s(if (codex) R.string.au_run_f_signed_out_codex else R.string.au_run_f_signed_out), failed)
            "no_plan" -> return RunLine(w.s(R.string.au_run_f_no_plan), failed)
            "on_hold" -> return RunLine(w.s(R.string.au_run_f_on_hold), failed)
            "no_credit" -> return RunLine(w.s(R.string.au_run_f_no_credit), failed)
            "provider" -> return RunLine(provider?.let { w.s(R.string.au_run_f_provider, it) } ?: w.s(R.string.au_run_f_provider_plain), failed)
            "limited" -> return RunLine(w.s(R.string.au_run_f_limited), failed)
            "blocked" -> return RunLine(w.s(R.string.au_run_blocked), failed)
        }
        return when (r.reason) {
            "missed" -> when {
                r.missed > 1 -> RunLine(w.p(if (r.lateWhy == DOWN) R.plurals.au_run_missed_times_down else R.plurals.au_run_missed_times, r.missed, r.missed), skipped)
                else -> RunLine(w.s(if (r.lateWhy == DOWN) R.string.au_run_missed_down else R.string.au_run_missed, clock(r.firedAt)), skipped)
            }
            "overlap" -> RunLine(w.s(R.string.au_run_overlap), skipped)
            "queue_full" -> RunLine(w.s(R.string.au_run_queue_full), skipped)
            "stale" -> RunLine(w.s(R.string.au_run_stale), skipped)
            "daily_cap" -> RunLine(w.s(R.string.au_run_daily_cap), skipped)
            "limited" -> RunLine(r.until?.let { w.s(R.string.au_run_limited, clockWords(w, it, now, zone)) } ?: w.s(R.string.au_run_limited_plain), skipped)
            "signed_out" -> RunLine(w.s(if (codex) R.string.au_run_signed_out_codex else R.string.au_run_signed_out), skipped)
            "no_plan" -> RunLine(w.s(R.string.au_run_no_plan), skipped)
            "on_hold" -> RunLine(w.s(R.string.au_run_on_hold), skipped)
            "no_credit" -> RunLine(w.s(R.string.au_run_no_credit), skipped)
            "provider" -> RunLine(provider?.let { w.s(R.string.au_run_provider, it) } ?: w.s(R.string.au_run_provider_plain), skipped)
            "no_project" -> RunLine(w.s(R.string.au_run_no_project), failed)
            "no_session" -> RunLine(w.s(R.string.au_run_no_session), failed)
            // A Codex step on a Mac where Codex isn't installed or turned on (the Mac's WorkflowWords say the same).
            "no_provider" -> RunLine(w.s(if (codex) R.string.au_run_no_codex else R.string.au_run_no_provider), failed)
            "target_busy" -> RunLine(w.s(R.string.au_run_target_busy), skipped)
            "loop" -> RunLine(w.s(R.string.au_run_loop), skipped)
            "start_failed" -> RunLine(w.s(if (codex) R.string.au_run_start_failed_codex else R.string.au_run_start_failed), failed)
            "error" -> RunLine(r.result?.let { w.s(R.string.au_run_error, it) } ?: w.s(R.string.au_run_failed), failed)
            "blocked" -> RunLine(w.s(R.string.au_run_blocked), stopped)
            "away" -> RunLine(w.s(R.string.au_run_away), stopped)
            "timeout" -> RunLine(w.s(R.string.au_run_timeout), stopped)
            "by_user" -> RunLine(w.s(R.string.au_run_by_user), stopped)
            "outside_projects" -> RunLine(w.s(R.string.au_run_outside), failed)
            "no_model" -> RunLine(w.s(R.string.au_run_no_model), failed)
            "paused" -> RunLine(w.s(R.string.au_run_paused), skipped)
            "mac_restart" -> RunLine(w.s(R.string.au_run_mac_restart), stopped)
            "ended" -> RunLine(w.s(R.string.au_run_ended), stopped)
            // Workflows: its budget ran out, or a command exited with an error (the failing step's words).
            "budget" -> RunLine(w.s(R.string.wf_run_budget), stopped)
            "exit" -> RunLine(r.result?.let { w.s(R.string.wf_run_exit_said, it) } ?: w.s(R.string.wf_run_exit), failed)
            else -> when (r.state) {
                Run.SKIPPED -> RunLine(w.s(R.string.au_run_skipped), skipped)
                Run.FAILED -> RunLine(w.s(R.string.au_run_failed), failed)
                else -> RunLine(w.s(R.string.au_run_stopped), stopped)
            }
        }
    }
}
