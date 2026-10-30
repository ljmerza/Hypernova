package com.prudnikweb.hypernova.data

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/*
 * Several paired computers (docs/DECISIONS.md, "One phone can pair with several Macs, with a
 * switcher in the app"). Each keeps its own pairing (token, device id, pin), its own
 * device certificate and its own offline queue, under [Server.id]. One is active: the screens,
 * the widgets and the glasses show it, as they showed the one pairing before. The others are
 * watched in the background by a LiveHub of their own, only for their notifications and their
 * needs-you counts (data/Core.kt).
 *
 * Plain Kotlin and org.json, so it runs in unit tests.
 */

/** A paired computer: its pairing, kept under [id] (the key of everything that is its own on the phone). */
@Immutable
data class Server(val id: String, val pairing: Pairing)

/** What the switcher and the chip say about one computer. */
@Immutable
data class ServerStatus(val id: String, val name: String, val state: LinkState, val needsYou: Int, val active: Boolean)

/** Where the list of computers is kept: a few strings (the app's SharedPreferences, a map in tests). */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class PrefsKeyValue(private val prefs: SharedPreferences) : KeyValue {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** The one pairing the app kept before it knew several (data/Pairing.kt, [PairingStore]). */
interface LegacyPairing {
    fun load(): Pairing?
    fun save(p: Pairing)
    fun clear()
}

/**
 * The paired computers and the active one, kept between runs. The first run after the update finds
 * no list and makes one from the single pairing kept before ([LegacyPairing]): it becomes the
 * server [Servers.LEGACY_ID], whose own keys (offline queue, device certificate) are the ones it
 * always had, so nothing has to be paired again. That server's pairing is still written to the old
 * keys too, so an older app installed over this one still finds it.
 */
class ServerBook(private val kv: KeyValue, private val legacy: LegacyPairing) {
    /** The list and the active server's id (null: nothing is paired). */
    fun load(): Pair<List<Server>, String?> {
        val raw = kv.get(SERVERS)
        if (raw == null) {
            val p = legacy.load()
            val list = if (p != null) listOf(Server(Servers.LEGACY_ID, p)) else emptyList()
            val active = list.firstOrNull()?.id
            kv.put(SERVERS, Servers.encode(list))
            kv.put(ACTIVE, active)
            return list to active
        }
        val list = Servers.decode(raw)
        val active = kv.get(ACTIVE)?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
        return list to active
    }

    fun save(list: List<Server>, active: String?) {
        kv.put(SERVERS, Servers.encode(list))
        kv.put(ACTIVE, active?.takeIf { id -> list.any { it.id == id } })
        val old = list.firstOrNull { it.id == Servers.LEGACY_ID }
        if (old != null) legacy.save(old.pairing) else legacy.clear()
    }

    companion object {
        const val SERVERS = "servers"
        const val ACTIVE = "activeServer"
    }
}

object Servers {
    /** The computer this phone was paired with before it could pair with several. */
    const val LEGACY_ID = "0"

    /** A new server's id: short, letters and digits only (it ends up in file and key names). */
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    /**
     * A key (or a preferences file's name) that belongs to one server: the old key as it is for
     * [LEGACY_ID], so what the single pairing kept stays its own; `<base>.<id>` for the others.
     */
    fun scoped(base: String, id: String): String = if (id == LEGACY_ID) base else "$base.$id"

    /**
     * Whether [a] and [b] pair the same computer: the same Mac id when both know one, else the same
     * name ([replacesAnother]'s rule). Pairing
     * one again (it forgot this phone, say) takes its old place in the list.
     */
    fun sameComputer(a: Pairing, b: Pairing): Boolean {
        if (a.macId != null && b.macId != null) return a.macId == b.macId
        return a.macName.equals(b.macName, ignoreCase = true)
    }

    /**
     * The list with [p] in it: in the place of the same computer (its id kept), or added at the end
     * under [newId]. Returns the list and the id [p] is kept under.
     */
    fun withPairing(list: List<Server>, p: Pairing, newId: () -> String = ::newId): Pair<List<Server>, String> {
        val i = list.indexOfFirst { sameComputer(it.pairing, p) }
        if (i >= 0) {
            val id = list[i].id
            return list.toMutableList().also { it[i] = Server(id, p) } to id
        }
        val id = generateSequence { newId() }.first { n -> list.none { it.id == n } }
        return list + Server(id, p) to id
    }

    /** The active server after [removed] goes: the same one, or the first left (null: none left). */
    fun activeAfterRemoval(list: List<Server>, removed: String, active: String?): String? {
        val left = list.filter { it.id != removed }
        return active?.takeIf { it != removed && left.any { s -> s.id == it } } ?: left.firstOrNull()?.id
    }

    /** The order the switcher lists them in: the active one first. */
    fun activeFirst(list: List<Server>, active: String?): List<Server> =
        list.filter { it.id == active } + list.filter { it.id != active }

    /**
     * Which computer a notification's tap or answer goes to: the one it names ([server]) while it is
     * still paired (never another in its place), the active one for a notification that names none
     * (posted before there were several).
     */
    fun target(server: String?, active: String?, known: Collection<String>): String? =
        if (server == null) active else server.takeIf { it in known }

    /**
     * Which computer an Allow or Deny goes to: the one named ([target]); a widget names none, so the
     * one the approval waits on ([waiting]: each computer's pending approval ids), else the active one.
     */
    fun approvalTarget(server: String?, approvalId: String, active: String?, waiting: Map<String, Collection<String>>): String? {
        if (server != null) return target(server, active, waiting.keys)
        return waiting.entries.firstOrNull { approvalId in it.value }?.key ?: active
    }

    /** Sessions that need the owner now (the permanent notification's rule): alive and waiting on them. */
    fun needsYou(sessions: List<Session>): Int = sessions.count { it.phase != Phase.Ended && it.state == SessionState.NeedsYou }

    /**
     * What the chip's amber count says: the other servers' sessions that need the owner, counted
     * from those that are linked or only reconnecting (an offline one's list is too old to say).
     */
    fun elsewhere(statuses: List<ServerStatus>): Int =
        statuses.filter { !it.active && (it.state == LinkState.Connected || it.state == LinkState.Reconnecting) }.sumOf { it.needsYou }

    fun encode(list: List<Server>): String = JSONArray().apply { for (s in list) put(encode(s)) }.toString()

    private fun encode(s: Server): JSONObject {
        val p = s.pairing
        return JSONObject()
            .put("id", s.id)
            .put("baseUrl", p.baseUrl).put("token", p.token).put("macName", p.macName)
            .putOpt("macId", p.macId).putOpt("deviceId", p.deviceId)
            .putOpt("fingerprint", p.fingerprint)
            .put("lan", JSONArray(p.lanAddresses)).put("lanPort", p.lanPort)
    }

    /** The list as kept; an entry without its id, base URL or token is dropped, a list that isn't one is empty. */
    fun decode(raw: String): List<Server> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Server>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val base = o.optString("baseUrl").takeIf { it.isNotEmpty() } ?: continue
            val token = o.optString("token").takeIf { it.isNotEmpty() } ?: continue
            if (out.any { it.id == id }) continue
            val lan = o.optJSONArray("lan")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } } }.orEmpty()
            out += Server(
                id,
                Pairing(
                    baseUrl = base,
                    token = token,
                    macName = o.optString("macName").takeIf { it.isNotEmpty() } ?: "Mac",
                    macId = o.str("macId"),
                    deviceId = o.str("deviceId"),
                    fingerprint = o.str("fingerprint"),
                    lanAddresses = lan,
                    lanPort = o.optInt("lanPort", LanInfo.DEFAULT_PORT),
                ),
            )
        }
        return out
    }
}
