package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.ActiveRoutes
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DeviceCertStore
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.RouteKind
import com.prudnikweb.hypernova.desktop.DesktopRig.main
import com.prudnikweb.hypernova.desktop.DesktopRig.platform
import com.prudnikweb.hypernova.desktop.DesktopRig.render
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The whole thing against a real server program: the demo server (demo/README.md), which runs the daemon's own pairing
 * and certificate code over mutual TLS on loopback, from invented data. It takes a running one, so CI skips it:
 *
 *   HYPERNOVA_DEMO_ADDRESS=127.0.0.1 HYPERNOVA_DEMO_PORT=… HYPERNOVA_DEMO_HOME=… node demo/server.ts atlas
 *   HYPERNOVA_TEST_DEMO_LINK=<its pair-link file> HYPERNOVA_TEST_DEMO_PID=<its pid> ./gradlew --no-configuration-cache :desktop:cleanTest :desktop:test --tests '*DemoServerTest*'
 *
 * With a pid it stops that server at the end, to see this app go offline.
 */
@OptIn(ExperimentalTestApi::class)
class DemoServerTest {
    @Test fun pairsListsIsRefusedAndGoesOffline() {
        val linkFile = System.getenv("HYPERNOVA_TEST_DEMO_LINK")
        assumeTrue("no demo server given", !linkFile.isNullOrBlank())
        val link = File(linkFile!!).readText().trim()
        runDesktopComposeUiTest(960, 1240) {
            setContent { App(shell, reduced = true) }
            render("20-demo-pair")
            main {
                shell.link = link
                shell.submit()
            }
            until("linked", timeoutMs = 30_000) { Core.live?.link?.state == LinkState.Connected && Core.live?.sessions?.isNotEmpty() == true }
            until("the certificate", timeoutMs = 30_000) { DeviceCertStore(platform.storage, platform.deviceKeys, Core.activeId!!).identity() != null }
            val route = ActiveRoutes.route(Core.pairing!!.baseUrl)
            assertEquals("over the pinned local route", RouteKind.Local, route.kind)
            assertNotNull("with the pin", route.fingerprint)
            assertNotNull("and this computer's certificate", route.identity)
            // The demo gives what comes after the handshake only to a certified device, so the sessions say it was checked.
            until("the socket again, with the certificate", timeoutMs = 30_000) { Core.live?.link?.state == LinkState.Connected && Core.live?.mac?.via == "local" }
            render("21-demo-list")
            println("DEMO paired with ${Core.live?.mac?.name}, ${Core.live?.sessions?.size} sessions, route ${route.kind}, pin ${route.fingerprint}, device ${Core.pairing?.deviceId}")
            println("DEMO key file ${platform.keyFile}: ${java.nio.file.Files.getPosixFilePermissions(platform.keyFile)}")

            // Read-only: it refuses an answer, and that's said.
            onNodeWithText(Ui.allow.text()).performClick()
            until("the refusal", timeoutMs = 20_000) { shell.toast != null }
            println("DEMO a refused Allow said: ${shell.toast}")
            assertTrue(!shell.toast.isNullOrBlank())
            waitForIdle()
            onNodeWithText(Ui.desk_answer_failed.text()).assertExists()
            render("22-demo-refused")

            val pid = System.getenv("HYPERNOVA_TEST_DEMO_PID")?.toLongOrNull()
            if (pid != null) {
                main { shell.dismissToast() }
                assertTrue(ProcessHandle.of(pid).map { it.destroy() }.orElse(false))
                until("offline", timeoutMs = 90_000) { Core.live?.link?.state == LinkState.Offline }
                waitForIdle()
                onNodeWithText(Ui.desk_link_offline.text().uppercase()).assertExists()
                onNodeWithText(Ui.cant_reach.format(Core.live!!.mac.name)).assertExists()
                println("DEMO offline says: ${com.prudnikweb.hypernova.data.linkProblemText(platform.strings, Core.live!!.link)}")
                render("23-demo-offline")
            }
            assertFalse(Core.servers.isEmpty())
        }
    }
}
