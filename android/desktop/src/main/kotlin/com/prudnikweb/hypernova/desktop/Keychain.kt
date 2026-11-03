package com.prudnikweb.hypernova.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/*
 * The system's own place for a secret, reached through the program each system ships for it, with nothing added to
 * the package. Why that, and what each one does and doesn't protect from: docs/DECISIONS.md, "The desktop client's
 * key in the keychain". What's kept is the same text as the owner-only file, and a secret only ever goes to a
 * program on its standard input, never on its command line, where any user on the computer could read it.
 */

/** What a keychain said when asked for the key. */
sealed interface KeychainRead {
    /** The text it holds. Never put this in a log or a message: see [toString]. */
    class Found(val text: String) : KeychainRead {
        override fun toString() = "Found"
    }
    /** It's working, and it holds nothing for this folder. */
    data object Missing : KeychainRead
    /** It didn't answer: locked, not running, a prompt nobody answered. It may well hold the key. */
    data object Down : KeychainRead
}

interface Keychain {
    fun read(): KeychainRead
    /** False when it can't have taken it. True still has to be proved by reading it back. */
    fun write(text: String): Boolean
    fun delete()
}

/** Why the key is in the owner-only file. Settings says each in its own words. */
enum class FileWhy {
    /** `HYPERNOVA_CLIENT_KEYCHAIN=off`. */
    TurnedOff,
    /** Nothing on this computer to reach a keychain with: on Linux, no `secret-tool`. */
    NoHelper,
    /** A keychain is there to try and didn't answer: locked or not running. */
    Down,
    /** It took the key and gave back something else, so it isn't trusted with it. */
    DidNotHold,
}

/** Which keychain this computer has, or why there's none to try. */
class KeychainChoice(val keychain: Keychain?, val without: FileWhy = FileWhy.NoHelper) {
    companion object {
        val OFF = KeychainChoice(null, FileWhy.TurnedOff)
    }
}

/** A finished helper program. No `toString`, as [out] can be the key. */
class HelperResult(val code: Int, val out: String, val saidSomething: Boolean)

/** Runs a helper. Null: it couldn't start, or hadn't finished in time, which is the same to a caller. */
fun interface Helper {
    fun run(command: List<String>, stdin: String?, timeoutMs: Long): HelperResult?
}

/** A real process. Only its own child is ever stopped, and nothing it says is logged here. */
object SystemHelper : Helper {
    private const val MAX_OUTPUT = 64 * 1024

    override fun run(command: List<String>, stdin: String?, timeoutMs: Long): HelperResult? {
        val p = try {
            ProcessBuilder(command).start()
        } catch (_: IOException) {
            return null
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val readers = listOf(drain(p.inputStream, out), drain(p.errorStream, err))
        val writer = thread(isDaemon = true, name = "keychain-helper") {
            runCatching { p.outputStream.use { o -> stdin?.let { o.write(it.toByteArray(Charsets.UTF_8)) } } }
        }
        val done = runCatching { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!done) {
            p.destroyForcibly()
            return null
        }
        writer.join(1_000)
        readers.forEach { it.join(1_000) }
        return HelperResult(p.exitValue(), synchronized(out) { out.toString(Charsets.UTF_8) }, synchronized(err) { err.size() } > 0)
    }

    private fun drain(from: InputStream, into: ByteArrayOutputStream) = thread(isDaemon = true, name = "keychain-helper") {
        runCatching {
            val buf = ByteArray(4096)
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                synchronized(into) { if (into.size() < MAX_OUTPUT) into.write(buf, 0, n) }
            }
        }
    }
}

/** How long one question to a keychain may take. An unlock prompt is why it's this long, and why [DesktopKeys.prepare] asks before there's a window. */
const val KEYCHAIN_TIMEOUT_MS = 20_000L

/** Not the visible name: a rename mustn't strand a key that's already paired (as DataFolder's names). */
private const val SERVICE = "hypernova-client"

/**
 * The Secret Service (GNOME Keyring; KWallet since Frameworks 5.97), through libsecret's `secret-tool`: secrets on its
 * standard input when that isn't a terminal, and its answer on its standard output (tool/secret-tool.c).
 * `lookup` exits 1 either way: with words on standard error it failed, with none there's no such item.
 */
class SecretServiceKeychain(private val account: String, private val helper: Helper = SystemHelper, private val tool: String = "secret-tool") : Keychain {
    private val attributes = listOf("service", SERVICE, "account", account)

    override fun read(): KeychainRead {
        val r = helper.run(listOf(tool, "lookup") + attributes, null, KEYCHAIN_TIMEOUT_MS) ?: return KeychainRead.Down
        return when {
            r.code == 0 && r.out.isNotBlank() -> KeychainRead.Found(r.out.trim())
            r.code != 0 && !r.saidSomething -> KeychainRead.Missing
            else -> KeychainRead.Down
        }
    }

    override fun write(text: String): Boolean =
        helper.run(listOf(tool, "store", "--label=Hypernova Client's device key") + attributes, text, KEYCHAIN_TIMEOUT_MS)?.code == 0

