package com.prudnikweb.hypernova.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.data.DebugScrub
import com.prudnikweb.hypernova.data.DeviceCertStore
import com.prudnikweb.hypernova.data.DeviceKeys
import com.prudnikweb.hypernova.data.Log
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.Server
import com.prudnikweb.hypernova.data.ServerBook
import com.prudnikweb.hypernova.data.Servers
import org.json.JSONArray
import org.json.JSONObject

/** The settings file the paired servers are in ([com.prudnikweb.hypernova.data.PairingStore]). */
const val TOKENS_PREFS = "hypernova"

/** Where the paired servers' tokens are now, as Settings says it. */
sealed interface TokenPlace {
    /** None paired, or none that can be had yet ([DesktopTokens.waiting] says which). */
    data object None : TokenPlace
    data object InKeychain : TokenPlace
    /** At least one is in the owner-only settings file. */
    data class InFile(val why: FileWhy) : TokenPlace
}

/** Why a token that's in the keychain can't be had. */
enum class WaitWhy {
    /** It didn't answer. The token is very likely still there. */
    Locked,
    /** It answered, with nothing under that name: reset, or this folder moved. Or a keychain that can't tell the two apart: it's never treated as lost. */
    Gone,
    /** Nothing on this computer to reach it with. */
    NoHelper,
    /** `HYPERNOVA_CLIENT_KEYCHAIN=off`: it isn't asked. */
    TurnedOff,
}

/** A paired server waiting for its token. Holds no secret. */
class WaitingServer(val id: String, val name: String, val why: WaitWhy)

/**
 * Each paired server's bearer token, one keychain item per server, and never in the settings file while a keychain holds
 * it. docs/DECISIONS.md, "The desktop client's tokens in the keychain".
 *
 * The data layer doesn't know. [com.prudnikweb.hypernova.data.ServerBook] keeps its list in the settings as it does on a
 * phone, and this stands between that file's memory and its disk: [prepare] puts the tokens into what the app reads, and
 * [seal] takes them out of what's written, a server's entry on the disk saying only [IN_KEYCHAIN]. All of it runs on the
 * thread the settings are written on, in order, so the keychain's prompt never holds the window, and two of these never race.
 *
 * What's different from the key ([DesktopKeys]) is that **a token can't be made again**. So:
 * - **A plain token in the file wins,** as the key's file does. It's only there because the keychain hadn't taken it, so it's
 *   the newest. It goes only when a [seal] has written it, read it back and had the same text.
 * - **A token the keychain won't give is never replaced, or dropped.** Its server's entry stays as it is on the disk, whatever
 *   else is saved meanwhile ([withheld]), and the keychain's item is never written over. The window says so ([waiting]), and
 *   [retry] asks again. Only the owner's own [forget] ends the wait.
 * - **The other servers carry on**: [com.prudnikweb.hypernova.data.Core] just doesn't see one that's waiting, until
 *   [com.prudnikweb.hypernova.data.Core.restored].
 *
 * Nothing here ever logs a token, or anything a helper said. No `toString` gives one away.
 */
class DesktopTokens(private val choice: KeychainChoice, private val log: Log) : PrefsSealer {
    private lateinit var storage: DesktopStorage
    private lateinit var keys: DeviceKeys
    private var post: (() -> Unit) -> Unit = { it() }

    var place by mutableStateOf<TokenPlace>(TokenPlace.None)
        private set
    /** The servers whose tokens are in the keychain and can't be had, for the window to say. */
    var waiting by mutableStateOf<List<WaitingServer>>(emptyList())
        private set
    var retrying by mutableStateOf(false)
        private set
    /** Servers back from waiting, the whole list's order, and which was on screen: [com.prudnikweb.hypernova.data.Core.restored]. */
    var restore: (List<Server>, List<String>, String?) -> Unit = { _, _, _ -> }

    // From here down: only on the writer's thread.
    private var why = choice.without
    /** It didn't answer, or didn't give back what it took: asked no more until [retry] or the next start. */
    private var down = false
    private var prepared = false
    /** The tokens the keychain has been seen to hold, by server. */
    private val inKeychain = HashMap<String, String>()
    /** Servers waiting, as their entries are on the disk: never a token. */
    private val withheld = LinkedHashMap<String, JSONObject>()
    /** The ids in the list's order, so a save with some left out keeps each in its place. */
    private var order = emptyList<String>()
    /** The servers the app's list held at the last save: one gone from it since is one forgotten. */
    private var listed = emptySet<String>()
    /** Items of servers forgotten while the keychain couldn't be told. Kept in the settings until it can. */
    private val toDrop = LinkedHashSet<String>()

    fun attach(storage: DesktopStorage, keys: DeviceKeys, post: (() -> Unit) -> Unit) {
        this.storage = storage
        this.keys = keys
        this.post = post
    }

