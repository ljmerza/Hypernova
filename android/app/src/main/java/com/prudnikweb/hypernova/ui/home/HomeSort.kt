package com.prudnikweb.hypernova.ui.home

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.prudnikweb.hypernova.data.KeyValue
import com.prudnikweb.hypernova.data.PrefsKeyValue
import com.prudnikweb.hypernova.data.Session

/**
 * How Home's sessions are ordered within each of its groups (owner, 2026-11-01; docs/DECISIONS.md,
 * "Sorting Home's sessions"), chosen from the sort button beside the filter (HomeSortSheet.kt) and kept
 * on this phone ([HomeSortPref]). The groups keep their order (Needs input, Working, Completed); this
 * is the order inside each. Every order ends with the name, any case, then the id, so equal keys
 * never swap.
 */
internal enum class HomeSort {
    /** A to Z by name: the default (the owner's words), in every group, Completed too. */
    Name,
    /** The latest activity first (its `updatedAt`, the time a card says), then the name. */
    Recent,
    /** A to Z by project, then the name. */
    Project,
    /** By computer, A to Z, then the name: only with "All servers" ([forServers]). */
    Server;

    /** The sort as it applies: [Server] only with "All servers" ([all]); otherwise [Name]. */
    fun forServers(all: Boolean): HomeSort = if (this == Server && !all) Name else this

    /** Not the default: the sort button is lit, with its dot. */
    val changed: Boolean get() = this != Name

    /**
     * The order of [T] (a session, or a card of "All servers"): [session] gives its session, [server]
     * its computer's name (null with one computer). A computer's name also tells apart the same name
     * and id on two computers.
     */
    fun <T> comparator(session: (T) -> Session, server: (T) -> String? = { null }): Comparator<T> {
        val byName = compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { session(it).name }
            .thenBy { session(it).id }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { server(it).orEmpty() }
        return when (this) {
            Name -> byName
            Recent -> compareByDescending<T> { session(it).updatedAt }.then(byName)
            Project -> compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { session(it).project }.then(byName)
            Server -> compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { server(it).orEmpty() }.then(byName)
        }
    }

    companion object {
        val DEFAULT = Name
        private const val KEY = "home_sort"

        /** What was kept; nothing, or a name this version doesn't know, is [Name]. */
        fun load(kv: KeyValue): HomeSort = entries.firstOrNull { it.name == kv.get(KEY) } ?: DEFAULT

        fun save(kv: KeyValue, sort: HomeSort) = kv.put(KEY, sort.takeIf { it != DEFAULT }?.name)

        /** The choices the sort sheet offers: Server only with "All servers". */
        fun offered(all: Boolean): List<HomeSort> = if (all) entries else entries - Server
    }
}

/** Home's sort, kept on this phone. One state for the whole app (as HomeFilterPref). */
internal object HomeSortPref {
    private const val PREFS = "hypernova_ui"
    private var current: MutableState<HomeSort>? = null

    fun state(context: Context): State<HomeSort> = holder(context)

    fun set(context: Context, value: HomeSort) {
        HomeSort.save(store(context), value)
        holder(context).value = value
    }

    private fun store(context: Context): KeyValue = PrefsKeyValue(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private fun holder(context: Context): MutableState<HomeSort> = current
        ?: mutableStateOf(HomeSort.load(store(context))).also { current = it }
}
