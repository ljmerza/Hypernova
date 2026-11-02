package com.prudnikweb.hypernova.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

/*
 * Statistics with "All servers" (docs/DECISIONS.md, "Combined Statistics in All servers"): every
 * server is asked for the same range and the answers are added up into one [Stats], which the
 * Statistics screen draws as it draws one server's. A server that isn't linked isn't asked, and one
 * that fails is left out; both are named ([AllStatsAnswer.missing]). [AllStats.combine] is pure.
 */

/** A server Statistics asks in All servers: its id and name as paired, and its hub. */
class StatsServer(val id: String, val name: String, val hub: Hub)

/**
 * What All servers' Statistics shows: the servers that answered added up ([stats], null when none
 * did), each one's own answer ([each], in the order paired), and the names of those that didn't
 * ([missing]: not linked, or failed; [outdated]: too old to know this range), whose numbers aren't
 * in [stats]. [problem]: why the last of them failed, for when none answered.
 */
class AllStatsAnswer(
    val stats: Stats?,
    val each: List<Pair<StatsServer, Stats>>,
    val missing: List<String>,
    val problem: ApiException?,
    val outdated: List<String> = emptyList(),
)

object AllStats {
    /** A day counts towards the streak with at least this much work (the daemon's own rule). */
    private const val WORKED_MS = 60_000L

    /**
     * Several servers' answers for one range, as one. Every count and time is summed (two servers
     * busy at once count twice, as two sessions on one do); the days are summed date by date (each
     * server's calendar); the busiest day and the streak are worked out again from those days;
     * projects of the same name are one project, summed; tools of the same name are one tool. The
     * longest session is the longest of any server's. One answer is returned as it is; none: null.
     */
    fun combine(parts: List<Stats>): Stats? {
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts.single()
        val range = parts.first().range
        val days = days(parts)
        return Stats(
            range = range,
            from = parts.minOf { it.from },
            to = parts.maxOf { it.to },
            since = parts.mapNotNull { it.since }.minOrNull(),
            // With older days on any server: hours start at the earliest any server has them (one
            // without older days has them from its own start).
            hoursSince = if (parts.none { it.hoursSince != null }) null else parts.mapNotNull { it.hoursSince ?: it.since }.minOrNull(),
            // From the latest of them on, every server was counting what only it sees.
            countingSince = parts.maxOf { it.countingSince },
            claudeMs = parts.sumOf { it.claudeMs },
            wallMs = parts.sumOf { it.wallMs },
            agentMs = parts.sumOf { it.agentMs },
            tokens = StatTokens(
                parts.sumOf { it.tokens.input }, parts.sumOf { it.tokens.output },
                parts.sumOf { it.tokens.cacheRead }, parts.sumOf { it.tokens.cacheWrite },
            ),
            sessions = parts.sumOf { it.sessions },
            prompts = parts.sumOf { it.prompts },
            fromPhone = parts.sumOf { it.fromPhone },
            byVoice = parts.sumOf { it.byVoice },
            toolCalls = parts.sumOf { it.toolCalls },
            tools = tools(parts),
            filesEdited = parts.sumOf { it.filesEdited },
            agents = parts.sumOf { it.agents },
            approvals = parts.sumOf { it.approvals },
            approvalsPhone = parts.sumOf { it.approvalsPhone },
            approvalsMac = parts.sumOf { it.approvalsMac },
            limitsFiveHour = parts.sumOf { it.limitsFiveHour },
            limitsWeek = parts.sumOf { it.limitsWeek },
            limitsModel = parts.sumOf { it.limitsModel },
            busiestDay = busiest(parts, days, range),
            longestSession = parts.mapNotNull { it.longestSession }.maxByOrNull { it.claudeMs },
            streak = streak(parts, days),
            projects = projects(parts),
            days = days,
            claude = agentPart(parts, codex = false),
            codex = agentPart(parts, codex = true),
        )
    }

    /** Each date's work on every server added up, oldest first, as many days as the longest answer has (the last 30). */
    fun days(parts: List<Stats>): List<StatDay> {
        val by = sortedMapOf<LocalDate, StatDay>()
        for (s in parts) for (d in s.days) by.merge(d.day, d) { a, b ->
            StatDay(a.day, a.claudeMs + b.claudeMs, a.wallMs + b.wallMs, a.tokens + b.tokens, a.sessions + b.sessions, a.prompts + b.prompts, a.codexMs + b.codexMs)
        }
        // Servers a timezone apart: one's today may be the other's tomorrow; the chart keeps its 30.
        return by.values.toList().takeLast(parts.maxOf { it.days.size })
    }

    /**
     * The range's busiest day from the summed days. For All and YTD, a server's own busiest day from
     * before the last 30 days is a candidate too (the others' days that far back aren't sent: those
     * on the same date are added up, so it's at least that much). YTD's summed days stop at January 1,
     * the earliest of the servers' (two servers a timezone apart can be in different years for hours).
     */
    private fun busiest(parts: List<Stats>, days: List<StatDay>, range: StatsRange): StatDay? {
        val yearStart = parts.mapNotNull { it.days.lastOrNull()?.day }.minOrNull()?.withDayOfYear(1)
        val inRange = when (range) {
            StatsRange.All -> days
            StatsRange.Ytd -> days.filter { yearStart == null || it.day >= yearStart }
            else -> days.takeLast(range.days)
        }
        val recent = inRange.filter { it.claudeMs > 0L }.maxByOrNull { it.claudeMs }?.let { StatDay(it.day, it.claudeMs, 0L, it.tokens, 0, 0) }
        val first = days.firstOrNull()?.day
        val older = if ((range != StatsRange.All && range != StatsRange.Ytd) || first == null) emptyList() else parts.mapNotNull { it.busiestDay }
            .filter { it.day < first }
            .groupBy { it.day }
            .map { (day, ds) -> StatDay(day, ds.sumOf { it.claudeMs }, 0L, ds.sumOf { it.tokens }, 0, 0) }
        return (listOfNotNull(recent) + older).filter { it.claudeMs > 0L }.maxByOrNull { it.claudeMs }
    }