    /** The first look, before the window, as a locked keychain may ask for its password here. Returns when it's done. */
    fun prepare() {
        storage.onWriterAndWait(::open)
    }

    private fun open() {
        val prefs = storage.file(TOKENS_PREFS)
        if ((keys as? DesktopKeys)?.keychainDown == true) wentDown()
        prefs.getString(TO_DROP, null)?.split(',')?.filterTo(toDrop) { it.isNotEmpty() }
        prepared = true
        val entries = prefs.getString(ServerBook.SERVERS, null)?.let(::entriesOf) ?: return
        val open = JSONArray()
        val waitingNow = ArrayList<WaitingServer>()
        var plain = false
        for (o in entries) {
            val id = o.optString("id")
            val token = o.optString("token")
            when {
                token.isNotEmpty() -> {
                    DebugScrub.remember(token)
                    plain = true
                    open.put(o)
                }
                !o.optBoolean(IN_KEYCHAIN) -> open.put(o)
                else -> when (val answer = readItem(id)) {
                    is KeychainRead.Found -> {
                        inKeychain[id] = answer.text
                        DebugScrub.remember(answer.text)
                        o.remove(IN_KEYCHAIN)
                        open.put(o.put("token", answer.text))
                    }
                    else -> {
                        withheld[id] = o
                        waitingNow += waitingOf(id, o, answer)
                    }
                }
            }
        }
        order = entries.map { it.optString("id") }
        listed = (0 until open.length()).mapTo(HashSet()) { open.getJSONObject(it).optString("id") }
        waiting = waitingNow
        prefs.opened(ServerBook.SERVERS, open.toString())
        place = if (inKeychain.isEmpty()) TokenPlace.None else TokenPlace.InKeychain
        // A plain token to move, one of the single pairing's, or an item still to remove: said on the disk at once.
        if (plain || prefs.contains(LEGACY_TOKEN) || toDrop.isNotEmpty()) {
            prefs.writeNow()
            runCatching { prefs.sweepLeftovers() }
        }
    }

    override fun seal(values: Map<String, Any>): Map<String, Any> {
        val entries = (values[ServerBook.SERVERS] as? String)?.let(::entriesOf) ?: return values
        var plain = 0
        var held = 0
        val seen = LinkedHashSet<String>()
        for (o in entries) {
            val id = o.optString("id")
            seen += id
            val token = o.optString("token")
            if (token.isNotEmpty()) {
                DebugScrub.remember(token)
                withheld.remove(id)
                if (keep(id, token)) {
                    o.remove("token")
                    o.put(IN_KEYCHAIN, true)
                    held++
                } else {
                    plain++
                }
            } else if (o.optBoolean(IN_KEYCHAIN)) {
                held++
            }
        }
        replaceWithheld(entries)
        if (prepared) (listed - seen - withheld.keys).forEach(::forgotten)
        listed = seen
        toDrop -= seen
        dropForgotten()
        val all = entries + withheld.values
        // Anything new goes at the end, in the order it has.
        val places = order.withIndex().associate { (i, id) -> id to i }
        val ordered = all.sortedBy { places[it.optString("id")] ?: Int.MAX_VALUE }
        order = ordered.map { it.optString("id") }
        publish(plain, held)
        var out = values + (ServerBook.SERVERS to JSONArray(ordered).toString())
        // The single pairing's keys (data/Pairing.kt) are written beside the list while it has that server: no second copy on the disk.
        if (order.contains(Servers.LEGACY_ID)) out = out - LEGACY_TOKEN
        out = if (toDrop.isEmpty()) out - TO_DROP else out + (TO_DROP to toDrop.joinToString(","))
        return out
    }

    /**
     * Try the keychain again, from a click: the servers waiting come back, what's in the file moves across, the key's
     * too. What it can't get stays as it was. Returns at once, the answers on the writer's thread and the window told on its own.
     */
    fun retry() {
        if (retrying) return
        retrying = true
        storage.onWriter {
            down = false
            why = choice.without
            runCatching { (keys as? DesktopKeys)?.retry() }
            val back = JSONArray()
            val still = ArrayList<WaitingServer>()
            for ((id, o) in withheld) {
                when (val answer = readItem(id)) {
                    is KeychainRead.Found -> {
                        inKeychain[id] = answer.text
                        DebugScrub.remember(answer.text)
                        back.put(JSONObject(o.toString()).apply { remove(IN_KEYCHAIN) }.put("token", answer.text))
                    }
                    else -> still += waitingOf(id, o, answer)
                }
            }
            waiting = still
            val prefs = storage.file(TOKENS_PREFS)
            // Those back stay withheld until the window's list has them: until then, a save keeps their entries as they are.
            prefs.writeNow()
            val servers = Servers.decode(back.toString())
            val active = prefs.getString(ServerBook.ACTIVE, null)
            val inOrder = order
            post {
                if (servers.isNotEmpty()) restore(servers, inOrder, active)
                retrying = false
            }
        }
    }

