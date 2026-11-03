package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.DebugScrub
import com.prudnikweb.hypernova.data.DeviceCertStore
import com.prudnikweb.hypernova.data.DeviceKeys
import com.prudnikweb.hypernova.data.Log
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The key's way to the keychain and back, on real files, with the keychain stood in for ([Stand]). The real Secret Service
 * is in scripts/test-client-package.sh, a throwaway one in a container or on CI. What's sent to a Mac's and Windows'
 * programs is checked here against stand-ins too, which proves what's sent and nothing about what they do with it.
 */
class KeychainTest {
    @get:Rule val temp = TemporaryFolder()

    private val said = CopyOnWriteArrayList<String>()
    private val log = object : Log {
        override fun d(tag: String, message: String) { said += message }
        override fun w(tag: String, message: String) { said += message }
    }

    private class Stand : Keychain {
        var held: String? = null
        var down = false
        var refuses = false
        var loses = false
        /** What it gives back in place of what it holds. */
        var garbled: String? = null
        var boom: String? = null
        var reads = 0
        var writes = 0
        var onRead: () -> Unit = {}

        override fun read(): KeychainRead {
            reads++
            onRead()
            boom?.let { throw IllegalStateException(it) }
            if (down) return KeychainRead.Down
            garbled?.let { return KeychainRead.Found(it) }
            if (loses) return KeychainRead.Missing
            return held?.let { KeychainRead.Found(it) } ?: KeychainRead.Missing
        }

        override fun write(text: String): Boolean {
            writes++
            boom?.let { throw IllegalStateException(it) }
            if (down || refuses) return false
            held = text
            return true
        }

        override fun delete(): Boolean {
            held = null
            return true
        }
    }

    private val stand = Stand()
    private val file: Path by lazy { temp.root.toPath().resolve("device-key.json") }

    private fun onFile() = DesktopKeys(file, log)
    private fun withKeychain() = DesktopKeys(file, log, KeychainChoice(stand))
    private fun same(a: PrivateKey?, b: PrivateKey?) = assertArrayEquals(a?.encoded, b?.encoded)

