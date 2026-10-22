package com.prudnikweb.hypernova.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The demo's statistics: eight weeks of believable work on the demo's projects, the same every
 * time for a given day (each day is seeded by its date), with today still filling in.
 */
object DemoStats {
    private const val HOUR = 3_600_000L
    private const val HISTORY_DAYS = 52L

    private class Day(
        val date: LocalDate, val claudeMs: Long, val wallMs: Long, val agentMs: Long, val tokens: StatTokens,
        val sessions: Int, val prompts: Int, val agents: Int, val toolCalls: Int, val files: Int,
        val projects: Map<String, Long>,
    )

    /** Shares of the work, roughly: the demo's projects, Hypernova first. */
    private val PROJECTS = listOf(
        "hypernova" to 0.5f, "0stems" to 0.18f, "Hyperscribe" to 0.15f, "prudnikov-site" to 0.1f, "Hypernavigate" to 0.07f,
    )
    private val PATHS = mapOf(
        "hypernova" to "~/Programming/hypernova", "0stems" to "~/Programming/0stems",
        "Hyperscribe" to "~/Documents/programming/even_realities/g2-local-stt",
        "prudnikov-site" to "~/Documents/programming/prudnikov-site", "Hypernavigate" to "~/Documents/programming/even_realities/Hypernavigate",
    )
    private val TOOLS = listOf("Bash" to 0.45f, "Read" to 0.2f, "Edit" to 0.14f, "Write" to 0.06f, "Grep" to 0.05f, "WebFetch" to 0.03f, "Agent" to 0.03f, "MCP" to 0.02f)

    private fun day(date: LocalDate, today: LocalDate, now: Long, zone: ZoneId): Day {
        val rnd = Random(date.toEpochDay() * 7919L + 17L)
        val weekend = when (date.dayOfWeek) { DayOfWeek.SATURDAY -> 0.55f; DayOfWeek.SUNDAY -> 0.35f; else -> 1f }
        val off = rnd.nextFloat() < 0.1f
        var hours = if (off) 0f else weekend * (1.6f + 7.4f * rnd.nextFloat().pow(0.8f))
        if (date == today) {
            // Today is still filling in: as much as the day so far would hold.
            val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val sofar = ((now - start).toFloat() / (24 * HOUR)).coerceIn(0f, 1f)
            hours *= 0.15f + 0.75f * sofar
        }
        val claude = (hours * HOUR).roundToLong()
        val tokensPerHour = 60_000_000f + 60_000_000f * rnd.nextFloat()
        val total = (hours * tokensPerHour).roundToLong()
        val out = (total * (0.0022f + 0.0012f * rnd.nextFloat())).roundToLong()
        val input = (total * 0.00001f).roundToLong()
        val write = (total * 0.022f).roundToLong()
        val projects = HashMap<String, Long>()
        var left = claude
        for ((i, p) in PROJECTS.withIndex()) {
            val share = if (i == PROJECTS.lastIndex) left else (claude * p.second * (0.6f + 0.8f * rnd.nextFloat())).roundToLong().coerceAtMost(left)
            if (share > 0) projects[p.first] = share
            left -= share
        }
        return Day(
            date = date, claudeMs = claude,
            wallMs = (claude * (0.7f + 0.2f * rnd.nextFloat())).roundToLong(),
            agentMs = (claude * (0.6f + 1.6f * rnd.nextFloat())).roundToLong(),
            tokens = StatTokens(input, out, total - out - input - write, write),
            sessions = if (hours <= 0f) 0 else (hours * 0.8f + rnd.nextFloat() * 2f).roundToInt().coerceAtLeast(1),
            prompts = if (hours <= 0f) 0 else (hours * 4.2f + rnd.nextFloat() * 6f).roundToInt(),
            agents = (hours * (3f + 10f * rnd.nextFloat())).roundToInt(),
            toolCalls = (hours * (280f + 380f * rnd.nextFloat())).roundToInt(),
            files = (hours * (6f + 12f * rnd.nextFloat())).roundToInt(),
            projects = projects,
        )
    }

