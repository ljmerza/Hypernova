package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DeviceCertStore
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.PairingStore
import com.prudnikweb.hypernova.data.Pin
import com.prudnikweb.hypernova.data.RouteKind
import com.prudnikweb.hypernova.data.ActiveRoutes
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.linkOf
import com.prudnikweb.hypernova.desktop.DesktopRig.main
import com.prudnikweb.hypernova.desktop.DesktopRig.platform
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.takesCode
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The pairing screen through the real pairing: the phone's [com.prudnikweb.hypernova.data.Pairer], the real pin and
 * real mutual TLS on loopback, with this platform's key and files. Only what's on the other side is scripted.
 */
@OptIn(ExperimentalTestApi::class)
class PairingTest {
    private val daemons = mutableListOf<FakeDaemon>()
    private fun daemon(name: String = "z2") = FakeDaemon(name, lan = true).also { daemons += it }

    @Before fun fresh() = DesktopRig.reset()

    @After fun tearDown() {
        DesktopRig.reset()
        daemons.forEach { it.close() }
    }

    @Test fun aPastedLinkPairsForRealAndTheKeyAndTokenStayOwnerOnly() = runDesktopComposeUiTest(900, 700) {
        val d = daemon()
        d.takesCode()
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "working", title = "Tune the encoder")))
        setContent { App(shell, reduced = true) }

        onNodeWithText(Ui.onb_pair.text()).assertIsNotEnabled()
        onNodeWithContentDescription(Ui.desk_pair_field.text()).performTextInput(linkOf(d))
        onNodeWithText(Ui.onb_pair.text()).assertIsEnabled().performClick()

        until("the session on screen") { Core.live?.link?.state == LinkState.Connected && Core.live?.sessions?.isNotEmpty() == true }
        until("the certificate presented on the socket") {
            d.issued.isNotEmpty() && d.calls("GET", "/v1/ws").any { it.overLan && it.clientCert?.encoded?.contentEquals(d.issued.last().encoded) == true }
        }
        waitForIdle()
        onNodeWithText("Tune the encoder").assertExists()
        assertEquals("the spent code left the field", "", shell.link)

        // The code went over the pinned listener, with nothing to show who it is yet.
        val pair = d.calls("POST", "/v1/pair").single()
        assertTrue(pair.overLan)
        assertNull(pair.auth)
        assertEquals("c0de-1234", pair.json.getString("code"))
        assertEquals(platform.device.name, pair.json.getString("deviceName"))
        assertTrue(pair.json.getString("model").startsWith("Desktop"))
        // The certificate is for this platform's own key.
        val issued = d.issued.last()
        assertEquals(platform.deviceKeys.ensure().first.encoded.toList(), issued.publicKey.encoded.toList())
        assertEquals("CN=dev-z2", issued.subjectX500Principal.name)
        val route = ActiveRoutes.route(Core.pairing!!.baseUrl)
        assertEquals(RouteKind.Local, route.kind)
        assertEquals(d.fingerprint, route.fingerprint)
        assertNotNull(DeviceCertStore(platform.storage, platform.deviceKeys, Core.activeId!!).identity())

        // Everything after it is on the file system as it should be.
        platform.folder.flush()
        val perms = { p: java.nio.file.Path -> PosixFilePermissions.toString(Files.getPosixFilePermissions(p)) }
        assertEquals("rw-------", perms(platform.keyFile))
        val prefs = DesktopRig.home.resolve("prefs").toFile().listFiles()!!
        assertTrue(prefs.isNotEmpty())
        for (f in prefs) assertEquals(f.name, "rw-------", perms(f.toPath()))
        assertTrue("the token's in those", prefs.any { it.readText().contains("device-token-z2") })
        assertEquals("rwx------", perms(DesktopRig.home))

        // A restart finds it paired: what Core reads as it starts.
        val again = PairingStore(DesktopStorage(DesktopRig.home, platform.log)).servers.load().first
        assertEquals(listOf("device-token-z2"), again.map { it.pairing.token })
        assertEquals(listOf(d.fingerprint), again.map { it.pairing.fingerprint })
    }

    @Test fun aListenerWithAnotherCertificateGetsNothing() = runDesktopComposeUiTest(900, 700) {
        val d = daemon()
        d.takesCode()
        setContent { App(shell, reduced = true) }
        // A link that names some other certificate: this listener isn't that server.
        val wrongPin = Pin.sha256Hex(byteArrayOf(1, 2, 3))
        main { shell.link = linkOf(d, fp = wrongPin); shell.submit() }
        until("it to fail") { shell.pair is PairState.Failed }
        waitForIdle()
        assertTrue("no code went to it", d.calls("POST", "/v1/pair").isEmpty())
        assertNull("nothing paired", Core.pairing)
        onNodeWithText(Ui.pair_failed.format("z2")).assertExists()
    }

    @Test fun plainHttpIsRefusedAsAPhonesConfigRefusesItAndNothingIsSent() = runDesktopComposeUiTest(900, 700) {
        val d = FakeDaemon("z2").also { daemons += it }
        d.takesCode()
        setContent { App(shell, reduced = true) }
        main { shell.link = "hypernova://pair?v=1&u=${d.base}&c=c0de&n=z2"; shell.submit() }
        until("it to fail") { shell.pair is PairState.Failed }
        waitForIdle()
        assertTrue("the code never left in the clear", d.requests.isEmpty())
        assertNull(Core.pairing)
        onNodeWithText(platform.strings.text(com.prudnikweb.hypernova.data.Str.problem_cleartext)).assertExists()
    }

    @Test fun aPlainHttpBaseBehindASilentListenerIsNotTheWayIn() = runDesktopComposeUiTest(900, 700) {
        val plain = FakeDaemon("plain").also { daemons += it }
        plain.takesCode()
        val d = daemon()
        // Its listener is gone: the phone's pairing then tries the link's `u`.
        val fp = d.fingerprint
        val port = d.lanPort
        d.close()
        setContent { App(shell, reduced = true) }
        main { shell.link = "hypernova://pair?v=1&u=${plain.base}&c=c0de&n=z2&fp=$fp&lan=127.0.0.1&lp=$port"; shell.submit() }
        until("it to fail", timeoutMs = 30_000) { shell.pair is PairState.Failed }
        assertTrue(plain.requests.isEmpty())
        assertNull(Core.pairing)
    }

    @Test fun aSpentCodeSaysSoAndHowToGetAnother() = runDesktopComposeUiTest(900, 700) {
        val d = daemon()
        d.on("POST", "/v1/pair") { refusal(403, "code_expired", "This pairing code has expired or was already used.") }
        setContent { App(shell, reduced = true) }
        main { shell.link = linkOf(d); shell.submit() }
        until("it to fail") { shell.pair is PairState.Failed }
        waitForIdle()
        onNodeWithText(Ui.pair_expired.text()).assertExists()
        onNodeWithText(Ui.desk_expired_body.text()).assertExists()
        // Back: the field again, the words still there to fix.
        onNodeWithText(Ui.pair_back.text()).performClick()
        waitForIdle()
        onNodeWithContentDescription(Ui.desk_pair_field.text()).assertExists()
    }

    @Test fun aServerNotShowingACodeSaysSo() = runDesktopComposeUiTest(900, 700) {
        val d = daemon()
        d.on("POST", "/v1/pair") { refusal(403, "pairing_closed", "No code is being shown.") }
        setContent { App(shell, reduced = true) }
        main { shell.link = linkOf(d); shell.submit() }
        until("it to fail") { shell.pair is PairState.Failed }
        waitForIdle()
        onNodeWithText(Ui.pair_closed.text()).assertExists()
    }

    @Test fun wordsThatArentALinkAreSaidToBeThat() = runDesktopComposeUiTest(900, 700) {
        setContent { App(shell, reduced = true) }
        main { shell.link = "good morning"; shell.submit() }
        waitForIdle()
        onNodeWithText(Ui.pair_bad_link.text()).assertExists()
        onNodeWithText(Ui.desk_bad_link_body.text()).assertExists()
        assertFalse(Core.servers.isNotEmpty())
    }

    @Test fun aPastedFileIsntRunThroughTheLinkParser() = runDesktopComposeUiTest(900, 700) {
        setContent { App(shell, reduced = true) }
        main { shell.link = "x".repeat(5_000); shell.submit() }
        waitForIdle()
        onNodeWithText(Ui.desk_pair_too_long.text()).assertExists()
    }

    @Test fun aSecondServerJoinsTheFirstAndTheSwitcherGoesBetweenThem() = runDesktopComposeUiTest(900, 700) {
        val a = daemon("alpha")
        val b = daemon("beta")
        a.snapshot = snapshot("alpha", sessions = listOf(session("a1", state = "working", title = "On alpha")))
        b.snapshot = snapshot("beta", sessions = listOf(session("b1", state = "working", title = "On beta")))
        setContent { App(shell, reduced = true) }
        with(DesktopRig) { pairedWith(a) }
        waitForIdle()
        onNodeWithText(Ui.desk_pair_another.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.onb_title_add.text()).assertExists()
        with(DesktopRig) { pairedWith(b) }
        until("beta's session") { Core.live?.sessions?.any { it.name == "On beta" } == true }
        waitForIdle()
        assertEquals(2, Core.servers.size)
        onNodeWithText("On beta").assertExists()
        // The first pill is the other server.
        onNodeWithText("alpha").performClick()
        until("alpha on screen") { Core.live?.mac?.name == "alpha" }
        until("alpha's session") { Core.live?.sessions?.any { it.name == "On alpha" } == true }
        waitForIdle()
        onNodeWithText("On alpha").assertExists()
    }
}
