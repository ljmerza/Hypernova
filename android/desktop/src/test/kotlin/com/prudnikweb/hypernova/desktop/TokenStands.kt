package com.prudnikweb.hypernova.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A keychain stood in for, with an item per name, as a real one has. What was said to it is kept, for a test to look at. */
class StandShelf {
    @Volatile var locked = false
    @Volatile var refuses = false
    /** It takes a write and then can't find it. */
    @Volatile var loses = false
    /** It gives this back in place of what it holds. */
    @Volatile var garbled: String? = null
    @Volatile var boom: String? = null
    val held = ConcurrentHashMap<String, String>()
    val writes = CopyOnWriteArrayList<String>()
    val reads = CopyOnWriteArrayList<String>()
    val deletes = CopyOnWriteArrayList<String>()
    @Volatile var onRead: (String) -> Unit = {}

    fun item(name: String): Keychain = object : Keychain {
        override fun read(): KeychainRead {
            reads += name
            onRead(name)
            boom?.let { throw IllegalStateException(it) }
            if (locked) return KeychainRead.Down
            garbled?.let { return KeychainRead.Found(it) }
            if (loses) return KeychainRead.Missing
            return held[name]?.let { KeychainRead.Found(it) } ?: KeychainRead.Missing
        }

        override fun write(text: String): Boolean {
            writes += name
            boom?.let { throw IllegalStateException(it) }
            if (locked || refuses) return false
            held[name] = text
            return true
        }

        override fun delete(): Boolean {
            deletes += name
            if (locked) return false
            held.remove(name)
            return true
        }
    }

    fun choice() = KeychainChoice(item("device-key"), item = ::item)

    fun token(server: String): String? = held[Keychains.tokenItem(server)]

    /** Only the servers' items. */
    val tokenItems: Set<String> get() = held.keys.filter { it.startsWith("token.") }.toSet()
}

/** What's on the disk: the settings file and every other file in the folder. */
object TokenDisk {
    fun allFiles(home: Path): List<Path> = Files.walk(home).use { w -> w.filter { Files.isRegularFile(it) }.toList() }

    fun noFileHolds(home: Path, vararg secrets: String) {
        for (f in allFiles(home)) {
            val text = String(Files.readAllBytes(f))
            for (s in secrets) check(!text.contains(s)) { "$f holds a token" }
        }
    }

    fun settings(home: Path): JSONObject = JSONObject(Files.readString(home.resolve("prefs/hypernova.json")))

    fun servers(home: Path): List<JSONObject> = JSONArray(settings(home).getString("servers")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    fun server(id: String, base: String, name: String, token: String? = null, fp: String? = null, lanPort: Int? = null): JSONObject =
        JSONObject().put("id", id).put("baseUrl", base).put("macName", name).put("macId", "m-$name").put("deviceId", "dev-$name").apply {
            if (token != null) put("token", token) else put("tokenInKeychain", true)
            if (fp != null) put("fingerprint", fp).put("lan", JSONArray(listOf("127.0.0.1")))
            if (lanPort != null) put("lanPort", lanPort)
        }

    /** A folder as an earlier run left it. */
    fun seed(home: Path, vararg servers: JSONObject) {
        Files.createDirectories(home.resolve("prefs"))
        val active = servers.first().getString("id")
        Files.writeString(home.resolve("prefs/hypernova.json"), JSONObject().put("servers", JSONArray(servers.toList()).toString()).put("activeServer", active).toString())
    }
}