    override fun delete() {
        helper.run(listOf(tool, "clear") + attributes, null, KEYCHAIN_TIMEOUT_MS)
    }
}

/**
 * The login keychain, through `/usr/bin/security`, a program Apple signs, so the item's list of who may read it names
 * something that doesn't change with each unsigned update of this app. `security -i` reads its commands from standard
 * input, which keeps the secret off a command line. Written from Apple's man page: **it hasn't run on a Mac** (CI's first).
 */
class MacKeychain(private val account: String, private val helper: Helper = SystemHelper, private val tool: String = "/usr/bin/security") : Keychain {
    override fun read(): KeychainRead {
        val r = helper.run(listOf(tool, "find-generic-password", "-s", SERVICE, "-a", account, "-w"), null, KEYCHAIN_TIMEOUT_MS) ?: return KeychainRead.Down
        return when {
            r.code == 0 && r.out.isNotBlank() -> KeychainRead.Found(r.out.trim())
            // errSecItemNotFound, -25300, as an exit status keeps its low byte.
            r.code == ITEM_NOT_FOUND -> KeychainRead.Missing
            else -> KeychainRead.Down
        }
    }

    /** `security -i`'s own exit says little about the commands it read, so this only says it ran: reading back is what counts. */
    override fun write(text: String): Boolean {
        val hex = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        return helper.run(listOf(tool, "-i"), "add-generic-password -U -s $SERVICE -a $account -X $hex\n", KEYCHAIN_TIMEOUT_MS) != null
    }

    override fun delete() {
        helper.run(listOf(tool, "delete-generic-password", "-s", SERVICE, "-a", account), null, KEYCHAIN_TIMEOUT_MS)
    }

    private companion object {
        const val ITEM_NOT_FOUND = 44
    }
}

/**
 * Windows' own encryption for a user's secrets (DPAPI, `ProtectedData` with `CurrentUser`: only this user, at this
 * login, can undo it), through Windows' PowerShell, and the result in the owner-only folder ([file]). A machine locked
 * to PowerShell's constrained language can't call it, which reads as down. Written from Microsoft's documents:
 * **it hasn't run on Windows** (CI's first).
 */
class WindowsDpapiKeychain(
    private val file: Path,
    private val helper: Helper = SystemHelper,
    systemRoot: String? = System.getenv("SystemRoot"),
) : Keychain {
    // By its whole path: never a powershell.exe that's found first on the way.
    private val powershell = File(systemRoot?.takeIf { it.isNotBlank() } ?: "C:\\Windows", "System32\\WindowsPowerShell\\v1.0\\powershell.exe").path

    private fun script(method: String) = "\$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Security;" +
        "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$method([Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()),\$null,'CurrentUser'))"

    private fun run(method: String, input: ByteArray): ByteArray? {
        val r = helper.run(listOf(powershell, "-NoProfile", "-NonInteractive", "-Command", script(method)), Base64.getEncoder().encodeToString(input), KEYCHAIN_TIMEOUT_MS)
        if (r == null || r.code != 0) return null
        return runCatching { Base64.getDecoder().decode(r.out.trim()) }.getOrNull()
    }

    override fun read(): KeychainRead {
        if (!Files.exists(file)) return KeychainRead.Missing
        val sealed = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return KeychainRead.Down
        return run("Unprotect", sealed)?.let { KeychainRead.Found(it.toString(Charsets.UTF_8)) } ?: KeychainRead.Down
    }

    override fun write(text: String): Boolean {
        val sealed = run("Protect", text.toByteArray(Charsets.UTF_8)) ?: return false
        OwnerOnly.write(file, sealed)
        return true
    }

    override fun delete() {
        Files.deleteIfExists(file)
    }
}

object Keychains {
    /** `off` keeps the key in the file: a test copy's, or an owner's who'd rather. */
    const val ENV = "HYPERNOVA_CLIENT_KEYCHAIN"

    /** A folder's own item, so a test copy's folder ([DataFolder.ENV]) never sees the real client's key, or replaces it. */
    fun accountOf(home: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(home.toAbsolutePath().normalize().toString().toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it) }

    fun forThisSystem(
        home: Path,
        env: (String) -> String? = System::getenv,
        os: String = System.getProperty("os.name").orEmpty(),
    ): KeychainChoice {
        if (env(ENV)?.trim()?.lowercase() == "off") return KeychainChoice.OFF
        val name = os.lowercase()
        return when {
            name.startsWith("windows") -> KeychainChoice(WindowsDpapiKeychain(home.resolve("device-key.dpapi"), systemRoot = env("SystemRoot")))
            name.startsWith("mac") -> KeychainChoice(MacKeychain(accountOf(home)))
            onPath("secret-tool", env("PATH")) -> KeychainChoice(SecretServiceKeychain(accountOf(home)))
            else -> KeychainChoice(null, FileWhy.NoHelper)
        }
    }

    fun onPath(program: String, path: String?): Boolean =
        path.orEmpty().split(File.pathSeparatorChar).any { it.isNotEmpty() && File(it, program).canExecute() }
}
