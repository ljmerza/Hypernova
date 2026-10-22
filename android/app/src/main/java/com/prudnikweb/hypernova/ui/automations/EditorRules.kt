package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.AutomationInput
import com.prudnikweb.hypernova.data.Trigger
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Claude Code's allow rules, checked as the Mac checks them (daemon/src/automations/text.ts,
 * checkRule): `Tool` or `Tool(spec)`, and never one that amounts to everything: a bare Bash, Edit,
 * Write, MultiEdit or NotebookEdit, a spec of `*`, `**`, `:*` or a whole tree, or a shell or an
 * interpreter with any arguments (`Bash(sh -c:*)`, `Bash(python3:*)`). The Mac's own words come
 * back on a refusal all the same; this only spares the round trip.
 */
object Rules {
    const val BAD = "bad_rule"
    const val BROAD = "rule_too_broad"
    private val SHAPE = Regex("^([A-Za-z][\\w-]*)(?:\\((.*)\\))?$", RegexOption.DOT_MATCHES_ALL)
    private val BROAD_TOOLS = setOf("Bash", "Edit", "Write", "MultiEdit", "NotebookEdit")
    private val EVERYTHING = setOf("*", "**", ":*", "/**", "./**", "~/**", "//**", "**/*", "/**/*", "./**/*", "~/**/*", "//**/*")
    private val RUNS_ANYTHING = Regex("^(?:sudo|env|xargs|eval|exec|command|nohup|bash|sh|zsh|fish|dash|ksh|csh|tcsh|python[\\d.]*|node|deno|bun|ruby|perl|php|osascript)(?:\\s+-\\w+)*\\s*:?\\s*\\*$")

    /** Null when fine; else [BAD] or [BROAD]. */
    fun check(rule: String): String? {
        val r = rule.trim()
        val m = SHAPE.matchEntire(r)
        if (r.isEmpty() || r.length > 200 || m == null || r.contains('\n') || r.contains('\r')) return BAD
        val spec = m.groups[2]?.value
        if (spec != null && spec.isBlank()) return BAD
        val tool = m.groupValues[1]
        val broad = spec == null || spec.replace(Regex("\\s+"), "") in EVERYTHING || (tool == "Bash" && RUNS_ANYTHING.matches(spec.trim()))
        return if (tool in BROAD_TOOLS && broad) BROAD else null
    }

    /**
     * The modes whose rules apply: Allowed only (all it may do), Ask me and Edits on its own (what
     * it does without asking besides). Auto and No asking keep none (the Mac drops them).
     */
    fun kept(mode: String): Boolean = mode == AutoMode.ALLOWED || mode == AutoMode.ASK || mode == AutoMode.EDITS
}

/** The editor's checks and moves that don't need the screen; unit-tested. */
object EditorRules {
    /**
     * A Once in the past can't be saved, unless it stays off with its time as it was (a rename of a
     * spent one: the Mac allows it then, A6).
     */
    fun pastOnce(d: AutomationInput, baseline: AutomationInput, now: Long): Boolean {
        val t = d.trigger as? Trigger.Once ?: return false
        if (t.at > now) return false
        return d.enabled || t != baseline.trigger
    }

    /** "The session that fired it" with a trigger that isn't a session's (the Mac's `bad_target`, A7). */
    fun badTarget(d: AutomationInput): Boolean {
        val a = d.action as? Action.Continue ?: return false
        return a.target == Action.Continue.TRIGGER && d.trigger !is Trigger.SessionEvent
    }

    /** A spent Once at the same time of day, on its next day to come: today if that time is still ahead, else tomorrow (A6). */
    fun nextOnce(t: Trigger.Once, now: Long, zone: ZoneId): Trigger.Once {
        val time = Instant.ofEpochMilli(t.at).atZone(zone).toLocalTime().withSecond(0).withNano(0)
        val today = Instant.ofEpochMilli(now).atZone(zone)
        var at = ZonedDateTime.of(today.toLocalDate(), time, zone)
        if (!at.isAfter(today)) at = at.plusDays(1)
        return Trigger.Once(at.toInstant().toEpochMilli())
    }

    /**
     * Every few hours around the clock runs on one grid every day (each step divides 24 hours): its
     * first run of the day, "HH:MM" (from 9:00 every 2 hours: 1:00). As the Mac works it out
     * (daemon/src/schedule.ts, everyGrid).
     */
    fun firstOfDay(from: String, hours: Int): String {
        val t = runCatching { LocalTime.parse(from) }.getOrNull() ?: return from
        if (hours <= 0 || 24 % hours != 0) return from
        val m = (t.hour * 60 + t.minute) % (hours * 60)
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }
}

/** A spent Once on its next day at that time: the editor opened from the card's switch (A6). */
fun nextOnce(t: Trigger.Once, now: Long, zone: ZoneId): Trigger.Once = EditorRules.nextOnce(t, now, zone)
