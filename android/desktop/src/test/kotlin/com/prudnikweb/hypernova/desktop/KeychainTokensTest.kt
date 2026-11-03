package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.DebugScrub
import com.prudnikweb.hypernova.data.Log
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.PairLink
import com.prudnikweb.hypernova.data.Prefs
import com.prudnikweb.hypernova.data.Server
import com.prudnikweb.hypernova.data.Servers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Each paired server's token on its way to the keychain and back, on real files, with the keychain stood in for ([StandShelf]).
 * The list goes through the settings as the data layer writes it. The real Secret Service is in scripts/test-client-package.sh.
 */
class KeychainTokensTest {
    @get:Rule val temp = TemporaryFolder()

    private val said = CopyOnWriteArrayList<String>()
    private val log = object : Log {
        override fun d(tag: String, message: String) { said += message }
        override fun w(tag: String, message: String) { said += message }
    }
    private val shelf = StandShelf()
    private val home: Path by lazy { temp.root.toPath().resolve("home") }
    private val open = ArrayList<DesktopStorage>()

    /** One start of the app, as far as the tokens go. Each is given back with what it restored. */
    private inner class Run(choice: KeychainChoice = shelf.choice()) {
        val tokens = DesktopTokens(choice, log)
        val storage = DesktopStorage(home, log, mapOf(TOKENS_PREFS to tokens)).also { open += it }
        val keys = DesktopKeys(home.resolve("device-key.json"), log, choice)
        val back = CopyOnWriteArrayList<Server>()
        val prefs: Prefs get() = storage.prefs(TOKENS_PREFS)

        init {
            tokens.attach(storage, keys) { it() }
            tokens.restore = { list, _, _ -> back += list }
            tokens.prepare()
        }

        /** What the app reads. */
        fun list(): List<Server> = Servers.decode(prefs.getString("servers", null) ?: "[]")

        /** What the data layer does to save its list. */
        fun save(vararg servers: Server) {
            prefs.edit().putString("servers", Servers.encode(servers.toList())).apply()
            storage.flush()
        }

        fun end() {
            storage.flush()
            storage.close()
            open -= storage
        }
    }

    @After fun closeAll() = open.forEach { it.close() }

    private fun server(id: String, token: String, name: String = "srv-$id") = Server(id, Pairing("https://$name", token, name, "m-$name", "d-$name"))

    /** A folder from before this: the token plain in the settings, as the first run with no keychain leaves it. */
    private fun anInstallFromBefore(vararg servers: Server) {
        val r = Run(KeychainChoice.OFF)
        r.save(*servers)
        assertTrue("that's a plain token", Files.readString(home.resolve("prefs/hypernova.json")).contains(servers.first().pairing.token))
        r.end()
    }

    // ─── An install that already has a plain token ───

    @Test fun anExistingTokenMovesAndGoesOnlyOnceItCameBack() {
        val before = server("a1", "hn_tokenOne")
        anInstallFromBefore(before)
        var plainAtReadBack: Boolean? = null
        shelf.onRead = { plainAtReadBack = Files.readString(home.resolve("prefs/hypernova.json")).contains("hn_tokenOne") }

        val r = Run()

        assertEquals("the file still had it when it was read back", true, plainAtReadBack)
        assertEquals("hn_tokenOne", shelf.token("a1"))
        assertEquals(TokenPlace.InKeychain, r.tokens.place)
        assertEquals("the app still reads it", "hn_tokenOne", r.list().single().pairing.token)
        TokenDisk.noFileHolds(home, "hn_tokenOne")
        assertTrue(TokenDisk.servers(home).single().getBoolean("tokenInKeychain"))
        assertEquals("the rest of the entry stays", "srv-a1", TokenDisk.servers(home).single().getString("macName"))
        r.end()

        // A restart reads it back, and doesn't write it again.
        val writes = shelf.writes.size
        val again = Run()
        assertEquals("hn_tokenOne", again.list().single().pairing.token)
        assertEquals(TokenPlace.InKeychain, again.tokens.place)
        assertEquals(writes, shelf.writes.size)
        TokenDisk.noFileHolds(home, "hn_tokenOne")
    }

