package com.prudnikweb.hypernova.data

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A prompt typed while the Mac was away, waiting on the phone to be sent (docs/DESIGN.md, "The
 * offline queue"). [id] is its idempotency key: it goes to the Mac as the send's `clientId`, so a
 * send repeated after a lost answer is one send there (docs/PROTOCOL.md, "Sending a prompt").
 * [files] were uploaded before the Mac went away. [cwd] and [mode] are what a new session needs
 * when the session it was meant for is gone ([ended]).
 */
data class Waiting(
    val id: String,
    val sessionId: String,
    val text: String,
    val files: List<Attachment>,
    val at: Long,
    val cwd: String? = null,
    val mode: String? = null,
    /** The Mac has no such session any more: the prompt stays, to start a new session with. */
    val ended: Boolean = false,
)

/** Where the queue is kept between runs of the app. */
interface QueueStore {
    fun read(): String?
    fun write(json: String)
}

class PrefsQueueStore(private val prefs: SharedPreferences, private val key: String = "offlineQueue") : QueueStore {
    override fun read(): String? = prefs.getString(key, null)
    override fun write(json: String) { prefs.edit().putString(key, json).apply() }
}

/**
 * The prompts waiting for the Mac, per session, in the order they were typed. Kept in
 * [store] on every change, so they survive the app closing and the phone restarting; they hold
 * nothing but the prompt's text and the paths of files already on the Mac. [version] is state, so
 * screens and the transcript's cache follow every change.
 *
 * A prompt being sent right now ([flying]) is hidden from the screens and can't be edited or
 * removed; it goes once the Mac has it ([sent]) or comes back if the send didn't get through ([failed]).
 */
class OfflineQueue(private val store: QueueStore, private val clock: () -> Long = System::currentTimeMillis) {
    private var items: List<Waiting> = parse(store.read())
    private val flying = HashSet<String>()
    var version by mutableIntStateOf(0)
        private set

    /** Everything waiting, oldest first, including what is being sent. */
    val all: List<Waiting> get() = items

    /** What a session's screen shows: waiting, not on its way. */
    fun of(sessionId: String): List<Waiting> {
        version // read: a change recomposes the reader
        return items.filter { it.sessionId == sessionId && it.id !in flying }
    }

    /** Prompts that are waiting and have a session still (not [Waiting.ended]), for a session's card. */
    fun count(sessionId: String): Int {
        version
        return items.count { it.sessionId == sessionId && !it.ended }
    }

    /** Prompts whose session is gone: Home offers them a new session. */
    fun orphans(): List<Waiting> {
        version
        return items.filter { it.ended && it.id !in flying }
    }

    fun get(id: String): Waiting? = items.firstOrNull { it.id == id }

    /** Puts a prompt at the end of the queue. [id] is a send's own key when it already has one. */
    fun add(sessionId: String, text: String, files: List<Attachment>, cwd: String? = null, mode: String? = null, id: String = UUID.randomUUID().toString()): Waiting {
        val w = Waiting(id, sessionId, text.trim(), files, clock(), cwd, mode)
        change(items + w)
        return w
    }

    /** New words for a waiting prompt. False when it isn't there, is being sent, or would be empty. */
    fun edit(id: String, text: String): Boolean {
        val w = get(id) ?: return false
        if (id in flying || (text.isBlank() && w.files.isEmpty())) return false
        change(items.map { if (it.id == id) it.copy(text = text.trim()) else it })
        return true
    }

    /** Drops a waiting prompt. False when it isn't there or is being sent. */
    fun remove(id: String): Boolean {
        if (id in flying || get(id) == null) return false
        change(items.filter { it.id != id })
        return true
    }

    /** Its files are gone from the Mac: the words stay. */
    fun dropFiles(id: String) = change(items.map { if (it.id == id) it.copy(files = emptyList()) else it })

    /** The Mac has no such session. */
    fun markEnded(id: String) = change(items.map { if (it.id == id) it.copy(ended = true) else it })

    /**
     * What to send next, in the order typed: a session's prompts go one after the other, so none
     * overtakes another, and a session whose earlier prompt is on its way waits for it.
     */
    fun due(): List<Waiting> {
        val busy = items.filter { it.id in flying }.mapTo(HashSet()) { it.sessionId }
        return items.filter { !it.ended && it.id !in flying && it.sessionId !in busy }
            .distinctBy { it.sessionId }
            .sortedBy { it.at }
    }

    /** Marks [id] as being sent; false when it already is, or isn't there. */
    fun sending(id: String): Boolean {
        if (get(id) == null || !flying.add(id)) return false
        version++
        return true
    }

    /** The Mac has it: it leaves the queue. */
    fun sent(id: String) {
        flying.remove(id)
        change(items.filter { it.id != id })
    }

    /** The send didn't get through: the prompt is waiting again, where it was. */
    fun failed(id: String) {
        flying.remove(id)
        version++
    }

    private fun change(next: List<Waiting>) {
        items = next
        version++
        store.write(json(next))
    }

    companion object {
        fun json(list: List<Waiting>): String = JSONArray().also { a ->
            for (w in list) a.put(
                JSONObject().put("id", w.id).put("session", w.sessionId).put("text", w.text).put("at", w.at)
                    .put("files", JSONArray().also { f ->
                        for (x in w.files) f.put(JSONObject().put("path", x.path).put("name", x.name).put("mime", x.mime ?: JSONObject.NULL).put("size", x.size))
                    })
                    .also { o ->
                        w.cwd?.let { o.put("cwd", it) }
                        w.mode?.let { o.put("mode", it) }
                        if (w.ended) o.put("ended", true)
                    },
            )
        }.toString()

        /** A damaged store reads as empty rather than crashing the app. */
        fun parse(json: String?): List<Waiting> = runCatching {
            val a = JSONArray(json ?: return emptyList())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val session = o.optString("session").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val files = o.optJSONArray("files")?.let { f ->
                    (0 until f.length()).mapNotNull { j ->
                        val x = f.optJSONObject(j) ?: return@mapNotNull null
                        val path = x.optString("path").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        Attachment(path, x.optString("name", path.substringAfterLast('/')), if (x.isNull("mime")) null else x.optString("mime"), x.optLong("size"))
                    }
                }.orEmpty()
                Waiting(id, session, o.optString("text"), files, o.optLong("at"), o.optString("cwd").takeIf { it.isNotEmpty() }, o.optString("mode").takeIf { it.isNotEmpty() }, o.optBoolean("ended"))
            }.sortedBy { it.at }
        }.getOrDefault(emptyList())
    }
}

/** A waiting prompt as the chat draws it: the user's own bubble, quiet, saying what it waits for. */
fun Waiting.item(): Item.User = Item.User(
    key = "waiting:$id",
    text = text,
    status = if (ended) SendStatus.SessionGone else SendStatus.WaitingMac,
    files = files,
    sentAt = at,
    waitingId = id,
)
