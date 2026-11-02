package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.Csr
import com.prudnikweb.hypernova.data.DeviceCertStore
import com.prudnikweb.hypernova.data.Log
import com.prudnikweb.hypernova.data.MainThread
import com.prudnikweb.hypernova.data.NetKind
import com.prudnikweb.hypernova.data.Ticks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The desktop's seams themselves, on real files in a temp folder. The modes are only checked where the system has them. */
class PlatformTest {
    @get:Rule val temp = TemporaryFolder()

    private val warnings = mutableListOf<String>()
    private val log = object : Log {
        override fun d(tag: String, message: String) = Unit
        override fun w(tag: String, message: String) { warnings += message }
    }

    private fun home(): Path = temp.root.toPath().resolve("home")
    private fun mode(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

    // ─── Where it keeps things ───

    @Test fun theFolderIsTheClientsOwnOnEverySystemAndNeverTheServers() {
        val none: (String) -> String? = { null }
        val linux = DataFolder.of(none, "Linux", "/home/me").path
        assertEquals("/home/me/.local/share/hypernova-client", linux)
        assertNotEquals("the server's folder, on a computer running both", "/home/me/.local/share/hypernova", linux)
        assertEquals("/data/hypernova-client", DataFolder.of({ if (it == "XDG_DATA_HOME") "/data" else null }, "Linux", "/home/me").path)
        assertEquals("a relative XDG_DATA_HOME is ignored, as its spec says", linux, DataFolder.of({ if (it == "XDG_DATA_HOME") "data" else null }, "Linux", "/home/me").path)
        assertEquals("/Users/me/Library/Application Support/Hypernova Client", DataFolder.of(none, "Mac OS X", "/Users/me").path)
        assertTrue("Windows: the local folder, which doesn't roam to other machines", DataFolder.of({ if (it == "LOCALAPPDATA") "C:/Users/me/AppData/Local" else null }, "Windows 11", "C:/Users/me").path.contains("Local"))
        assertEquals("a test copy", "/tmp/x", DataFolder.of({ if (it == DataFolder.ENV) "/tmp/x" else null }, "Linux", "/home/me").path)
    }

    // ─── Settings ───

    @Test fun settingsComeBackInTheirTypesAfterARestart() {
        DesktopStorage(home(), log).use { s ->
            s.prefs("hypernova").edit().putString("token", "t-1").putInt("lanPort", 47323).putLong("at", 5_000_000_000L).putBoolean("on", true).putString("gone", "x").apply()
            s.prefs("hypernova").edit().remove("gone").apply()
            s.flush()
        }
        DesktopStorage(home(), log).use { s ->
            val p = s.prefs("hypernova")
            assertEquals("t-1", p.getString("token", null))
            assertEquals(47323, p.getInt("lanPort", 0))
            assertEquals(5_000_000_000L, p.getLong("at", 0))
            assertTrue(p.getBoolean("on", false))
            assertFalse(p.contains("gone"))
            assertEquals("a default", 7, p.getInt("none", 7))
            assertEquals(setOf("token", "lanPort", "at", "on"), p.all.keys)
        }
    }

    @Test fun aClearHappensFirstAsSharedPreferencesDoesIt() {
        DesktopStorage(home(), log).use { s ->
            val p = s.prefs("a")
            p.edit().putString("old", "1").apply()
            p.edit().putString("new", "2").clear().apply()
            assertFalse(p.contains("old"))
            assertEquals("2", p.getString("new", null))
        }
    }

    @Test fun everyFileIsOwnerOnly() {
        DesktopStorage(home(), log).use { s ->
            s.prefs("hypernova").edit().putString("token", "t").apply()
            s.flush()
            assertEquals("rwx------", mode(home()))
            assertEquals("rwx------", mode(home().resolve("prefs")))
            assertEquals("rwx------", mode(s.filesDir.toPath()))
            assertEquals("rw-------", mode(home().resolve("prefs/hypernova.json")))
            assertEquals("no leftovers from the atomic write", listOf("hypernova.json"), home().resolve("prefs").toFile().list()!!.toList())
        }
    }

    @Test fun aFolderOrFileLeftOpenIsClosedAgain() {
        Files.createDirectories(home().resolve("prefs"))
        val file = home().resolve("prefs/hypernova.json")
        Files.writeString(file, """{"token":"t"}""")
        Files.setPosixFilePermissions(home(), PosixFilePermissions.fromString("rwxrwxrwx"))
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"))
        DesktopStorage(home(), log).use { s ->
            assertEquals("t", s.prefs("hypernova").getString("token", null))
            assertEquals("rwx------", mode(home()))
            assertEquals("rw-------", mode(file))
            assertTrue("and it's said", warnings.any { it.contains("owner-only") })
        }
    }

    @Test fun aNameIsNeverAPath() {
        DesktopStorage(home(), log).use { s ->
            s.prefs("../../evil").edit().putString("a", "b").apply()
            s.flush()
            assertFalse(temp.root.resolve("evil.json").exists())
            assertEquals(listOf("..%2F..%2Fevil.json"), home().resolve("prefs").toFile().list()!!.toList())
            assertThrows(IllegalArgumentException::class.java) { s.prefs("..") }
        }
    }

    @Test fun aSymlinkIsNeitherReadNorWrittenThrough() {
        val outside = temp.newFile("outside.json").toPath()
        Files.writeString(outside, "{}")
        Files.createDirectories(home().resolve("prefs"))
        Files.createSymbolicLink(home().resolve("prefs/hypernova.json"), outside)
        DesktopStorage(home(), log).use { s -> assertThrows(IOException::class.java) { s.prefs("hypernova") } }
        assertThrows(IOException::class.java) { OwnerOnly.write(home().resolve("prefs/hypernova.json"), byteArrayOf(1)) }
        assertEquals("{}", Files.readString(outside))
    }

    @Test fun aCorruptFileIsKeptAsideAndTheAppStartsEmpty() {
        Files.createDirectories(home().resolve("prefs"))
        val file = home().resolve("prefs/hypernova.json")
        Files.writeString(file, "not json")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        DesktopStorage(home(), log).use { s ->
            assertNull(s.prefs("hypernova").getString("token", null))
            assertTrue(Files.exists(home().resolve("prefs/hypernova.json.corrupt")))
        }
    }

    // ─── The device key ───

    private fun keys() = run {
        Files.createDirectories(home())
        DesktopKeys(home().resolve("device-key.json"), log)
    }

    @Test fun theKeyIsMadeOnceAndOwnerOnly() {
        val file = home().resolve("device-key.json")
        val (pub, priv) = keys().ensure()
        assertEquals("rw-------", mode(file))
        assertEquals("EC", priv.algorithm)
        // A new process reads the same key.
        val again = keys()
        assertArrayEquals(priv.encoded, again.privateKey()!!.encoded)
        assertArrayEquals(pub.encoded, again.ensure().first.encoded)
        // And it signs, and what it signs checks out.
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(again.privateKey()); update(byteArrayOf(1, 2, 3)) }.sign()
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(byteArrayOf(1, 2, 3)) }.verify(sig))
    }

    @Test fun noKeyIsNoKeyUntilOneIsAskedFor() {
        val k = keys()
        assertNull(k.privateKey())
        k.ensure()
        assertNotNull(k.privateKey())
        k.delete()
        assertNull(k.privateKey())
        assertFalse(Files.exists(home().resolve("device-key.json")))
    }

    @Test fun aKeyFileLeftOpenIsClosedAndSaid() {
        val file = home().resolve("device-key.json")
        keys().ensure()
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        assertNotNull(keys().privateKey())
        assertEquals("rw-------", mode(file))
        assertTrue(warnings.any { it.contains("device key") })
    }

    @Test fun anUnreadableKeyIsAsGoodAsNoneAndNeverLogged() {
        val file = home().resolve("device-key.json")
        keys().ensure()
        val secret = Files.readString(file)
        Files.writeString(file, secret.dropLast(20))
        val k = keys()
        assertNull(k.privateKey())
        assertTrue(warnings.isNotEmpty())
        assertTrue("the log holds no key material", warnings.none { w -> secret.chunked(40).any { w.contains(it) } })
        // The next certificate request makes a new one.
        assertNotNull(k.ensure().second)
    }

    @Test fun aSymlinkIsNotTheKey() {
        Files.createDirectories(home())
        Files.createSymbolicLink(home().resolve("device-key.json"), temp.newFile("elsewhere").toPath())
        assertThrows(IOException::class.java) { keys().privateKey() }
    }

    @Test fun theKeyGoesInACertificateRequestAndStaysOutOfTheSettings() {
        DesktopStorage(home(), log).use { s ->
            val keys = DesktopKeys(home().resolve("device-key.json"), log)
            val store = DeviceCertStore(s, keys, "srv")
            val csr = store.csr("dev-1")
            assertTrue(csr.isNotBlank())
            val (pub, priv) = keys.ensure()
            assertTrue(Csr.build("dev-1", pub, priv).isNotEmpty())
            s.prefs("hypernova.cert.srv").edit().putString("cert", "c").apply()
            s.flush()
            val secret = java.util.Base64.getEncoder().encodeToString(priv.encoded)
            for (f in home().resolve("prefs").toFile().listFiles()!!) assertFalse(f.name, f.readText().contains(secret))
        }
    }

    // ─── The clock and the alarm ───

    @Test fun theClockCountsASleepAndNeverGoesBack() {
        val nanos = AtomicLong(0)
        val wall = AtomicLong(1_000_000)
        val c = SleepAwareClock({ nanos.get() }, { wall.get() })
        nanos.addAndGet(2_000_000_000); wall.addAndGet(2_000)
        assertEquals("awake: as the monotonic clock says", 2_000, c.elapsed())
        // nanoTime stood still for an hour while the wall clock went on.
        nanos.addAndGet(1_000_000); wall.addAndGet(3_600_000)
        assertEquals("asleep: the hour counts", 3_602_000, c.elapsed())
        // The wall clock set back: nothing goes backwards.
        nanos.addAndGet(1_000_000_000); wall.addAndGet(-60_000)
        assertEquals(3_603_000, c.elapsed())
        // A small wander between the two clocks isn't a sleep.
        nanos.addAndGet(1_000_000_000); wall.addAndGet(1_500)
        assertEquals(3_604_000, c.elapsed())
    }

    private val direct = MainThread { it() }

    @Test fun anAlarmIsDueByTheClockThatCountsSleep() {
        val now = AtomicLong(0)
        val fired = CountDownLatch(1)
        val alarm = DesktopRetryAlarm(Ticks { now.get() }, direct)
        alarm.set(60_000) { fired.countDown() }
        assertFalse("not yet", fired.await(1_500, TimeUnit.MILLISECONDS))
        now.set(60_000)
        assertTrue(fired.await(5, TimeUnit.SECONDS))
    }

    @Test fun aCancelledAlarmNeverFires() {
        val now = AtomicLong(0)
        val fired = AtomicInteger()
        val alarm = DesktopRetryAlarm(Ticks { now.get() }, direct)
        alarm.set(10) { fired.incrementAndGet() }
        alarm.cancel()
        now.set(100)
        Thread.sleep(1_500)
        assertEquals(0, fired.get())
    }

    // ─── The network ───

    @Test fun theNetworkSaysWhatTheInterfacesSay() {
        var now = listOf("eth0" to listOf("192.168.1.2"))
        val n = DesktopNetwork(read = { now })
        assertTrue(n.hasNetwork())
        assertEquals("a local route is worth trying", NetKind.Wifi, n.kind())
        assertEquals("eth0", n.words())
        assertEquals("never sweeps a /24 behind a Docker bridge's .1", emptyList<String>(), n.hotspotGateways())
        now = emptyList()
        assertFalse(n.hasNetwork())
        assertEquals(NetKind.None, n.kind())
        assertEquals("no network", n.words())
        assertNull(n.active())
    }

    @Test fun whenItCantTellItSaysThereIsOne() {
        val n = DesktopNetwork(read = { throw IOException("no") })
        assertTrue(n.hasNetwork())
    }

    @Test fun aWatchHearsAChangeAndOnlyAChange() {
        var now = listOf("eth0" to listOf("192.168.1.2"))
        val n = DesktopNetwork(pollMs = 60_000, read = { now })
        val heard = AtomicInteger()
        val routes = AtomicInteger()
        val w = n.watchLink { heard.incrementAndGet() }
        val r = n.watchRoute { routes.incrementAndGet() }!!
        // Told at once of the network there is, on the watch's own thread.
        val deadline = System.currentTimeMillis() + 5_000
        while (routes.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, routes.get())
        n.tick()
        assertEquals("nothing changed", 0, heard.get())
        now = listOf("eth0" to listOf("192.168.1.3"))
        n.tick()
        assertEquals(1, heard.get())
        assertEquals(2, routes.get())
        w.stop(); r.stop()
        now = emptyList()
        n.tick()
        assertEquals("stopped", 1, heard.get())
    }

    @Test fun realInterfacesReadWithoutBridgesOrLoopback() {
        for ((name, addrs) in DesktopNetwork.interfaces()) {
            assertFalse(name, name.startsWith("docker") || name.startsWith("veth") || name.startsWith("br-") || name == "lo")
            assertTrue(addrs.isNotEmpty())
        }
    }

    // ─── Saying what's what ───

    @Test fun theVersionComesFromTheBuildAndNoServerOffersItAnUpdate() {
        assertTrue(DesktopAppInfo.versionName.isNotBlank())
        assertEquals(Int.MAX_VALUE, DesktopAppInfo.versionCode)
    }

    @Test fun theDeviceHasAName() {
        assertEquals("box", DesktopDevice(env = { if (it == "HOSTNAME") "box.lan" else null }).name)
        assertEquals("fromdns", DesktopDevice(env = { null }, hostName = { "fromdns.lan" }).name)
        assertTrue(DesktopDevice(env = { null }, hostName = { null }).name.isNotBlank())
        assertTrue(DesktopDevice().model.startsWith("Desktop"))
    }

    @Test fun whatADesktopHasntGotSaysSo() {
        DesktopPlatform(home(), log).use { p ->
            assertFalse("no notifications yet", p.notifier().canPost())
            assertFalse(p.speech.speaking)
            assertNull(p.thumbOf("/x"))
            assertTrue("this process holds the link", p.background.running)
        }
    }

    @Test fun anAutomationsNoteIsToldOnce() {
        DesktopStorage(home(), log).use { s ->
            val n = QuietAutomationNotes(log, s, "srv")
            assertTrue(n.firstTime(listOf("a", "b")))
            assertFalse(n.firstTime(listOf("a")))
            assertTrue(n.shown("b"))
            assertFalse("per server", QuietAutomationNotes(log, s, "other").shown("b"))
        }
    }

    @Test fun oneCopyPerFolder() {
        Files.createDirectories(home())
        assertTrue(oneCopy(home()))
        assertFalse("the lock is held", oneCopy(home()))
    }

    @Test fun theScreenWatchHearsTheOwnerLook() {
        val s = DesktopScreen()
        val heard = AtomicInteger()
        val w = s.watch { heard.incrementAndGet() }
        s.ownerLooked()
        assertEquals(1, heard.get())
        w.stop()
        s.ownerLooked()
        assertEquals(1, heard.get())
    }

}
