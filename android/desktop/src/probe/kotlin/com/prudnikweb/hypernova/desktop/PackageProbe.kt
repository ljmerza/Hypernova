package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.ActiveRoutes
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DeviceCertStore
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.RouteKind
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Pairs an installed package with the demo server (demo/README.md), inside its own Java, without a window or a
 * keyboard. In no package: scripts/test-client-package.sh starts the installed launcher with
 * `JAVA_TOOL_OPTIONS=-javaagent:<this jar>`, so the launcher, the runtime and the app's jars are the ones that
 * were installed, and nothing in the install has to change. This runs before the app's own `main`, and exits.
 *
 * The pairing is the window's own ([Shell]). What it proves is that the runtime holds what `includeAllModules` is
 * there for: this computer's EC key made and read back, the certificate requested on it, and mutual TLS with it.
 *
 * `HYPERNOVA_PROBE_LINK` is the file with the pairing link, `HYPERNOVA_PROBE_OUT` where the answer goes (a window
 * launcher on Windows has no standard output), and `HYPERNOVA_CLIENT_HOME` the folder, which stays paired after it.
 * With no link it starts on the pairing the folder already has, as an install does when it's updated.
 *
 * It also says where the key ended up and which key it is, by a hash of the public half, for the script to follow
 * it from the file to the keychain and back, and where the tokens are. No secret is ever in this answer.
 *
 * For the tokens' way (scripts/test-client-package.sh):
 * - `HYPERNOVA_PROBE_EXPECT=waiting`: a server's token has to be out of reach at the start, and this says why. With no other
 *   server to reach, that's all it does.
 * - `HYPERNOVA_PROBE_GO=<file>`: then says so in `<file>.waiting`, waits for `<file>` to appear, and presses Try again.
 * - `HYPERNOVA_PROBE_SERVERS=<n>`: every one of `n` servers has to link, not only the one on screen.
 * - `HYPERNOVA_PROBE_FORGET=<name>`: then forgets that server, as Pair again does.
 */
object PackageProbe {
    private const val TIMEOUT_MS = 90_000L

    @JvmStatic
    fun premain(@Suppress("UNUSED_PARAMETER") args: String?) {
        val out = File(checkNotNull(System.getenv("HYPERNOVA_PROBE_OUT")) { "HYPERNOVA_PROBE_OUT isn't set" })
        val lines = mutableListOf<String>()
        val code = try {
            lines += "java.home=${System.getProperty("java.home")}"
            lines += "java.version=${System.getProperty("java.runtime.version")}"
            lines += "app.version=${DesktopAppInfo.versionName}"
            // The app never reaches its own check for a display: this has no window to open.
            System.setProperty("java.awt.headless", "true")
            val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            lines += "ec=${ec.provider.name}"
            lines += "tls=${SSLContext.getDefault().supportedSSLParameters.protocols.joinToString(",")}"
            pair(lines)
            lines += "ok=true"
            0
        } catch (e: Throwable) {
            lines += "failed=$e"
            e.stackTrace.take(12).forEach { lines += "  at $it" }
            1
        }
        out.writeText(lines.joinToString("\n", postfix = "\n"))
        System.err.println(lines.joinToString("\n"))
        exitProcess(code)
    }

