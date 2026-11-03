package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.Log
import com.prudnikweb.hypernova.data.Prefs
import com.prudnikweb.hypernova.data.PrefsEditor
import com.prudnikweb.hypernova.data.Storage
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where this app keeps what it has on this computer. It's the client's own folder, never the server's
 * (`~/.local/share/hypernova` on Linux), since one computer can run both.
 */
object DataFolder {
    /** Overrides the folder, for a test copy. */
    const val ENV = "HYPERNOVA_CLIENT_HOME"
    /** Folder names, not the app's visible name ([APP_NAME]): a rename mustn't strand a key that's already paired. */
    private const val LINUX_NAME = "hypernova-client"
    private const val DESKTOP_NAME = "Hypernova Client"

    fun of(
        env: (String) -> String? = System::getenv,
        os: String = System.getProperty("os.name").orEmpty(),
        home: String = System.getProperty("user.home").orEmpty(),
    ): File {
        env(ENV)?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val name = os.lowercase()
        return when {
            // %LOCALAPPDATA%, not %APPDATA%: Windows copies the roaming one to other machines, and the key must stay on this one.
            name.startsWith("windows") -> File(env("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Local", DESKTOP_NAME)
            name.startsWith("mac") -> File(home, "Library/Application Support/$DESKTOP_NAME")
            // The XDG spec has a relative value ignored.
            else -> File(env("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.local/share", LINUX_NAME)
        }
    }
}

/**
 * Files only this user can read: 0600 and 0700 where the system has POSIX modes, an ACL naming only the
 * owner where it has those (Windows), and a refusal where it has neither, or where the mode doesn't take
 * (a FAT or NTFS mount on Linux takes the chmod and keeps 0777). What's kept here is a token and a key.
 */
object OwnerOnly {
    private val FILE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private val DIR = FILE + PosixFilePermission.OWNER_EXECUTE

    private fun posix(p: Path) = Files.getFileAttributeView(p, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)

    /** The folder, made if it isn't there, with only its last step owner-only. */
    fun dir(path: Path): Path {
        path.parent?.let { Files.createDirectories(it) }
        val onPosix = path.parent?.let { posix(it) } != null
        try {
            if (onPosix) Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIR)) else Files.createDirectory(path)
        } catch (_: FileAlreadyExistsException) {
            // There already: tightened below.
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("$path isn't a folder")
        protect(path, directory = true)
        return path
    }

    /** Whether only the owner can get at [path]. Null: the system has no way to say. */
    fun isOwnerOnly(path: Path): Boolean? {
        val p = posix(path)
        if (p != null) {
            val perms = p.readAttributes().permissions()
            return perms.none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") }
        }
        val a = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return null
        val owner = a.owner
        return a.acl.all { it.principal() == owner }
    }

    /** [path] made owner-only, and checked; throws when it can't be. */
    fun protect(path: Path, directory: Boolean = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        val p = posix(path)
        if (p != null) {
            p.setPermissions(if (directory) DIR else FILE)
        } else {
            val a = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                ?: throw IOException("Can't keep $path private here: this file system has neither POSIX modes nor ACLs.")
            val flags = if (directory) setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) else emptySet()
            a.acl = listOf(
                AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(a.owner)
                    .setPermissions(AclEntryPermission.entries.toSet()).setFlags(flags).build(),
            )
        }
        if (isOwnerOnly(path) != true) throw IOException("Can't keep $path private here: this file system doesn't hold the permissions it's given.")
    }

