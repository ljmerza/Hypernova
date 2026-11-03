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
 * it from the file to the keychain and back. Nothing of the private half is ever in this answer.
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
        if (link != null) {
            SwingUtilities.invokeAndWait {
                shell.link = link
                shell.submit()
            }
        }
        val until = System.currentTimeMillis() + TIMEOUT_MS
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
        lines += "key.place=${when (val p = platform.keys.place) {
            KeyPlace.Nowhere -> "nowhere"
            KeyPlace.InKeychain -> "keychain"
            is KeyPlace.InFile -> "file:${p.why}"
        }}"
        lines += "key.public=${MessageDigest.getInstance("SHA-256").digest(checkNotNull(platform.keys.publicKey()).encoded).joinToString("") { "%02x".format(it) }}"
        platform.folder.flush()
    }
}
