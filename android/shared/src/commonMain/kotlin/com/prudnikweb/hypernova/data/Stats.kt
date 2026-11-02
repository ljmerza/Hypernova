package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.LocalDate

/*
 * Statistics: how much the agents worked on the Mac (docs/PROTOCOL.md, "Statistics"). The Mac adds
 * Claude Code's part up from its transcripts and counts Codex's turns as it follows them; every
 * total covers both, [Stats.claude] and [Stats.codex] split them. The Statistics screen (a tap on
 * the sun) draws it.
 */

/**
 * The ranges the Statistics screen offers; [wire] is what `GET /v1/stats?range=` takes, and [days]
 * how many of the chart's last 30 days it can light (YTD fewer in January: [litDays]).
 */
enum class StatsRange(val wire: String, val days: Int) {
    Today("today", 1), Week("week", 7), Month("month", 30), Ytd("ytd", 30), All("all", 30),
}

/** A server too old to know the range asked for (`400 bad_range`): it needs updating, nothing has failed. */
val ApiException.refusedRange: Boolean get() = status == 400 && code == "bad_range"

@Immutable
data class StatTokens(
    val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long,
) {
    val total: Long get() = input + output + cacheRead + cacheWrite
}

/**
 * One day of the last 30 ([day] in the Mac's calendar). [claudeMs] is both agents' busy time (the
 * name is from before Codex), [codexMs] Codex's share of it (0 from an older Mac, or without Codex).
 */
@Immutable
data class StatDay(val day: LocalDate, val claudeMs: Long, val wallMs: Long, val tokens: Long, val sessions: Int, val prompts: Int, val codexMs: Long = 0L)

/** One agent's part of a range (`byAgent.claude`, `byAgent.codex`): its busy time, tokens, sessions, prompts and tool calls. */
@Immutable
data class StatAgent(val ms: Long, val tokens: Long, val sessions: Int, val prompts: Int, val toolCalls: Int) {
    /** It did something in the range. */
    val worked: Boolean get() = ms > 0L || tokens > 0L || sessions > 0
}

/**
 * Whose work the Statistics screen speaks of: Claude's alone (as before Codex, word for word), both
 * agents' (a split under the hero), or Codex's alone (a Mac where Codex leads and Claude did nothing).
 */
enum class StatsAgents { Claude, Both, Codex }

@Immutable
data class StatProject(val name: String, val path: String?, val claudeMs: Long, val tokens: Long, val sessions: Int)

@Immutable
data class StatSession(val id: String, val title: String, val project: String, val claudeMs: Long, val startedAt: Long)

@Immutable
data class StatTool(val name: String, val count: Int)

