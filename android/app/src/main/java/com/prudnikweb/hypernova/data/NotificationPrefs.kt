package com.prudnikweb.hypernova.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * What the owner can turn off, a switch each (docs/DECISIONS.md, "Which notifications"). Every
 * [Notice.Kind] belongs to one; a new kind doesn't compile until it says which.
 */
enum class NotifyGroup(val key: String) {
    Answers("answers"),
    Finished("finished"),
    Waiting("waiting"),
    Errors("errors"),
    Limits("limits"),
    Automations("automations"),
    Updates("updates"),
    ;

    companion object {
        fun of(kind: Notice.Kind): NotifyGroup = when (kind) {
            Notice.Kind.Approval, Notice.Kind.Question, Notice.Kind.Plan -> Answers
            Notice.Kind.Finished -> Finished
            Notice.Kind.Waiting -> Waiting
            Notice.Kind.Error -> Errors
            // What stops the sessions from outside: a limit, the account, a Mac about to sleep.
            Notice.Kind.Limit, Notice.Kind.Account, Notice.Kind.Power, Notice.Kind.Backpack -> Limits
            Notice.Kind.Automation -> Automations
            Notice.Kind.Update, Notice.Kind.ServerUpdateFailed -> Updates
        }
    }
}

/** A server's own row: follow the defaults, its own switches, or nothing from it. */
enum class ServerNotify(val key: String) { Same("same"), Custom("custom"), Off("off") }

/**
 * A server's override. [custom]: the groups its own switches have off, kept while it's on Same as
 * all or Off, so Custom comes back as it was; null until Custom was first chosen.
 */
@Immutable
data class ServerOverride(val mode: ServerNotify = ServerNotify.Same, val custom: Set<NotifyGroup>? = null)

/**
 * Which notifications this phone posts, kept on the phone and not on the servers. Pure: nothing here
 * touches Android ([NotificationPrefsStore] keeps it). Only alerts follow it: cards, banners in the
 * open app and the widget show everything whatever it says.
 *
 * [master] off is nothing from any server. Otherwise the server's override wins, then the defaults
 * ([off], the groups turned off). Everything is on until the owner says otherwise.
 */
@Immutable
data class NotificationPrefs(
    val master: Boolean = true,
    val off: Set<NotifyGroup> = emptySet(),
    val servers: Map<String, ServerOverride> = emptyMap(),
) {
    /** Whether [kind] from [server] may be a notification; null: no server of its own, so the defaults. */
    fun allows(server: String?, kind: Notice.Kind): Boolean = master && underMaster(server, NotifyGroup.of(kind))

    /** The same, leaving the master switch out. */
    private fun underMaster(server: String?, group: NotifyGroup): Boolean {
        val o = server?.let(servers::get)
        return when (o?.mode) {
            ServerNotify.Off -> false
            ServerNotify.Custom -> group !in o.custom.orEmpty()
            else -> group !in off
        }
    }

    fun modeOf(server: String): ServerNotify = servers[server]?.mode ?: ServerNotify.Same

    /** The groups [server]'s own switches have off. */
    fun customOf(server: String): Set<NotifyGroup> = servers[server]?.custom ?: off

    fun withDefault(group: NotifyGroup, on: Boolean) = copy(off = if (on) off - group else off + group)

    /** Custom starts from what the server had (its last Custom, else the defaults), so choosing it silences nothing new. */
    fun withMode(server: String, mode: ServerNotify): NotificationPrefs {
        val old = servers[server]
        val custom = old?.custom ?: off.takeIf { mode == ServerNotify.Custom }
        return if (mode == ServerNotify.Same && custom == null) without(server)
        else copy(servers = servers + (server to ServerOverride(mode, custom)))
    }

    fun withCustom(server: String, group: NotifyGroup, on: Boolean): NotificationPrefs {
        val now = customOf(server)
        return copy(servers = servers + (server to ServerOverride(ServerNotify.Custom, if (on) now - group else now + group)))
    }

    /** A forgotten server's override goes with it. */
    fun without(server: String) = copy(servers = servers - server)

    /**
     * Whether going to [next] would leave one of [paired] silent about approvals where it wasn't:
     * the confirm's test, whichever switch did it. A switch turned off under a master that's already
     * off counts too, since it would stay silent once the master is back.
     */
    fun silencesAnswers(next: NotificationPrefs, paired: List<String>): Boolean = paired.any { id ->
        val was = underMaster(id, NotifyGroup.Answers)
        was && (!next.underMaster(id, NotifyGroup.Answers) || (master && !next.master))
    }

    fun save(kv: KeyValue) {
        kv.put(MASTER, if (master) null else "off")
        kv.put(OFF, off.keys())
        kv.put(SERVERS, servers.entries.joinToString("\n") { (id, o) -> "$id:${o.mode.key}" + o.custom?.let { ":" + it.keys().orEmpty() }.orEmpty() }.ifEmpty { null })
    }

    private fun Set<NotifyGroup>.keys(): String? = joinToString(",") { it.key }.ifEmpty { null }

    companion object {
        private const val MASTER = "notifyMaster"
        private const val OFF = "notifyOff"
        private const val SERVERS = "notifyServers"

        fun load(kv: KeyValue) = NotificationPrefs(
            master = kv.get(MASTER) != "off",
            off = groups(kv.get(OFF)),
            servers = kv.get(SERVERS).orEmpty().lineSequence().mapNotNull(::server).toMap(),
        )

        private fun groups(s: String?): Set<NotifyGroup> =
            s.orEmpty().split(',').mapNotNull { k -> NotifyGroup.entries.firstOrNull { it.key == k } }.toSet()

        /** `<id>:<mode>`, then `:<groups off>` when a Custom set is kept (it may be empty). */
        private fun server(line: String): Pair<String, ServerOverride>? {
            val p = line.split(':')
            val id = p.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
            val mode = ServerNotify.entries.firstOrNull { it.key == p.getOrNull(1) } ?: return null
            return id to ServerOverride(mode, if (p.size > 2) groups(p[2]) else null)
        }
    }
}

/** [NotificationPrefs] on this phone, one state for the whole app: Settings follows it, each notification reads it. */
object NotificationPrefsStore {
    private var current: MutableState<NotificationPrefs>? = null

    fun state(context: Context): State<NotificationPrefs> = holder(context)

    fun get(context: Context): NotificationPrefs = holder(context).value

    fun set(context: Context, value: NotificationPrefs) {
        value.save(store(context))
        holder(context).value = value
    }

    private fun store(context: Context): KeyValue = PrefsKeyValue(context.applicationContext.getSharedPreferences("hypernova", Context.MODE_PRIVATE))

    private fun holder(context: Context): MutableState<NotificationPrefs> = current
        ?: mutableStateOf(NotificationPrefs.load(store(context))).also { current = it }
}