    /**
     * Days in a row with a minute of work on any server, to the last day (or the one before, while
     * it hasn't started). A streak that fills the whole chart may go back further: no shorter than
     * any server's own then, since a day one server worked is a day with work.
     */
    private fun streak(parts: List<Stats>, days: List<StatDay>): Int {
        var i = days.lastIndex
        if (i >= 0 && days[i].claudeMs < WORKED_MS) i--
        var n = 0
        while (i >= 0 && days[i].claudeMs >= WORKED_MS) { n++; i-- }
        return maxOf(n, parts.maxOf { it.streak })
    }

    /** Projects by name across servers, summed (the same folder name on two servers is one project), by hours, 8 at most. */
    private fun projects(parts: List<Stats>): List<StatProject> {
        val by = LinkedHashMap<String, StatProject>()
        for (s in parts) for (p in s.projects) by.merge(p.name, p) { a, b ->
            StatProject(a.name, a.path ?: b.path, a.claudeMs + b.claudeMs, a.tokens + b.tokens, a.sessions + b.sessions)
        }
        return by.values.sortedWith(compareByDescending<StatProject> { it.claudeMs }.thenByDescending { it.tokens }).take(8)
    }

    /** Tools by name across servers, summed, most used first: 8, then "Other" (the daemon's own shape). */
    private fun tools(parts: List<Stats>): List<StatTool> {
        val by = LinkedHashMap<String, Int>()
        for (s in parts) for (t in s.tools) by.merge(t.name, t.count, Int::plus)
        val other = by.remove(OTHER) ?: 0
        val ranked = by.entries.sortedByDescending { it.value }
        val rest = other + ranked.drop(8).sumOf { it.value }
        return ranked.take(8).map { StatTool(it.key, it.value) } + listOfNotNull(rest.takeIf { it > 0 }?.let { StatTool(OTHER, it) })
    }

    private const val OTHER = "Other"

    /**
     * Claude's or Codex's part, summed. Codex: the servers that have one (none: null). Claude: null
     * only when no server split them (all older daemons); otherwise an older server's whole answer
     * is Claude's, as [Stats.claudeOwnMs] reads it.
     */
    private fun agentPart(parts: List<Stats>, codex: Boolean): StatAgent? {
        val each = if (codex) parts.mapNotNull { it.codex } else {
            if (parts.all { it.claude == null }) return null
            parts.map { s ->
                s.claude ?: StatAgent(s.claudeOwnMs, (s.tokens.total - (s.codex?.tokens ?: 0L)).coerceAtLeast(0L), s.sessions - (s.codex?.sessions ?: 0), s.prompts - (s.codex?.prompts ?: 0), s.toolCalls - (s.codex?.toolCalls ?: 0))
            }
        }
        if (each.isEmpty()) return null
        return StatAgent(each.sumOf { it.ms }, each.sumOf { it.tokens }, each.sumOf { it.sessions }, each.sumOf { it.prompts }, each.sumOf { it.toolCalls })
    }

    /** [servers] linked now: the ones asked. */
    private fun linked(servers: List<StatsServer>) = servers.filter { Servers.fresh(it.hub.link.state) }

    private fun answer(servers: List<StatsServer>, got: Map<String, Result<Stats>>): AllStatsAnswer {
        val each = servers.mapNotNull { sv -> got[sv.id]?.getOrNull()?.let { sv to it } }
        val refused = { sv: StatsServer -> (got[sv.id]?.exceptionOrNull() as? ApiException)?.refusedRange == true }
        val outdated = servers.filter(refused).map { it.name }
        val missing = servers.filter { sv -> got[sv.id]?.isSuccess != true && !refused(sv) }.map { it.name }
        // None answered: a real failure says why, ahead of a server that only needs updating.
        val failures = got.values.mapNotNull { it.exceptionOrNull() as? ApiException }
        val problem = if (each.isNotEmpty()) null
        else failures.lastOrNull { !it.refusedRange } ?: failures.lastOrNull() ?: ApiException(Problem.Unreachable)
        return AllStatsAnswer(combine(each.map { it.second }), each, missing, problem, outdated)
    }

    /** The last answers for [range], shown at once while fresh ones come: only when every linked server has one. */
    fun cached(servers: List<StatsServer>, range: StatsRange): AllStatsAnswer? {
        val asked = linked(servers)
        if (asked.isEmpty()) return null
        val got = asked.associate { sv -> sv.id to Result.success(StatsSource.cached(sv.hub, range) ?: return null) }
        return answer(servers, got)
    }

    /** Every linked server's last answer for [range] is from the last half minute. */
    fun fresh(servers: List<StatsServer>, range: StatsRange): Boolean =
        linked(servers).let { l -> l.isNotEmpty() && l.all { StatsSource.fresh(it.hub, range) } }

    /**
     * Ask every linked server for [range] at once (a fresh answer is used as it is, unless [again]:
     * Try again) and add up those that answered. A server that isn't linked isn't asked.
     */
    suspend fun load(servers: List<StatsServer>, range: StatsRange, again: Boolean = false): AllStatsAnswer = coroutineScope {
        val got = linked(servers).map { sv ->
            async {
                val kept = if (again || !StatsSource.fresh(sv.hub, range)) null else StatsSource.cached(sv.hub, range)
                sv.id to (kept?.let { Result.success(it) } ?: StatsSource.load(sv.hub, range))
            }
        }.awaitAll().toMap()
        answer(servers, got)
    }
}