    private fun signs(priv: PrivateKey, pub: PublicKey) {
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(priv); update(byteArrayOf(9)) }.sign()
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(byteArrayOf(9)) }.verify(sig))
    }

    private fun secretOf(text: String) = JSONObject(text).getString("private")

    private fun noKeyIn(words: Collection<String>, secret: String) {
        for (w in words) for (piece in secret.chunked(24).filter { it.length == 24 }) assertFalse("key material in: $w", w.contains(piece))
    }

    // ─── An install that already has a file ───

    @Test fun anExistingFileMovesAndGoesOnlyOnceTheKeyCameBackAndSigned() {
        val before = onFile().ensure()
        val text = Files.readString(file)
        var fileThereAtReadBack: Boolean? = null
        stand.onRead = { fileThereAtReadBack = Files.exists(file) }

        val k = withKeychain()
        k.prepare()

        assertEquals("the file was still there when it was read back", true, fileThereAtReadBack)
        assertFalse("and it's gone now", Files.exists(file))
        assertEquals(KeyPlace.InKeychain, k.place)
        same(before.second, k.privateKey())
        assertArrayEquals("the same public half", before.first.encoded, k.publicKey()!!.encoded)
        signs(k.privateKey()!!, before.first)
        assertEquals("the very key", secretOf(text), secretOf(stand.held!!))

        // A restart finds it there, the file not coming back.
        val again = withKeychain()
        same(before.second, again.privateKey())
        assertEquals(KeyPlace.InKeychain, again.place)
        assertFalse(Files.exists(file))
    }

    @Test fun aKeychainThatRefusesLeavesTheFileAndSaysSo() {
        val before = onFile().ensure()
        val text = Files.readString(file)
        stand.refuses = true
        val k = withKeychain()
        same(before.second, k.privateKey())
        assertEquals(KeyPlace.InFile(FileWhy.Down), k.place)
        assertEquals("the file as it was", text, Files.readString(file))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        // Not asked over and over in one run.
        val writes = stand.writes
        k.privateKey(); k.ensure()
        assertEquals(writes, stand.writes)
    }

    @Test fun aKeychainThatGivesBackAnythingElseIsntTrustedWithIt() {
        val before = onFile().ensure()
        val text = Files.readString(file)
        val other = run {
            val f = temp.root.toPath().resolve("other.json")
            DesktopKeys(f, log).ensure()
            Files.readString(f)
        }
        for (back in listOf(other, "not json", """{"public":"AAAA","private":"AAAA"}""", "")) {
            stand.garbled = back
            val k = withKeychain()
            same(before.second, k.privateKey())
            assertEquals(back, KeyPlace.InFile(FileWhy.DidNotHold), k.place)
            assertEquals(back, text, Files.readString(file))
        }
    }

    @Test fun aKeychainThatTakesItAndThenCantFindItIsntTrustedEither() {
        val before = onFile().ensure()
        stand.loses = true
        val k = withKeychain()
        same(before.second, k.privateKey())
        assertEquals(KeyPlace.InFile(FileWhy.DidNotHold), k.place)
        assertTrue(Files.exists(file))
    }

    // ─── A new key ───

    @Test fun aNewKeyGoesStraightToTheKeychainAndNeverTouchesTheDisk() {
        val k = withKeychain()
        k.prepare()
        assertEquals("nothing yet", KeyPlace.Nowhere, k.place)
        assertNull(k.privateKey())
        val (pub, priv) = k.ensure()
        assertEquals(KeyPlace.InKeychain, k.place)
        assertFalse(Files.exists(file))
        signs(priv, pub)
        same(priv, withKeychain().privateKey())
    }

    @Test fun aNewKeyWithTheKeychainDownIsInTheFileAndWorks() {
        stand.down = true
        val k = withKeychain()
        val (pub, priv) = k.ensure()
        assertEquals(KeyPlace.InFile(FileWhy.Down), k.place)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        signs(priv, pub)
        assertEquals("asked once this run", 1, stand.reads)
        assertEquals(0, stand.writes)
    }

    // ─── The key moved, and then the keychain is locked ───

    @Test fun lockedAfterTheMoveItConnectsOnANewKeyAndThatKeyWinsLater() {
        val old = withKeychain().ensure()
        val oldInKeychain = stand.held
        stand.down = true

        // A run with it locked: the old key can't be had, so a certificate request makes a new one.
        val locked = withKeychain()
        locked.prepare()
        assertNull(locked.privateKey())
        assertEquals("Settings has nothing to say yet", KeyPlace.Nowhere, locked.place)
        val (newPub, newPriv) = locked.ensure()
        assertEquals(KeyPlace.InFile(FileWhy.Down), locked.place)
        assertNotEquals(old.first, newPub)
        assertEquals("the keychain's is left alone while it's locked", oldInKeychain, stand.held)
        signs(newPriv, newPub)

        // Unlocked again: the file is the newest, so it replaces the old key, and goes.
        stand.down = false
        val back = withKeychain()
        same(newPriv, back.privateKey())
        assertEquals(KeyPlace.InKeychain, back.place)
        assertFalse(Files.exists(file))
        assertNotEquals(oldInKeychain, stand.held)
        same(newPriv, withKeychain().privateKey())
    }

    @Test fun aCertificateForAnotherKeyIsntPresented() {
        DesktopStorage(temp.root.toPath().resolve("home"), log).use { storage ->
            val k = withKeychain()
            val (pub, priv) = k.ensure()
            val store = DeviceCertStore(storage, k, "srv")
            val b64 = { c: HeldCertificate -> Base64.getEncoder().encodeToString(c.certificate.encoded) }
            val ca = HeldCertificate.Builder().certificateAuthority(0).build()
            store.install(b64(HeldCertificate.Builder().keyPair(KeyPair(pub, priv)).signedBy(ca).build()), b64(ca), "fp")
            assertNotNull("with its own key", store.identity())

            // Locked at the next start, so another key: presenting this certificate on it could only fail the handshake.
            stand.down = true
            val locked = withKeychain()
            locked.ensure()
            assertNull("nothing presented, so the server asks, and the hub gets a new one", DeviceCertStore(storage, locked, "srv").identity())

            // A phone's keystore doesn't say, and nothing changes for it.
            val phone = object : DeviceKeys {
                override fun ensure() = pub to priv
                override fun privateKey() = priv
                override fun delete() = Unit
            }
            assertNotNull(DeviceCertStore(storage, phone, "srv").identity())
        }
    }

    @Test fun anUnreadableEntryIsAsGoodAsNoneAndIsReplaced() {
        stand.held = "not a key"
        val k = withKeychain()
        assertNull(k.privateKey())
        val (pub, priv) = k.ensure()
        signs(priv, pub)
        assertEquals(KeyPlace.InKeychain, k.place)
        same(priv, withKeychain().privateKey())
    }

    @Test fun aBrokenFileGivesWayToTheKeychainsKey() {
        val k = withKeychain().ensure()
        Files.writeString(file, "broken")
        val again = withKeychain()
        same(k.second, again.privateKey())
        assertEquals(KeyPlace.InKeychain, again.place)
        assertFalse(Files.exists(file))
    }

    @Test fun unpairingTakesItFromBoth() {
        val k = withKeychain()
        k.ensure()
        k.delete()
        assertNull(stand.held)
        assertEquals(KeyPlace.Nowhere, k.place)
        assertNull(k.privateKey())

        stand.down = true
        val f = withKeychain()
        f.ensure()
        assertTrue(Files.exists(file))
        f.delete()
        assertFalse("the file goes whatever the keychain says", Files.exists(file))
    }

    @Test fun withNoKeychainItsTheFileAndItSaysWhy() {
        val off = DesktopKeys(file, log, KeychainChoice.OFF)
        off.ensure()
        assertEquals(KeyPlace.InFile(FileWhy.TurnedOff), off.place)
        val none = DesktopKeys(file, log, KeychainChoice(null, FileWhy.NoHelper))
        none.prepare()
        assertEquals(KeyPlace.InFile(FileWhy.NoHelper), none.place)
    }

    // ─── Never in a log ───

    @Test fun theKeyIsNeverInWhatItSaysNorWhenAKeychainQuotesIt() {
        val secret = run {
            onFile().ensure()
            secretOf(Files.readString(file))
        }
        // A helper's failure that quotes what it was given.
        stand.boom = "failed on $secret"
        val k = withKeychain()
        assertNotNull(k.privateKey())
        assertEquals(KeyPlace.InFile(FileWhy.Down), k.place)
        stand.boom = null
        val moved = withKeychain()
        moved.prepare()
        assertEquals(KeyPlace.InKeychain, moved.place)
        assertTrue(said.isNotEmpty())
        noKeyIn(said, secret)
    }

    @Test fun theDebugLogsDoorCutsIt() {
        onFile().ensure()
        val text = Files.readString(file)
        val secret = secretOf(text)
        val cut = DebugScrub.line("the key: $text")
        noKeyIn(listOf(cut), secret)
        // Known by now, whatever shape a line makes of it.
        assertFalse(DebugScrub.line("x${secret}y").contains(secret))
        for (r in listOf(KeychainRead.Found(text), HelperResult(0, text, false))) assertFalse("no toString gives it away", r.toString().contains(secret))
    }

    // ─── What's handed to each system's program ───

    private class Calls(val answer: (List<String>, String?) -> HelperResult?) : Helper {
        val runs = mutableListOf<Pair<List<String>, String?>>()
        override fun run(command: List<String>, stdin: String?, timeoutMs: Long): HelperResult? {
            runs += command to stdin
            return answer(command, stdin)
        }
    }

    private val text = """{"public":"pub","private":"s3cret-Key"}"""

    private fun neverOnACommandLine(c: Calls) {
        for ((argv, _) in c.runs) assertFalse("on a command line: $argv", argv.joinToString(" ").contains("s3cret") || argv.joinToString(" ").contains(hex(text)))
    }

    private fun hex(s: String) = s.toByteArray().joinToString("") { "%02x".format(it) }

    @Test fun secretToolHasItOnStandardInputAndKnowsMissingFromDown() {
        val c = Calls { argv, _ -> if (argv[1] == "store") HelperResult(0, "", false) else HelperResult(0, "$text\n", false) }
        val k = SecretServiceKeychain("acct", c)
        assertTrue(k.write(text))
        assertEquals(listOf("secret-tool", "store", "--label=Hypernova Client's device key", "service", "hypernova-client", "account", "acct"), c.runs[0].first)
        assertEquals(text, c.runs[0].second)
        assertEquals(text, (k.read() as KeychainRead.Found).text)
        assertEquals(listOf("secret-tool", "lookup", "service", "hypernova-client", "account", "acct"), c.runs[1].first)
        neverOnACommandLine(c)

        // As it really does: libsecret's tool/secret-tool.c, and a container's gnome-keyring. `lookup` says the same for no item
        // and for a locked one, so `search --all` is what tells them apart.
        val listing = { out: String -> Calls { argv, _ -> if (argv[1] == "search") HelperResult(0, out, false) else HelperResult(1, "", false) } }
        assertEquals("nothing listed: there's none", KeychainRead.Missing, SecretServiceKeychain("a", listing("")).read())
        assertEquals("listed, with no secret: it's locked", KeychainRead.Down, SecretServiceKeychain("a", listing("[/1]\nlabel = \n")).read())
        val searched = listing("")
        SecretServiceKeychain("a", searched).read()
        assertEquals(listOf("secret-tool", "search", "--all", "service", "hypernova-client", "account", "a"), searched.runs[1].first)
        assertEquals("a search that can't say isn't a no", KeychainRead.Down, SecretServiceKeychain("a", Calls { _, _ -> HelperResult(1, "", false) }).read())
        assertEquals(KeychainRead.Down, SecretServiceKeychain("a", Calls { _, _ -> HelperResult(1, "", true) }).read())
        assertEquals(KeychainRead.Down, SecretServiceKeychain("a", Calls { _, _ -> null }).read())
        assertFalse(SecretServiceKeychain("a", Calls { _, _ -> HelperResult(1, "", true) }).write(text))
        assertFalse(SecretServiceKeychain("a", Calls { _, _ -> null }).write(text))
    }

    @Test fun aMacsSecurityHasItInACommandOnStandardInput() {
        val c = Calls { argv, _ -> if (argv[1] == "-i") HelperResult(0, "security> ", false) else HelperResult(0, "$text\n", false) }
        val k = MacKeychain("acct", c)
        assertTrue(k.write(text))
        assertEquals(listOf("/usr/bin/security", "-i"), c.runs[0].first)
        assertEquals("add-generic-password -U -s hypernova-client -a acct -X ${hex(text)}\n", c.runs[0].second)
        assertEquals(text, (k.read() as KeychainRead.Found).text)
        assertEquals(listOf("/usr/bin/security", "find-generic-password", "-s", "hypernova-client", "-a", "acct", "-w"), c.runs[1].first)
        neverOnACommandLine(c)

        assertEquals("errSecItemNotFound's low byte", KeychainRead.Missing, MacKeychain("a", Calls { _, _ -> HelperResult(44, "", true) }).read())
        assertEquals("errSecInteractionNotAllowed's", KeychainRead.Down, MacKeychain("a", Calls { _, _ -> HelperResult(36, "", true) }).read())
        assertEquals(KeychainRead.Down, MacKeychain("a", Calls { _, _ -> null }).read())
    }

    @Test fun windowsHasItOnStandardInputAndOnlyEverSealedOnDisk() {
        val b64 = Base64.getEncoder()
        val unb64 = Base64.getDecoder()
        // ROT by one stands in for DPAPI.
        val c = Calls { argv, input ->
            val by = if (argv.last().contains("]::Protect(")) 1 else -1
            HelperResult(0, b64.encodeToString(unb64.decode(input!!.trim()).map { (it + by).toByte() }.toByteArray()) + "\r\n", false)
        }
        val sealed = temp.root.toPath().resolve("device-key.dpapi")
        val k = WindowsDpapiKeychain(sealed, c, "D:\\Win")
        assertEquals("nothing there is missing, with no program run", KeychainRead.Missing, k.read())
        assertEquals(0, c.runs.size)
        assertTrue(k.write(text))
        assertEquals("by its whole path", "D:\\Win/System32\\WindowsPowerShell\\v1.0\\powershell.exe".replace('/', java.io.File.separatorChar), c.runs[0].first[0])
        assertFalse(String(Files.readAllBytes(sealed)).contains("s3cret"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(sealed)))
        assertEquals(text, (k.read() as KeychainRead.Found).text)
        neverOnACommandLine(c)
        k.delete()
        assertFalse(Files.exists(sealed))

        val failing = WindowsDpapiKeychain(sealed, Calls { _, _ -> HelperResult(1, "", true) }, "D:\\Win")
        assertFalse(failing.write(text))
        Files.writeString(sealed, "x")
        assertEquals("a login that can't undo it is down, not missing", KeychainRead.Down, failing.read())
    }

    @Test fun whichOneThisComputerHas() {
        val home = temp.root.toPath()
        val tool = temp.newFolder("bin").toPath().resolve("secret-tool")
        Files.writeString(tool, "#!/bin/sh\n")
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwx------"))
        fun env(vararg kv: Pair<String, String>): (String) -> String? = { kv.toMap()[it] }

        assertEquals(FileWhy.TurnedOff, Keychains.forThisSystem(home, env(Keychains.ENV to "OFF"), "Linux").without)
        assertNull(Keychains.forThisSystem(home, env(Keychains.ENV to "off"), "Mac OS X").keychain)
        assertTrue(Keychains.forThisSystem(home, env("PATH" to tool.parent.toString()), "Linux").keychain is SecretServiceKeychain)
        val none = Keychains.forThisSystem(home, env("PATH" to "/nowhere"), "Linux")
        assertNull(none.keychain)
        assertEquals(FileWhy.NoHelper, none.without)
        assertTrue(Keychains.forThisSystem(home, env(), "Mac OS X").keychain is MacKeychain)
        assertTrue(Keychains.forThisSystem(home, env(), "Windows 11").keychain is WindowsDpapiKeychain)

        // Each folder its own item: a test copy never sees the real client's key, or replaces it.
        assertNotEquals(Keychains.accountOf(home.resolve("a")), Keychains.accountOf(home.resolve("b")))
        assertEquals(Keychains.accountOf(home.resolve("a")), Keychains.accountOf(home.resolve("x/../a")))
        assertFalse("no path in a keychain's list", Keychains.accountOf(home).contains('/'))
    }

    // ─── The real process ───

    private fun onUnix() = assumeTrue(!System.getProperty("os.name").orEmpty().lowercase().startsWith("windows"))

    @Test fun aHelperIsGivenItsInputAndItsAnswerAndItsExit() {
        onUnix()
        val r = SystemHelper.run(listOf("sh", "-c", "cat; echo oops >&2; exit 3"), "hello", 10_000)!!
        assertEquals(3, r.code)
        assertEquals("hello", r.out)
        assertTrue(r.saidSomething)
        assertFalse(SystemHelper.run(listOf("sh", "-c", "exit 0"), null, 10_000)!!.saidSomething)
    }

    @Test fun oneThatDoesntFinishOrStartIsNoAnswer() {
        onUnix()
        val start = System.currentTimeMillis()
        assertNull(SystemHelper.run(listOf("sleep", "30"), null, 300))
        assertTrue("it didn't wait the 30 s", System.currentTimeMillis() - start < 10_000)
        assertNull(SystemHelper.run(listOf("/no/such/helper"), null, 1_000))
    }
}