@Immutable
data class Stats(
    val range: StatsRange,
    val from: Long,
    val to: Long,
    /** The first day with any work on the Mac, older days included (null: none yet). */
    val since: Long?,
    /** When the Mac started counting what only it sees (prompts from the phone, approvals). */
    val countingSince: Long,
    /** Each session's busy time, added up. */
    val claudeMs: Long,
    /** The time any session was busy (overlaps once). */
    val wallMs: Long,
    /** Each agent's own busy time, added up. */
    val agentMs: Long,
    val tokens: StatTokens,
    val sessions: Int,
    val prompts: Int,
    val fromPhone: Int,
    val byVoice: Int,
    val toolCalls: Int,
    val tools: List<StatTool>,
    val filesEdited: Int,
    val agents: Int,
    val approvals: Int,
    val approvalsPhone: Int,
    val approvalsMac: Int,
    /** Usage limits reached that began in the range: the 5-hour window, the week, a model's own. */
    val limitsFiveHour: Int,
    val limitsWeek: Int,
    val limitsModel: Int,
    val busiestDay: StatDay?,
    val longestSession: StatSession?,
    val streak: Int,
    val projects: List<StatProject>,
    /** The last 30 days, oldest first, whatever the range. */
    val days: List<StatDay>,
    /** The range's work per agent (`byAgent`): null from an older Mac, where all of it is Claude's. */
    val claude: StatAgent? = null,
    /** Codex's part: null where Codex isn't installed (and from an older Mac). */
    val codex: StatAgent? = null,
    /**
     * With older days from Claude Code's own summary in this answer: when the numbers it hasn't got
     * start (hours, prompts, tokens, projects and the rest: the first day the Mac counted). Null
     * without any. [sessions] and [toolCalls] include them; [tokens] is the Mac's own.
     */
    val hoursSince: Long? = null,
) {
    /**
     * Whose work this is. Codex with work in the range: both agents' (the hero splits them), or
     * Codex's alone where Codex leads ([codexLeads]: no Claude Code on the Mac) and Claude did
     * nothing. A Mac where Codex leads with no work yet is Codex's too. Otherwise Claude's, as ever.
     */
    fun agents(codexLeads: Boolean): StatsAgents {
        val codexWorked = codex?.worked == true
        val claudeWorked = claude?.worked ?: (claudeMs > 0L || tokens.total > 0L || sessions > 0)
        return when {
            codexWorked && (claudeWorked || !codexLeads) -> StatsAgents.Both
            codexWorked || (codexLeads && !claudeWorked) -> StatsAgents.Codex
            else -> StatsAgents.Claude
        }
    }

    /** Claude's busy time in the range: its own part, or (from an older Mac) all of it. */
    val claudeOwnMs: Long get() = claude?.ms ?: (claudeMs - (codex?.ms ?: 0L)).coerceAtLeast(0L)

    /** Codex's busy time in the range (0 without Codex). */
    val codexOwnMs: Long get() = codex?.ms ?: 0L
}

object StatsWire {
    fun parse(o: JSONObject): Stats {
        val t = o.obj("tokens")
        val from = o.obj("promptsFrom")
        val ap = o.obj("approvals")
        val lim = o.obj("limitsHit")
        val days = buildList {
            val a = o.arr("days") ?: return@buildList
            for (i in 0 until a.length()) {
                val d = a.optJSONObject(i) ?: continue
                add(day(d) ?: continue)
            }
        }
        val busy = o.obj("busiestDay")
        val by = o.obj("byAgent")
        // Older days from Claude Code's own summary, added to what it has numbers for (a server without them: none).
        // Sessions and tool calls only: an older server's `tokens` here run 2.2 times Hypernova's, so they're ignored.
        val back = o.obj("backfill")
        val backSessions = back?.int("sessions") ?: 0
        val backTools = back?.int("toolCalls") ?: 0
        return Stats(
            range = StatsRange.entries.firstOrNull { it.wire == o.str("range") } ?: StatsRange.Week,
            from = o.long("from") ?: 0L,
            to = o.long("to") ?: System.currentTimeMillis(),
            since = listOfNotNull(o.long("since"), back?.long("since")).minOrNull(),
            countingSince = o.long("countingSince") ?: System.currentTimeMillis(),
            claudeMs = o.long("claudeMs") ?: 0L,
            wallMs = o.long("wallMs") ?: 0L,
            agentMs = o.long("agentMs") ?: 0L,
            tokens = StatTokens(t?.long("input") ?: 0L, t?.long("output") ?: 0L, t?.long("cacheRead") ?: 0L, t?.long("cacheWrite") ?: 0L),
            sessions = (o.int("sessions") ?: 0) + backSessions,
            prompts = o.int("prompts") ?: 0,
            fromPhone = from?.int("phone") ?: 0,
            byVoice = from?.int("voice") ?: 0,
            toolCalls = (o.int("toolCalls") ?: 0) + backTools,
            tools = buildList {
                val a = o.arr("tools") ?: return@buildList
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    add(StatTool(x.str("name") ?: continue, x.int("count") ?: 0))
                }
            },
            filesEdited = o.int("filesEdited") ?: 0,
            agents = o.int("agents") ?: 0,
            approvals = ap?.int("total") ?: 0,
            approvalsPhone = ap?.int("phone") ?: 0,
            approvalsMac = ap?.int("mac") ?: 0,
            limitsFiveHour = lim?.int("fiveHour") ?: 0,
            limitsWeek = lim?.int("sevenDay") ?: 0,
            limitsModel = lim?.int("model") ?: 0,
            busiestDay = busy?.let { b ->
                val d = b.str("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@let null
                StatDay(d, b.long("claudeMs") ?: 0L, 0L, b.long("tokens") ?: 0L, 0, 0)
            },
            longestSession = o.obj("longestSession")?.let { s ->
                StatSession(s.str("id") ?: "", s.str("title") ?: "", s.str("project") ?: "", s.long("claudeMs") ?: 0L, s.long("startedAt") ?: 0L)
            },
            streak = o.int("streak") ?: 0,
            projects = buildList {
                val a = o.arr("projects") ?: return@buildList
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    add(StatProject(p.str("name") ?: continue, p.str("path"), p.long("claudeMs") ?: 0L, p.long("tokens") ?: 0L, p.int("sessions") ?: 0))
                }
            },
            days = days,
            // The older days are Claude's: its summary is Claude Code's own.
            claude = by?.obj("claude")?.let(::agent)?.let { it.copy(sessions = it.sessions + backSessions, toolCalls = it.toolCalls + backTools) },
            codex = by?.obj("codex")?.let(::agent),
            hoursSince = back?.long("hoursSince"),
        )
    }

    private fun day(d: JSONObject): StatDay? {
        val date = d.str("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return StatDay(date, d.long("claudeMs") ?: 0L, d.long("wallMs") ?: 0L, d.long("tokens") ?: 0L, d.int("sessions") ?: 0, d.int("prompts") ?: 0, codexMs = d.long("codexMs") ?: 0L)
    }

    /** One agent's part of the range (`byAgent.claude`, `byAgent.codex`); missing numbers are 0. */
    private fun agent(a: JSONObject) = StatAgent(a.long("ms") ?: 0L, a.long("tokens") ?: 0L, a.int("sessions") ?: 0, a.int("prompts") ?: 0, a.int("toolCalls") ?: 0)
}

