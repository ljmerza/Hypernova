package com.prudnikweb.hypernova.ui.home

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.prudnikweb.hypernova.data.KeyValue
import com.prudnikweb.hypernova.data.AndroidPrefs
import com.prudnikweb.hypernova.data.PrefsKeyValue
import com.prudnikweb.hypernova.data.Session

/** The agent Home's filter keeps: one of the two, or both (null in [HomeFilter.agent]). */
internal enum class FilterAgent { Claude, Codex }

/**
 * Home's filter (owner, 2026-11-01; docs/DECISIONS.md, "Home filters"): which of Home's sessions the
 * Sessions tab lists, chosen in the filter sheet (HomeFilterSheet.kt) and kept on this phone
 * ([HomeFilterPref]), not per server. Pure: HomeScreen.kt applies it after the sky's [SkyFilter]
 * and before the search, so a search looks only among what the filter shows.
 *
 * - [groups]: which of Home's groups show, by the rule Home groups them ([homeGroup]: errored,
 *   starting and the rest go where Home puts them). Empty is every group, as all three are.
 * - [agent]: Claude Code's or Codex's sessions; null both.
 * - [project]: one project's sessions (by name, as cards say it); null every project.
 * - [server]: one computer's sessions, by its id; only with "All servers" ([forServers]), else ignored.
 */
internal data class HomeFilter(
    val groups: Set<HomeGroup> = emptySet(),
    val agent: FilterAgent? = null,
    val project: String? = null,
    val server: String? = null,
) {
    /** The groups shown: every one while none is picked. */
    val shownGroups: Set<HomeGroup> get() = groups.ifEmpty { ALL_GROUPS }

    /** Anything is narrowed. Ask it of [forServers]' result, so a server filter outside "All servers" doesn't count. */
    val on: Boolean get() = shownGroups != ALL_GROUPS || agent != null || project != null || server != null

    /** The "Active" preset is what the states are: Needs you and Working, nothing else. */
    val active: Boolean get() = shownGroups == ACTIVE

    /**
     * The filter as it applies here: [servers] are the computers Home shows with "All servers" (their
     * ids), null with one computer. A server filter applies only with "All servers" and while that
     * computer is still paired; otherwise it waits (kept, not applied), so choosing All again brings
     * it back. All three groups picked is no group filter.
     */
    fun forServers(servers: Collection<String>?): HomeFilter = copy(
        groups = if (groups == ALL_GROUPS) emptySet() else groups,
        server = server?.takeIf { servers != null && it in servers },
    )

    /** Whether [s] is shown by its group, agent and project (the server is [parts]'). */
    fun keeps(s: Session): Boolean =
        homeGroup(s) in shownGroups &&
            (agent == null || (agent == FilterAgent.Codex) == s.codex) &&
            (project == null || s.project == project)

    /** The sessions it shows, in their order. */
    fun apply(sessions: List<Session>): List<Session> = if (groups.isEmpty() && agent == null && project == null) sessions else sessions.filter(::keeps)

    /** The computers it shows with "All servers": the one chosen, or every one. */
    fun <H> parts(parts: List<ServerPart<H>>): List<ServerPart<H>> = if (server == null) parts else parts.filter { it.id == server }

    /**
     * A state chip tapped: on, it shows that group too; off, it doesn't (the last one shown stays,
     * so the filter never hides every state: a tap on it does nothing).
     */
    fun toggle(g: HomeGroup): HomeFilter {
        val shown = shownGroups
        val next = if (g in shown) shown - g else shown + g
        if (next.isEmpty()) return this
        return copy(groups = if (next == ALL_GROUPS) emptySet() else next)
    }

    /** "Active: Needs you + Working", in one tap; tapped again, every state again. The rest stays as it is. */
    fun toggleActive(): HomeFilter = copy(groups = if (active) emptySet() else ACTIVE)

    /**
     * What the chip says it shows, piece by piece, in the sheet's order: the states joined ("Needs you
     * + Working", only when they're narrowed), the agent, the project, the computer ([serverName]: its
     * name, null when it's gone). Words come from the caller, so this stays pure.
     */
    fun pieces(group: (HomeGroup) -> String, agentName: (FilterAgent) -> String, serverName: (String) -> String?): List<String> = listOfNotNull(
        if (shownGroups != ALL_GROUPS) HomeGroup.entries.filter { it in shownGroups }.joinToString(" + ") { group(it) } else null,
        agent?.let(agentName),
        project,
        server?.let(serverName),
    )

    fun save(kv: KeyValue) {
        kv.put(GROUPS, groups.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name })
        kv.put(AGENT, agent?.name)
        kv.put(PROJECT, project)
        kv.put(SERVER, server)
    }

    companion object {
        val ALL_GROUPS: Set<HomeGroup> = HomeGroup.entries.toSet()
        val ACTIVE: Set<HomeGroup> = setOf(HomeGroup.NeedsInput, HomeGroup.Working)
        val NONE = HomeFilter()

        private const val GROUPS = "home_filter_groups"
        private const val AGENT = "home_filter_agent"
        private const val PROJECT = "home_filter_project"
        private const val SERVER = "home_filter_server"

        /** What was kept; a name this version doesn't know is left out (a group or agent from a later version). */
        fun load(kv: KeyValue): HomeFilter {
            val groups = kv.get(GROUPS).orEmpty().split(',').mapNotNull { n -> HomeGroup.entries.firstOrNull { it.name == n } }.toSet()
            return HomeFilter(
                groups = if (groups == ALL_GROUPS) emptySet() else groups,
                agent = kv.get(AGENT)?.let { n -> FilterAgent.entries.firstOrNull { it.name == n } },
                project = kv.get(PROJECT)?.takeIf { it.isNotBlank() },
                server = kv.get(SERVER)?.takeIf { it.isNotBlank() },
            )
        }

        /**
         * The sheet's projects: every project on Home's list (by name, A to Z, any case), and the one
         * chosen though none of its sessions is there now, so it can still be unchosen.
         */
        fun projects(sessions: List<Session>, chosen: String?): List<String> =
            (sessions.map { it.project }.filter { it.isNotBlank() } + listOfNotNull(chosen)).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}

/** Home's filter, kept on this phone. One state for the whole app (as CardSwipePref), so the sheet's change reaches Home at once. */
internal object HomeFilterPref {
    private const val PREFS = "hypernova_ui"
    private var current: MutableState<HomeFilter>? = null

    fun state(context: Context): State<HomeFilter> = holder(context)

    fun set(context: Context, value: HomeFilter) {
        value.save(store(context))
        holder(context).value = value
    }

    private fun store(context: Context): KeyValue = PrefsKeyValue(AndroidPrefs(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))

    private fun holder(context: Context): MutableState<HomeFilter> = current
        ?: mutableStateOf(HomeFilter.load(store(context))).also { current = it }
}