    /** The owner's own word: this server's token isn't coming back. Its certificate goes, and the keychain's item as soon as it can be told. */
    fun forget(id: String) {
        waiting = waiting.filter { it.id != id }
        storage.onWriter {
            if (withheld.remove(id) != null) {
                toDrop += id
                val last = listed.isEmpty() && withheld.isEmpty()
                runCatching { DeviceCertStore(storage, keys, id).clear(dropKey = last) }
                storage.file(TOKENS_PREFS).writeNow()
            }
        }
    }

    /** Pairing the same computer again while it waited: the server has replaced that device, so what's in the keychain for it is no use. */
    private fun replaceWithheld(entries: List<JSONObject>) {
        val replaced = withheld.filterValues { w -> entries.any { Servers.sameComputer(pairingOf(w), pairingOf(it)) } }.keys.toList()
        if (replaced.isEmpty()) return
        replaced.forEach {
            withheld.remove(it)
            toDrop += it
        }
        waiting = waiting.filter { it.id !in replaced }
    }

    /** Only what [Servers.sameComputer] compares. */
    private fun pairingOf(o: JSONObject) = Pairing("", "", o.optString("macName"), o.optString("macId").takeIf { it.isNotEmpty() }, null)

    private fun forgotten(id: String) {
        inKeychain.remove(id)
        if (choice.item(Keychains.tokenItem(id)) != null) toDrop += id
    }

    private fun dropForgotten() {
        if (down) return
        for (id in toDrop.toList()) {
            val item = choice.item(Keychains.tokenItem(id)) ?: continue
            if (!runCatching { item.delete() }.getOrDefault(false)) return
            toDrop -= id
        }
    }

    /** Written and read back. True only when it came back whole: then, and only then, the file may go without it. */
    private fun keep(id: String, token: String): Boolean {
        if (inKeychain[id] == token) return true
        inKeychain.remove(id)
        val item = choice.item(Keychains.tokenItem(id)) ?: return false
        if (down) return false
        if (!runCatching { item.write(token) }.getOrDefault(false)) {
            wentDown()
            return false
        }
        when (val back = runCatching { item.read() }.getOrElse { KeychainRead.Down }) {
            is KeychainRead.Found -> if (back.text != token) {
                didNotHold()
                return false
            }
            KeychainRead.Down -> {
                wentDown()
                return false
            }
            KeychainRead.Missing -> {
                didNotHold()
                return false
            }
        }
        inKeychain[id] = token
        log.i(TAG, "a token is in the keychain")
        return true
    }

    /** Null: there's no way to ask. A blank answer is no answer. */
    private fun readItem(id: String): KeychainRead? {
        val item = choice.item(Keychains.tokenItem(id)) ?: return null
        if (down) return KeychainRead.Down
        val answer = runCatching { item.read() }.getOrElse { KeychainRead.Down }
        if (answer == KeychainRead.Down) wentDown()
        return if (answer is KeychainRead.Found && answer.text.isBlank()) KeychainRead.Missing else answer
    }

    private fun waitingOf(id: String, o: JSONObject, answer: KeychainRead?) = WaitingServer(
        id, o.optString("macName").ifEmpty { "Mac" },
        when (answer) {
            KeychainRead.Missing -> WaitWhy.Gone
            null -> if (choice.without == FileWhy.TurnedOff) WaitWhy.TurnedOff else WaitWhy.NoHelper
            else -> WaitWhy.Locked
        },
    )

    private fun entriesOf(raw: String): List<JSONObject>? {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun publish(plain: Int, held: Int) {
        place = when {
            plain > 0 -> TokenPlace.InFile(why)
            held > 0 -> TokenPlace.InKeychain
            else -> TokenPlace.None
        }
        val left = waiting.filter { it.id in withheld }
        if (left.size != waiting.size) waiting = left
    }

    private fun wentDown() {
        if (!down) log.w(TAG, "the keychain didn't answer: a token in the keychain stays there, and a new one goes in its private file for now")
        down = true
        why = FileWhy.Down
    }

    private fun didNotHold() {
        log.w(TAG, "the keychain didn't give a token back: it stays in its private file")
        down = true
        why = FileWhy.DidNotHold
    }

    override fun toString() = "DesktopTokens"

    private companion object {
        const val TAG = "Tokens"
        /** A server's entry on the disk, in place of its token. */
        const val IN_KEYCHAIN = "tokenInKeychain"
        const val TO_DROP = "tokensToRemove"
        /** The single pairing's own key, for the server [Servers.LEGACY_ID]. */
        const val LEGACY_TOKEN = "token"
    }
}