/**
 * Where the Statistics screen gets its numbers: the Mac (a short cache per range, so opening it
 * again or switching ranges shows the last answer at once while a fresh one comes).
 */
object StatsSource {
    private class Entry(val stats: Stats, val at: Long)
    private val cache = HashMap<String, Entry>()

    /** A Mac answers within the daemon's own 15 s cache; older than this, ask again. */
    private const val FRESH_MS = 30_000L

    // A hub with no Mac to ask (a screen test's) is its own entry, so several of them (All servers) keep theirs apart.
    private fun key(hub: Hub, range: StatsRange): String = "${(hub as? PairedHub)?.pairing?.baseUrl ?: "hub@${System.identityHashCode(hub)}"}|${range.wire}"

    /** The last answer for [range], if any (shown at once while a fresh one loads). */
    fun cached(hub: Hub, range: StatsRange): Stats? = cache[key(hub, range)]?.stats

    fun fresh(hub: Hub, range: StatsRange, now: Long = System.currentTimeMillis()): Boolean =
        cache[key(hub, range)]?.let { now - it.at < FRESH_MS } == true

    /** A screen test's answer for [range] of [hub] (which has no Mac to ask): kept as one just received. */
    fun put(hub: Hub, range: StatsRange, stats: Stats) {
        cache[key(hub, range)] = Entry(stats, System.currentTimeMillis())
    }

    /** A screen test's refusal: asking [hub] (which has no Mac to ask) for [range] fails with [e]. */
    fun refuse(hub: Hub, range: StatsRange, e: ApiException) {
        refusals[key(hub, range)] = e
    }
    private val refusals = HashMap<String, ApiException>()

    /** Ask the Mac. A failure is an [ApiException] to explain. */
    suspend fun load(hub: Hub, range: StatsRange): Result<Stats> {
        val k = key(hub, range)
        return try {
            val s = when {
                hub is PairedHub -> StatsWire.parse(Api(hub.pairing.baseUrl, hub.pairing.token).get("/v1/stats?range=${range.wire}"))
                else -> throw refusals[k] ?: ApiException(Problem.Server, 0, "no_stats")
            }
            cache[k] = Entry(s, System.currentTimeMillis())
            Result.success(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(Net.classify(e))
        }
    }
}