    fun make(range: StatsRange, now: Long): Stats {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = today.minusDays(HISTORY_DAYS - 1)
        val all = (0 until HISTORY_DAYS).map { day(first.plusDays(it), today, now, zone) }
        val from = when (range) {
            StatsRange.All -> first
            else -> today.minusDays(range.days - 1L)
        }
        val inRange = all.filter { it.date >= from }
        val claude = inRange.sumOf { it.claudeMs }
        val prompts = inRange.sumOf { it.prompts }
        val toolCalls = inRange.sumOf { it.toolCalls }
        val tokens = StatTokens(inRange.sumOf { it.tokens.input }, inRange.sumOf { it.tokens.output }, inRange.sumOf { it.tokens.cacheRead }, inRange.sumOf { it.tokens.cacheWrite })
        val busiest = inRange.filter { it.claudeMs > 0 }.maxByOrNull { it.claudeMs }
        val projects = HashMap<String, Long>()
        for (d in inRange) for ((p, ms) in d.projects) projects[p] = (projects[p] ?: 0L) + ms
        val sessions = inRange.sumOf { it.sessions }
        var streak = 0
        val worked = { d: LocalDate -> all.firstOrNull { it.date == d }?.let { it.claudeMs >= 60_000L } == true }
        var cursor = if (worked(today)) today else today.minusDays(1)
        while (cursor >= first && worked(cursor)) { streak++; cursor = cursor.minusDays(1) }
        val approvals = (prompts * 0.6f).roundToInt()
        val longest = busiest?.let { b ->
            val ms = minOf(6 * HOUR + 12 * 60_000L, (b.claudeMs * 0.82f).roundToLong())
            StatSession("demo-longest", "Agents in sessions", "hypernova", ms, b.date.atTime(9, 40).atZone(zone).toInstant().toEpochMilli())
        }
        val dayStat = { d: Day -> StatDay(d.date, d.claudeMs, d.wallMs, d.tokens.total, d.sessions, d.prompts) }
        return Stats(
            range = range,
            from = from.atStartOfDay(zone).toInstant().toEpochMilli(),
            to = now,
            since = first.atStartOfDay(zone).toInstant().toEpochMilli(),
            countingSince = first.atStartOfDay(zone).toInstant().toEpochMilli(),
            claudeMs = claude,
            wallMs = inRange.sumOf { it.wallMs },
            agentMs = inRange.sumOf { it.agentMs },
            tokens = tokens,
            sessions = sessions,
            prompts = prompts,
            fromPhone = (prompts * 0.24f).roundToInt(),
            fromGlasses = (prompts * 0.06f).roundToInt(),
            byVoice = (prompts * 0.09f).roundToInt(),
            toolCalls = toolCalls,
            tools = TOOLS.map { (n, f) -> StatTool(n, (toolCalls * f).roundToInt()) } + StatTool("Other", (toolCalls * 0.02f).roundToInt()),
            filesEdited = (inRange.sumOf { it.files } * 0.55f).roundToInt(),
            agents = inRange.sumOf { it.agents },
            approvals = approvals,
            approvalsPhone = (approvals * 0.75f).roundToInt(),
            approvalsGlasses = (approvals * 0.08f).roundToInt(),
            approvalsMac = approvals - (approvals * 0.75f).roundToInt() - (approvals * 0.08f).roundToInt(),
            limitsFiveHour = inRange.count { it.claudeMs > 8 * HOUR },
            limitsWeek = if (range == StatsRange.Month || range == StatsRange.All) 1 else 0,
            limitsModel = if (range == StatsRange.All) 2 else 0,
            busiestDay = busiest?.let(dayStat),
            longestSession = longest,
            streak = streak,
            projects = projects.entries.sortedByDescending { it.value }.map { (name, ms) ->
                val share = if (claude > 0) ms.toFloat() / claude else 0f
                StatProject(name, PATHS[name], ms, (tokens.total * share).roundToLong(), (sessions * share).roundToInt().coerceAtLeast(1))
            },
            days = all.takeLast(30).map(dayStat),
        )
    }
}