    private fun pair(lines: MutableList<String>) {
        val link = System.getenv("HYPERNOVA_PROBE_LINK")?.takeIf { it.isNotBlank() }?.let { File(it).readText().trim() }
        val platform = DesktopPlatform()
        val shell = startCore(platform)
        val until = System.currentTimeMillis() + TIMEOUT_MS
        if (System.getenv("HYPERNOVA_PROBE_EXPECT") == "waiting") {
            check(platform.tokens.waiting.isNotEmpty()) { "no server waiting for its token: ${tokenPlace(platform)}" }
            lines += "token.waiting=${platform.tokens.waiting.joinToString(",") { it.why.name }}"
            val go = System.getenv("HYPERNOVA_PROBE_GO")?.takeIf { it.isNotBlank() }?.let(::File)
            if (go != null) {
                File("${go.path}.waiting").writeText("waiting")
                while (!go.exists()) {
                    check(System.currentTimeMillis() < until) { "nobody unlocked it" }
                    Thread.sleep(200)
                }
                SwingUtilities.invokeAndWait { platform.tokens.retry() }
                while (platform.tokens.waiting.isNotEmpty() || platform.tokens.retrying) {
                    check(System.currentTimeMillis() < until) { "Try again brought nothing back" }
                    Thread.sleep(200)
                }
                lines += "token.waiting.after=${platform.tokens.waiting.size}"
            } else if (link == null && Core.servers.isEmpty()) {
                platform.folder.flush()
                check(Core.live == null) { "a hub with no token" }
                report(lines, platform)
                return
            }
        }
        if (link != null) {
            SwingUtilities.invokeAndWait {
                shell.link = link
                shell.submit()
            }
        }
        fun waitFor(what: String, done: () -> Boolean) {
            while (!done()) {
                (shell.pair as? PairState.Failed)?.let { error("the pairing failed: ${it.title}: ${it.body}") }
                check(System.currentTimeMillis() < until) { "no $what after ${TIMEOUT_MS / 1000} s: ${shell.pair}, ${Core.live?.link?.state}" }
                Thread.sleep(200)
            }
        }
        // As DemoServerTest: the demo gives what comes after the handshake only to a certified device.
        waitFor("list") { Core.live?.link?.state == LinkState.Connected && Core.live?.sessions?.isNotEmpty() == true }
        waitFor("certificate") { DeviceCertStore(platform.storage, platform.deviceKeys, Core.activeId!!).identity() != null }
        waitFor("socket with the certificate") { Core.live?.link?.state == LinkState.Connected && Core.live?.mac?.via == "local" }
        val route = ActiveRoutes.route(Core.pairing!!.baseUrl)
        check(route.kind == RouteKind.Local) { "over ${route.kind}, not the pinned local route" }
        checkNotNull(route.fingerprint) { "no pin" }
        checkNotNull(route.identity) { "no certificate on the route" }
        lines += "paired=${Core.live?.mac?.name} sessions=${Core.live?.sessions?.size} route=${route.kind}"
        System.getenv("HYPERNOVA_PROBE_SERVERS")?.takeIf { it.isNotBlank() }?.toInt()?.let { n ->
            waitFor("$n servers each linked") { Core.servers.size == n && Core.allHubs.size == n && Core.allHubs.all { it.link.state == LinkState.Connected && it.mac.via == "local" } }
        }
        System.getenv("HYPERNOVA_PROBE_FORGET")?.takeIf { it.isNotBlank() }?.let { name ->
            val id = checkNotNull(Core.servers.firstOrNull { it.pairing.macName == name }) { "no server named $name" }.id
            SwingUtilities.invokeAndWait { Core.forget(id) }
            lines += "forgot=$name"
        }
        lines += "servers=${Core.servers.joinToString(",") { it.pairing.macName }}"
        platform.folder.flush()
        report(lines, platform)
    }

    private fun tokenPlace(platform: DesktopPlatform) = when (val p = platform.tokens.place) {
        TokenPlace.None -> "none"
        TokenPlace.InKeychain -> "keychain"
        is TokenPlace.InFile -> "file:${p.why}"
    }

    private fun report(lines: MutableList<String>, platform: DesktopPlatform) {
        lines += "key.place=${when (val p = platform.keys.place) {
            KeyPlace.Nowhere -> "nowhere"
            KeyPlace.InKeychain -> "keychain"
            is KeyPlace.InFile -> "file:${p.why}"
        }}"
        lines += "key.public=${platform.keys.publicKey()?.let { k -> MessageDigest.getInstance("SHA-256").digest(k.encoded).joinToString("") { "%02x".format(it) } } ?: "none"}"
        lines += "token.place=${tokenPlace(platform)}"
    }
}