    @Test fun aKeychainThatRefusesOrLosesOrGarblesItLeavesThePlainCopy() {
        val before = server("a1", "hn_tokenOne")
        anInstallFromBefore(before)
        val text = Files.readString(home.resolve("prefs/hypernova.json"))
        fun tryIt(why: FileWhy, set: () -> Unit, undo: () -> Unit) {
            set()
            val r = Run()
            assertEquals("hn_tokenOne", r.list().single().pairing.token)
            assertEquals(TokenPlace.InFile(why), r.tokens.place)
            r.end()
            assertEquals(JSONObject(text).toString(), TokenDisk.settings(home).toString())
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("prefs/hypernova.json"))))
            undo()
        }
        tryIt(FileWhy.Down, { shelf.refuses = true }, { shelf.refuses = false })
        tryIt(FileWhy.DidNotHold, { shelf.loses = true }, { shelf.loses = false })
        tryIt(FileWhy.DidNotHold, { shelf.garbled = "not the token" }, { shelf.garbled = null })
        tryIt(FileWhy.Down, { shelf.locked = true }, { shelf.locked = false })
        // Back: it moves.
        val r = Run()
        assertEquals(TokenPlace.InKeychain, r.tokens.place)
        TokenDisk.noFileHolds(home, "hn_tokenOne")
    }

    @Test fun aHalfWrittenFileLeftBesideItGoesWithTheMove() {
        anInstallFromBefore(server("a1", "hn_tokenOne"))
        val left = home.resolve("prefs/hypernova.json123.tmp")
        Files.writeString(left, "{\"token\":\"hn_tokenOne\"")
        Run()
        assertFalse(Files.exists(left))
        TokenDisk.noFileHolds(home, "hn_tokenOne")
    }

    // ─── A new pairing ───

    @Test fun aNewTokenNeverTouchesTheDisk() {
        val r = Run()
        r.save(server("a1", "hn_new"))
        assertEquals("hn_new", shelf.token("a1"))
        assertEquals(TokenPlace.InKeychain, r.tokens.place)
        TokenDisk.noFileHolds(home, "hn_new")
        assertEquals("the app has it", "hn_new", r.list().single().pairing.token)
        // Every later save of the list is only that: no helper, no prompt.
        val writes = shelf.writes.size
        val reads = shelf.reads.size
        r.save(server("a1", "hn_new"))
        assertEquals(writes, shelf.writes.size)
        assertEquals(reads, shelf.reads.size)
    }

    @Test fun aNewTokenWithTheKeychainLockedIsInTheFileAndMovesWhenItIsBack() {
        shelf.locked = true
        val r = Run()
        r.save(server("a1", "hn_new"))
        assertEquals(TokenPlace.InFile(FileWhy.Down), r.tokens.place)
        assertEquals("hn_new", TokenDisk.servers(home).single().getString("token"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("prefs/hypernova.json"))))
        // Not asked over and over in one run.
        val writes = shelf.writes.size
        r.save(server("a1", "hn_new"))
        assertEquals(writes, shelf.writes.size)

        // Unlocked, and Try again: moved without a restart.
        shelf.locked = false
        r.tokens.retry()
        r.storage.flush()
        assertEquals("hn_new", shelf.token("a1"))
        assertEquals(TokenPlace.InKeychain, r.tokens.place)
        TokenDisk.noFileHolds(home, "hn_new")
    }

    @Test fun aNewTokenWithTheKeychainLockedMovesAtTheNextStartToo() {
        shelf.locked = true
        Run().apply { save(server("a1", "hn_new")); end() }
        shelf.locked = false
        val r = Run()
        assertEquals("hn_new", shelf.token("a1"))
        assertEquals(TokenPlace.InKeychain, r.tokens.place)
        TokenDisk.noFileHolds(home, "hn_new")
    }

    // ─── Locked at the start, with the token inside ───

    @Test fun lockedAtTheStartNothingIsDiscardedOverwrittenOrWritten() {
        Run().apply { save(server("a1", "hn_good")); end() }
        val onDisk = Files.readString(home.resolve("prefs/hypernova.json"))
        shelf.locked = true
        val writes = shelf.writes.size

        val r = Run()

        assertTrue("the app sees no server to dial with a token it hasn't got", r.list().isEmpty())
        assertEquals(listOf("a1"), r.tokens.waiting.map { it.id })
        assertEquals(WaitWhy.Locked, r.tokens.waiting.single().why)
        assertEquals("srv-a1", r.tokens.waiting.single().name)
        assertEquals("the keychain's left alone", "hn_good", shelf.token("a1"))
        assertEquals(writes, shelf.writes.size)
        assertEquals("the file as it was", onDisk, Files.readString(home.resolve("prefs/hypernova.json")))

        // Saves meanwhile, of everything else, keep it: its entry, in its place, with nothing written to its item.
        r.prefs.edit().putString("other", "setting").apply()
        r.save()
        r.save(server("b2", "hn_two"))
        val listed = TokenDisk.servers(home)
        assertEquals(listOf("a1", "b2"), listed.map { it.getString("id") })
        assertTrue(listed[0].getBoolean("tokenInKeychain"))
        assertFalse(listed[0].has("token"))
        assertEquals("hn_good", shelf.token("a1"))
        assertEquals(TokenPlace.InFile(FileWhy.Down), r.tokens.place)
        assertEquals("and it isn't forgotten", emptyList<String>(), shelf.deletes.filter { it.contains("a1") })

        // Still locked: try again says the same, and asks once.
        r.tokens.retry()
        r.storage.flush()
        assertEquals(listOf("a1"), r.tokens.waiting.map { it.id })
        assertFalse(r.tokens.retrying)
        assertTrue(r.back.isEmpty())

        // Unlocked: it's back, the same token, and what was in the file moves over.
        shelf.locked = false
        r.tokens.retry()
        r.storage.flush()
        assertTrue(r.tokens.waiting.isEmpty())
        assertEquals("hn_good", r.back.single().pairing.token)
        assertEquals("the other's moved", "hn_two", shelf.token("b2"))
        // The window's list has it now: it saves that.
        r.save(r.back.single(), server("b2", "hn_two"))
        assertEquals(listOf("a1", "b2"), TokenDisk.servers(home).map { it.getString("id") })
        TokenDisk.noFileHolds(home, "hn_good", "hn_two")
        assertEquals(TokenPlace.InKeychain, r.tokens.place)
        r.end()

        val again = Run()
        assertEquals(listOf("hn_good", "hn_two"), again.list().map { it.pairing.token })
    }

    @Test fun lockedThenRestartedStillHoldsIt() {
        Run().apply { save(server("a1", "hn_good")); end() }
        shelf.locked = true
        Run().apply { assertEquals(1, tokens.waiting.size); end() }
        Run().apply { assertEquals(1, tokens.waiting.size); end() }
        shelf.locked = false
        assertEquals("hn_good", Run().list().single().pairing.token)
    }

    @Test fun aKeychainThatSaysNothingIsThereIsntTakenAtItsWord() {
        Run().apply { save(server("a1", "hn_good")); end() }
        val held = HashMap(shelf.held)
        shelf.held.clear()
        val r = Run()
        assertEquals(WaitWhy.Gone, r.tokens.waiting.single().why)
        assertTrue("the entry stays", TokenDisk.servers(home).single().getBoolean("tokenInKeychain"))
        r.end()
        // Back after all (it was only locked, on a keychain that can't say, or restored).
        shelf.held.putAll(held)
        val again = Run()
        assertEquals("hn_good", again.list().single().pairing.token)
    }

    @Test fun onlyTheOwnersOwnForgetEndsAWait() {
        val r0 = Run().apply { save(server("a1", "hn_good"), server("b2", "hn_two")); end() }
        check(r0.tokens.waiting.isEmpty())
        shelf.locked = true
        val r = Run()
        r.tokens.forget("a1")
        r.storage.flush()
        assertEquals(listOf("b2"), r.tokens.waiting.map { it.id })
        assertEquals(listOf("b2"), TokenDisk.servers(home).map { it.getString("id") })
        assertEquals("it can't be told yet", "hn_good", shelf.token("a1"))
        assertEquals("a1", TokenDisk.settings(home).getString("tokensToRemove"))
        r.end()

        // It goes once the keychain answers, and the other's untouched.
        shelf.locked = false
        val back = Run()
        assertNull(shelf.token("a1"))
        assertEquals("hn_two", shelf.token("b2"))
        assertFalse(TokenDisk.settings(home).has("tokensToRemove"))
        assertEquals(listOf("hn_two"), back.list().map { it.pairing.token })
    }

    @Test fun pairingTheSameComputerAgainWhileItWaitedReplacesIt() {
        Run().apply { save(server("a1", "hn_old", name = "same")); end() }
        shelf.locked = true
        val r = Run()
        assertEquals(1, r.tokens.waiting.size)
        r.save(server("c3", "hn_again", name = "same"))
        assertTrue(r.tokens.waiting.isEmpty())
        assertEquals(listOf("c3"), TokenDisk.servers(home).map { it.getString("id") })
        assertEquals("hn_again", TokenDisk.servers(home).single().getString("token"))
        r.end()
        shelf.locked = false
        val back = Run()
        assertNull(shelf.token("a1"))
        assertEquals("hn_again", shelf.token("c3"))
        TokenDisk.noFileHolds(home, "hn_again")
        assertEquals("hn_again", back.list().single().pairing.token)
    }

    @Test fun aNewTokenInTheFileWinsOverTheOneInTheKeychain() {
        Run().apply { save(server("a1", "hn_old")); end() }
        shelf.locked = true
        // Paired again, same place, with the keychain locked.
        Run().apply { save(server("a1", "hn_new")); end() }
        assertEquals("hn_old", shelf.token("a1"))
        assertEquals("hn_new", TokenDisk.servers(home).single().getString("token"))
        shelf.locked = false
        val r = Run()
        assertEquals("hn_new", shelf.token("a1"))
        assertEquals("hn_new", r.list().single().pairing.token)
        TokenDisk.noFileHolds(home, "hn_new", "hn_old")
    }

    // ─── Removing a server, and several ───

    @Test fun twoServersEachKeepTheirOwnAndForgetRemovesOnlyThatItem() {
        val r = Run()
        val a = server("a1", "hn_one")
        val b = server("b2", "hn_two")
        r.save(a, b)
        assertEquals("hn_one", shelf.token("a1"))
        assertEquals("hn_two", shelf.token("b2"))
        assertEquals(2, shelf.tokenItems.size)
        TokenDisk.noFileHolds(home, "hn_one", "hn_two")

        r.save(b)
        assertNull("Forget takes its item", shelf.token("a1"))
        assertEquals("hn_two", shelf.token("b2"))
        r.end()

        val again = Run()
        assertEquals(listOf("hn_two"), again.list().map { it.pairing.token })
        assertEquals(1, shelf.tokenItems.size)
    }

    @Test fun forgetWhileLockedRemovesTheItemOnceItCan() {
        val r = Run()
        r.save(server("a1", "hn_one"))
        shelf.locked = true
        r.save()
        assertEquals("hn_one", shelf.token("a1"))
        assertEquals("a1", TokenDisk.settings(home).getString("tokensToRemove"))
        TokenDisk.noFileHolds(home, "hn_one")
        r.end()
        shelf.locked = false
        Run()
        assertNull(shelf.token("a1"))
        assertFalse(TokenDisk.settings(home).has("tokensToRemove"))
    }

    @Test fun theOnePairingKeptBeforeTheListMovesToo() {
        // What a phone's first build left, and the list made from it (Pairing.kt, Servers.kt).
        Files.createDirectories(home.resolve("prefs"))
        Files.writeString(home.resolve("prefs/hypernova.json"), JSONObject().put("baseUrl", "https://s").put("token", "hn_single").put("macName", "s").toString())
        val r = Run()
        // ServerBook's first load makes the list, then keeps the old keys beside it as it saves.
        val p = Server(Servers.LEGACY_ID, Pairing("https://s", "hn_single", "s", null, null))
        r.prefs.edit().putString("servers", Servers.encode(listOf(p))).putString("token", "hn_single").apply()
        r.storage.flush()
        assertEquals("hn_single", shelf.token(Servers.LEGACY_ID))
        assertEquals("the app can still read the old key", "hn_single", r.prefs.getString("token", null))
        TokenDisk.noFileHolds(home, "hn_single")
    }

    // ─── No keychain to use ───

    @Test fun offIsTheFileAndSaysSoAndNeverAsks() {
        val r = Run(KeychainChoice.OFF)
        r.save(server("a1", "hn_one"))
        assertEquals(TokenPlace.InFile(FileWhy.TurnedOff), r.tokens.place)
        assertEquals("hn_one", TokenDisk.servers(home).single().getString("token"))
        assertTrue(shelf.writes.isEmpty() && shelf.reads.isEmpty())
    }

    @Test fun theEnvironmentsOffForcesTheFileEvenWithAKeychainHolding() {
        val choice = Keychains.forThisSystem(home, { if (it == Keychains.ENV) "off" else null }, "Linux")
        assertNull(choice.item("anything"))
        assertEquals(FileWhy.TurnedOff, choice.without)
        // Moved earlier, and now turned off: it isn't asked, and it isn't lost.
        Run().apply { save(server("a1", "hn_one")); end() }
        val r = Run(choice)
        assertEquals(WaitWhy.TurnedOff, r.tokens.waiting.single().why)
        assertEquals("hn_one", shelf.token("a1"))
        assertTrue(TokenDisk.servers(home).single().getBoolean("tokenInKeychain"))
    }

    @Test fun noHelperSaysSoToo() {
        Run().apply { save(server("a1", "hn_one")); end() }
        val r = Run(KeychainChoice(null, FileWhy.NoHelper))
        assertEquals(WaitWhy.NoHelper, r.tokens.waiting.single().why)
    }

    // ─── Items ───

    @Test fun eachFolderAndEachServerHasItsOwnItem() {
        val a = temp.root.toPath().resolve("a")
        val b = temp.root.toPath().resolve("b")
        val item = Keychains.tokenItem("srv1")
        assertEquals(Keychains.accountOf(a, item), Keychains.accountOf(a.resolve("x/.."), item))
        assertEquals(setOf(32), setOf(Keychains.accountOf(a).length, Keychains.accountOf(a, item).length))
        val all = listOf(Keychains.accountOf(a), Keychains.accountOf(a, item), Keychains.accountOf(b, item), Keychains.accountOf(a, Keychains.tokenItem("srv2")))
        assertEquals("all different", all.size, all.toSet().size)
        // The key's own item didn't change with this: a client paired already finds it.
        assertEquals(Keychains.accountOf(a), Keychains.accountOf(a, null))
    }

    @Test fun whichItemsThisComputerHas() {
        val tool = temp.newFolder("bin").toPath().resolve("secret-tool")
        Files.writeString(tool, "#!/bin/sh\n")
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwx------"))
        fun env(vararg kv: Pair<String, String>): (String) -> String? = { kv.toMap()[it] }
        val item = Keychains.tokenItem("srv1")
        assertTrue(Keychains.forThisSystem(home, env("PATH" to tool.parent.toString()), "Linux").item(item) is SecretServiceKeychain)
        assertTrue(Keychains.forThisSystem(home, env(), "Mac OS X").item(item) is MacKeychain)
        assertTrue(Keychains.forThisSystem(home, env(), "Windows 11").item(item) is WindowsDpapiKeychain)
        assertNull(Keychains.forThisSystem(home, env("PATH" to "/nowhere"), "Linux").item(item))
        assertNull(Keychains.forThisSystem(home, env(Keychains.ENV to "off"), "Windows 11").item(item))
    }

    @Test fun onLinuxATokensItemIsLabelledAsOne() {
        val runs = ArrayList<List<String>>()
        SecretServiceKeychain("acct", { argv, _, _ ->
            runs += argv
            HelperResult(0, "", false)
        }, label = "Hypernova Client's sign-in token").write("hn_x")
        assertEquals(listOf("secret-tool", "store", "--label=Hypernova Client's sign-in token", "service", "hypernova-client", "account", "acct"), runs.single())
    }

    @Test fun anIdFromTheSettingsFileCantBecomeACommand() {
        val runs = ArrayList<Pair<List<String>, String?>>()
        val helper = Helper { argv, input, _ ->
            runs += argv to input
            HelperResult(0, "t\n", false)
        }
        val evil = "x\ndelete-keychain login.keychain"
        val account = Keychains.accountOf(home, Keychains.tokenItem(evil))
        assertTrue(account.all { it in '0'..'9' || it in 'a'..'f' })
        MacKeychain(account, helper).write("hn_one")
        assertEquals("one line, one command", 1, runs.single().second!!.count { it == '\n' })
    }

    /** `randomToken(32)` in daemon/src/http.ts. Mac's `security -i` reads lines, so that one is what could be short of room. */
    @Test fun aTokenFitsEachSystemsWay() {
        val token = "hn_" + "A".repeat(4096)
        // Secret Service: on standard input, whole, with no line to run out of. A real one takes this much in test-client-package.sh.
        val runs = ArrayList<Pair<List<String>, String?>>()
        val helper = Helper { argv, input, _ ->
            runs += argv to input
            HelperResult(0, input.orEmpty(), false)
        }
        SecretServiceKeychain("acct", helper, label = "x").write(token)
        assertEquals(token, runs.last().second)

        // DPAPI: base64 on standard input.
        val sealed = temp.root.toPath().resolve("s.dpapi")
        WindowsDpapiKeychain(sealed, helper, "D:\\Win").write(token)
        assertEquals(token, String(Base64.getDecoder().decode(runs.last().second)))

        // A real token, hex on one line: well under the 4,096 bytes `security -i` is remembered to read (from memory, not checked:
        // that's why a token only leaves the file after coming back whole, which is what happens to one too long for a line).
        val real = "hn_" + "A".repeat(64)
        MacKeychain(Keychains.accountOf(home, Keychains.tokenItem("0123456789ab")), helper).write(real)
        assertTrue("${runs.last().second!!.length}", runs.last().second!!.length < 512)
    }

    @Test fun onAMacAndOnWindowsDeletingSaysWhetherItWent() {
        fun exiting(code: Int) = Helper { _, _, _ -> HelperResult(code, "", code != 0) }
        assertTrue(MacKeychain("a", exiting(0)).delete())
        assertTrue("nothing there is nothing left", MacKeychain("a", exiting(44)).delete())
        assertFalse("a locked one's still there", MacKeychain("a", exiting(36)).delete())
        assertFalse(MacKeychain("a", { _, _, _ -> null }).delete())
        val f = temp.root.toPath().resolve("sealed.dpapi")
        Files.writeString(f, "x")
        assertTrue(WindowsDpapiKeychain(f, exiting(0), "D:\\Win").delete())
        assertFalse(Files.exists(f))
    }

    @Test fun onLinuxDeletingALockedItemDoesntCountAsGone() {
        // `clear` exits 1 saying nothing both for no item and for a locked one (seen in a container).
        fun tool(listed: String) = Helper { argv, _, _ -> if (argv[1] == "search") HelperResult(0, listed, false) else HelperResult(1, "", false) }
        assertTrue(SecretServiceKeychain("a", tool("")).delete())
        assertFalse(SecretServiceKeychain("a", tool("[/1]\n")).delete())
        assertTrue(SecretServiceKeychain("a", { _, _, _ -> HelperResult(0, "", false) }).delete())
        assertFalse(SecretServiceKeychain("a", { _, _, _ -> null }).delete())
    }

    // ─── Never in a log ───

    @Test fun aTokenIsInNothingItSaysNorWhenAKeychainQuotesIt() {
        anInstallFromBefore(server("a1", "hn_secretTokenOne"))
        shelf.boom = "failed on hn_secretTokenOne"
        Run().end()
        shelf.boom = null
        shelf.garbled = "hn_secretTokenOne garbled"
        Run().end()
        shelf.garbled = null
        val r = Run()
        r.save(server("a1", "hn_secretTokenOne"), server("b2", "hn_secretTokenTwo"))
        r.save()
        assertTrue(said.isNotEmpty())
        for (w in said) assertFalse(w, w.contains("hn_secretToken"))
    }

    @Test fun whatHoldsATokenCantPrintIt() {
        val secret = "hn_secretTokenOne"
        val r = Run()
        val p = Pairing("https://s", secret, "s", null, null)
        val inMemory = listOf(p, Server("a", p), PairLink("https://s", "c0de-secret", "s"), KeychainRead.Found(secret), HelperResult(0, secret, false), r.tokens, WaitingServer("a", "s", WaitWhy.Locked))
        for (x in inMemory) {
            assertFalse("$x", x.toString().contains(secret))
            assertFalse("$x", x.toString().contains("c0de-secret"))
        }
    }

    @Test fun eachTokenIsCutFromTheDebugLogFromTheMomentItsRead() {
        Run().apply { save(server("a1", "Zq3xV8pLm2")); end() }
        Run()
        assertFalse(DebugScrub.line("the header was Zq3xV8pLm2, oops").contains("Zq3xV8pLm2"))
    }

    @Test fun aHelpersCommandLineNeverHoldsIt() {
        val runs = ArrayList<List<String>>()
        val helper = Helper { argv, _, _ ->
            runs += argv
            HelperResult(0, "hn_secretTokenOne\n", false)
        }
        val account = Keychains.accountOf(home, Keychains.tokenItem("a1"))
        for (k in listOf(SecretServiceKeychain(account, helper), MacKeychain(account, helper), WindowsDpapiKeychain(temp.root.toPath().resolve("x.dpapi"), helper, "D:\\Win"))) {
            k.write("hn_secretTokenOne")
            k.read()
            k.delete()
        }
        val hex = "hn_secretTokenOne".toByteArray().joinToString("") { "%02x".format(it) }
        for (argv in runs) {
            assertFalse(argv.toString(), argv.joinToString(" ").contains("secretToken"))
            assertFalse(argv.toString(), argv.joinToString(" ").contains(hex))
        }
    }
}