    /**
     * [bytes] as [path]'s whole content, or the old content if anything goes wrong: a file made
     * owner-only before any byte is in it, written and synced, then moved over [path] in one step.
     * Never a symlink's target.
     */
    fun write(path: Path, bytes: ByteArray) {
        if (Files.isSymbolicLink(path)) throw IOException("$path is a symbolic link: refusing to write through it.")
        val dir = checkNotNull(path.parent) { "$path has no folder" }
        val temp = if (posix(dir) != null) {
            Files.createTempFile(dir, path.fileName.toString(), ".tmp", PosixFilePermissions.asFileAttribute(FILE))
        } else {
            Files.createTempFile(dir, path.fileName.toString(), ".tmp")
        }
        try {
            protect(temp, directory = false)
            FileChannel.open(temp, StandardOpenOption.WRITE).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

/** What a settings file keeps off the disk ([DesktopTokens]). */
interface PrefsSealer {
    /** [values] as they may go on the disk. Run where the file is written, never on the window's thread. */
    fun seal(values: Map<String, Any>): Map<String, Any>
}

/**
 * One file of small settings, in the folder; read once, kept in memory and written behind, like
 * SharedPreferences. A file of JSON (`{"lanPort": 47323}`): the type of a value is whatever it
 * was put as, a whole number going by what's asked for.
 */
class FilePrefs(
    private val file: Path,
    private val log: Log,
    private val sealer: PrefsSealer? = null,
    private val schedule: (() -> Unit) -> Unit,
) : Prefs {
    private val values = ConcurrentHashMap<String, Any>()
    private val dirty = AtomicBoolean(false)
    private val lock = Any()

    init {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) load()
    }

    private fun load() {
        if (Files.isSymbolicLink(file)) throw IOException("$file is a symbolic link: refusing to read it as settings.")
        // Someone's chmod, or a restored backup: back to owner-only, and said in the log.
        if (OwnerOnly.isOwnerOnly(file) != true) {
            log.w(TAG, "${file.fileName} could be read by others: made owner-only again")
            OwnerOnly.protect(file, directory = false)
        }
        try {
            val o = JSONObject(Files.readString(file))
            for (k in o.keys()) values[k] = o.get(k)
        } catch (e: org.json.JSONException) {
            // Writes are atomic, so this isn't ours. Kept aside, not lost, and the app starts unpaired.
            log.w(TAG, "${file.fileName} isn't readable: kept aside (${e.javaClass.simpleName})")
            Files.move(file, file.resolveSibling("${file.fileName}.corrupt"), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun getString(key: String, default: String?): String? = values[key] as? String ?: default
    override fun getInt(key: String, default: Int): Int = (values[key] as? Number)?.toInt() ?: default
    override fun getLong(key: String, default: Long): Long = (values[key] as? Number)?.toLong() ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun contains(key: String): Boolean = values.containsKey(key)
    override val all: Map<String, *> get() = values.toMap()

    override fun edit(): PrefsEditor = object : PrefsEditor {
        private val ops = ArrayList<() -> Unit>()
        private var clearFirst = false
        private fun put(key: String, value: Any?): PrefsEditor = apply { ops += { if (value == null) values.remove(key) else values[key] = value } }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)
        // As SharedPreferences: a clear happens first, whatever the order it was called in.
        override fun clear(): PrefsEditor = apply { clearFirst = true }
        override fun apply() {
            synchronized(lock) {
                if (clearFirst) values.clear()
                ops.forEach { it() }
            }
            if (dirty.compareAndSet(false, true)) schedule(::writeNow)
        }
    }

    /** On the writer's thread. */
    internal fun writeNow() {
        dirty.set(false)
        val now = synchronized(lock) { values.toMap() }
        // A full disk or a removed folder: the values stay in memory, and the next change tries again.
        // Nor does anything go out unsealed: the old file stays if sealing itself ever breaks.
        try {
            val out = sealer?.seal(now) ?: now
            OwnerOnly.write(file, JSONObject(out).toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            log.w(TAG, "couldn't save ${file.fileName}: ${e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            log.w(TAG, "couldn't save ${file.fileName}: ${e.javaClass.simpleName}")
        }
    }

    /** [value] as this run sees it, with nothing written ([PrefsSealer]'s other half: what the disk doesn't hold). */
    internal fun opened(key: String, value: String) {
        synchronized(lock) { values[key] = value }
    }

    /** What a write killed halfway left beside the file. They can hold what was in it, so they go once it's been sealed. */
    internal fun sweepLeftovers() {
        val dir = file.parent ?: return
        val mine = file.fileName.toString()
        Files.list(dir).use { all ->
            all.filter { it.fileName.toString().let { n -> n.startsWith(mine) && n.endsWith(".tmp") } }.forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        const val TAG = "Storage"
    }
}

/** This app's folder ([home]), owner-only, and its settings files (`prefs/<name>.json`). */
class DesktopStorage(val home: Path, private val log: Log, private val sealers: Map<String, PrefsSealer> = emptyMap()) : Storage, AutoCloseable {
    private val prefsDir: Path
    private val files = ConcurrentHashMap<String, FilePrefs>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "prefs-writer").apply { isDaemon = true } }

    init {
        OwnerOnly.dir(home)
        prefsDir = OwnerOnly.dir(home.resolve("prefs"))
    }

    override val filesDir: File = OwnerOnly.dir(home.resolve("files")).toFile()

    override fun prefs(name: String): Prefs = file(name)

    internal fun file(name: String): FilePrefs = files.computeIfAbsent(name) {
        // URL-encoded: never a path, whatever a name holds. "." and ".." are all that encoding leaves as they are.
        require(it.isNotBlank() && it != "." && it != "..") { "not a settings name: $it" }
        FilePrefs(prefsDir.resolve(URLEncoder.encode(it, Charsets.UTF_8) + ".json"), log, sealers[it]) { write -> writer.execute(write) }
    }

    /** [block] where the settings are written: it can wait on a keychain there, and every write waits for it. */
    internal fun onWriter(block: () -> Unit) {
        writer.execute(block)
    }

    /** As [onWriter], returning when it's done. Never from that thread. */
    internal fun onWriterAndWait(block: () -> Unit) {
        writer.submit(block).get()
    }

    /** Everything changed so far is on disk when this returns. Longer than a keychain's prompt: a write may be waiting on one. */
    fun flush() {
        writer.submit { }.get(WAIT_S, TimeUnit.SECONDS)
    }

    override fun close() {
        writer.shutdown()
        writer.awaitTermination(WAIT_S, TimeUnit.SECONDS)
    }

    private companion object {
        const val WAIT_S = 45L
    }
}
