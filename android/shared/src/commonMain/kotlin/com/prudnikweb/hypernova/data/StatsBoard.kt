package com.prudnikweb.hypernova.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What Statistics has to show for the chosen range. */
sealed interface StatsShown {
    /** Nothing yet, and it's been asked. */
    data object Reading : StatsShown
    /** No answer for this range and it failed: [problem] says why, and it can be tried again. Another range's numbers never stand in. */
    data class Failed(val problem: ApiException?) : StatsShown
    /** Too old for this range ([names]): not an error, and asked again each minute, so the numbers come once it's updated. */
    data class NeedsUpdate(val names: List<String>) : StatsShown
    /** One server too old to have statistics at all. */
    data object TooOld : StatsShown
    /**
     * Numbers. [answered]: whose they are. [missing]: left out, not answering; [outdated]: left out, too old for this range.
     * [staleSince]: asking again failed, and this is when these are from.
     */
    data class Numbers(val stats: Stats, val answered: List<String>, val missing: List<String>, val outdated: List<String>, val staleSince: Long?) : StatsShown
}

/**
 * Statistics' state: the range chosen and what's known of each, for one server or added up from several ([servers], two or
 * more; AllStats does the adding). What's cached shows at once while a fresh answer comes. Each range's answer is its own.
 */
class StatsBoard(private val hub: Hub, servers: List<StatsServer>?) {
    private val many: List<StatsServer>? = servers?.takeIf { it.size > 1 }

    /** With All servers. */
    val all: Boolean get() = many != null

    private val answers = mutableStateMapOf<StatsRange, StatsShown.Numbers>()
    private val failures = mutableStateMapOf<StatsRange, Failure>()

    private class Failure(val problem: ApiException?, val outdated: List<String>)

    var range by mutableStateOf(StatsRange.Week)
        private set

    /** A question is on its way. */
    var asking by mutableStateOf(false)
        private set

    init {
        kept(range)
    }

    /** The range's last answer, if there is one, in place at once. */
    private fun kept(r: StatsRange) {
        if (r in answers) return
        val got = if (many != null) AllStats.cached(many, r)?.let(::numbers) else StatsSource.cached(hub, r)?.let(::one)
        got?.let { answers[r] = it }
    }

    fun pick(r: StatsRange) {
        kept(r)
        range = r
    }

    private fun one(s: Stats) = StatsShown.Numbers(s, listOf(hub.mac.name), emptyList(), emptyList(), null)

    private fun numbers(a: AllStatsAnswer): StatsShown.Numbers? =
        a.stats?.let { StatsShown.Numbers(it, a.each.map { (sv, _) -> sv.name }, a.missing, a.outdated, null) }

    /** Who speaks in this range's numbers: Claude alone, both, or Codex alone. */
    fun agents(s: Stats): StatsAgents = s.agents(many?.all { it.hub.codexLeads } ?: hub.codexLeads)

    /** Whose work is being read, before there are numbers to say. */
    val expected: StatsAgents
        get() = when {
            hub.codexLeads -> StatsAgents.Codex
            hub.bothAgents -> StatsAgents.Both
            else -> StatsAgents.Claude
        }

    /** The server named when it's one. */
    val serverName: String get() = hub.mac.name

    /** Links as they stand: any change is a reason to ask at once. */
    val links: List<Boolean> get() = (many?.map { it.hub } ?: listOf(hub)).map { Servers.fresh(it.link.state) }

    /** What the screen shows now. */
    val shown: StatsShown
        get() {
            val got = answers[range]
            val failed = failures[range]
            return when {
                got != null -> if (failed != null) got.copy(staleSince = got.stats.to) else got
                failed == null -> StatsShown.Reading
                failed.problem?.refusedRange == true -> StatsShown.NeedsUpdate(failed.outdated)
                many == null && failed.problem?.status == NOT_FOUND -> StatsShown.TooOld
                else -> StatsShown.Failed(failed.problem)
            }
        }

    /** Ask for the chosen range, [again] even if the last answer is fresh (Try again). Waits for the answer. */
    suspend fun ask(again: Boolean = false) {
        val r = range
        asking = true
        try {
            if (many != null) askAll(many, r, again) else askOne(r, again)
        } finally {
            asking = false
        }
    }

    private suspend fun askOne(r: StatsRange, again: Boolean) {
        val fresh = if (again) null else StatsSource.cached(hub, r)?.takeIf { StatsSource.fresh(hub, r) }
        if (fresh != null) {
            answers[r] = one(fresh)
            failures.remove(r)
            return
        }
        StatsSource.load(hub, r)
            .onSuccess {
                answers[r] = one(it)
                failures.remove(r)
            }
            .onFailure { failures[r] = Failure(it as? ApiException, listOf(hub.mac.name)) }
    }

    private suspend fun askAll(servers: List<StatsServer>, r: StatsRange, again: Boolean) {
        val got = AllStats.load(servers, r, again)
        val added = numbers(got)
        if (added != null) {
            answers[r] = added
            failures.remove(r)
        } else {
            failures[r] = Failure(got.problem, got.outdated)
        }
    }

    private companion object {
        const val NOT_FOUND = 404
    }
}
