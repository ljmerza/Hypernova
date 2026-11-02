package com.prudnikweb.hypernova.data

import com.prudnikweb.hypernova.data.live.MapPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/** The switch, the queue and the files (data/DebugLog.kt, DebugFiles.kt), on a real thread and real files in a temp folder. */
class DebugLogTest {
    private val root: File = Files.createTempDirectory("debug-log").toFile()
    private val dir = File(root, "logs")
    private val prefs = MapPrefs()
    private val head = DebugHead("1.2.3 (45)", "debug build", "Test OS 1", "Test Model")
    private val now = AtomicLong(1_800_000_000_000)

    private fun logger(fileMax: Long = RotatingLog.FILE_MAX, files: Int = RotatingLog.FILES, capacity: Int = DebugLogger.QUEUE) =
        DebugLogger(dir, prefs, head, clock = { now.get() }, capacity = capacity, fileMax = fileMax, files = files)

    private val open = mutableListOf<DebugLogger>()
    private fun on(l: DebugLogger = logger()) = l.also { open += it; it.turnOn() }

    @After fun tearDown() {
        open.forEach { it.turnOff() }
        open.forEach { it.flush(5_000) }
        DebugLog.use(null)
        root.deleteRecursively()
    }

    private fun text(): String = listOf("debug.4.log", "debug.3.log", "debug.2.log", "debug.1.log", "debug.log").map { File(dir, it) }.filter { it.exists() }.joinToString("") { it.readText() }

    // ── Off ──

    @Test fun offByDefaultAndOffWritesNothingAtAll() {
        val l = logger()
        DebugLog.use(l)
        l.start()
        assertFalse(l.isOn)
        assertEquals(0L, l.until)
        l.put(DebugLevel.E, "t", "a line", RuntimeException("x"))
        DebugLog.e("t") { "a line" }
        assertTrue(l.flush())
        l.refresh()
        assertFalse("no folder even", dir.exists())
        assertEquals(0L, l.sizeBytes)
        assertTrue(l.empty)
    }

    @Test fun offNoWordsAreBuilt() {
        DebugLog.use(logger())
        var built = 0
        repeat(100) {
            DebugLog.d("t") { built++; "words" }
            DebugLog.i("t") { built++; "words" }
            DebugLog.w("t") { built++; "words" }
            DebugLog.e("t") { built++; "words" }
        }
        assertEquals(0, built)
    }

    @Test fun onTheWordsGoInTheFileWithTheirLevelAndTime() {
        val l = on()
        DebugLog.use(l)
        DebugLog.d("http") { "a debug line" }
        DebugLog.i("conn") { "an info line" }
        DebugLog.w("net") { "a warning" }
        DebugLog.e("error", IllegalStateException("x")) { "an error" }
        assertTrue(l.flush())
        val text = text()
        assertTrue(text, Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} D http   a debug line""").containsMatchIn(text))
        assertTrue(text, text.contains(" I conn   an info line"))
        assertTrue(text, text.contains(" W net    a warning"))
        assertTrue(text, text.contains(" E error  an error\njava.lang.IllegalStateException (its message left out, 1 characters)\n    at "))
    }

    @Test fun theHeadSaysWhatTheFileIsFrom() {
        val l = on()
        l.put(DebugLevel.I, "t", "x", null)
        assertTrue(l.flush())
        val top = File(dir, "debug.log").readLines().take(6)
        assertEquals("# Hypernova debug log", top[0])
        assertEquals("# App: version 1.2.3 (45), debug build", top[1])
        assertEquals("# System: Test OS 1", top[2])
        assertEquals("# Device: Test Model", top[3])
    }

    @Test fun onlyTheOwnerCanReadIt() {
        val l = on()
        l.put(DebugLevel.I, "t", "x", null)
        l.crashed(Thread.currentThread(), RuntimeException())
        assertTrue(l.flush())
        // Android's file modes aren't readable through this on a host test's other OSes: POSIX or nothing.
        if (!dir.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) return
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dir, "debug.log").toPath())))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dir, "crashes.log").toPath())))
    }

    // ── The 24 hours ──

    @Test fun turnedOnItSaysWhenItTurnsOffAndDoesThenAndWritesNothingAfter() {
        val l = on()
        val ends = now.get() + DebugLogger.DURATION_MS
        assertEquals(ends, l.until)
        assertEquals(ends, prefs.getLong(DebugLogger.KEY, 0))
        l.put(DebugLevel.I, "t", "before the end", null)
        assertTrue(l.flush())

        now.set(ends - 1)
        l.put(DebugLevel.I, "t", "a moment before", null)
        assertTrue(l.flush())
        assertTrue(l.isOn)

        now.set(ends)
        l.put(DebugLevel.I, "t", "AFTER THE END", null)
        l.flush()
        waitFor("it to turn off") { !l.isOn }
        waitFor("the state to say so") { l.until == 0L }
        assertEquals(0L, prefs.getLong(DebugLogger.KEY, 0))
        l.put(DebugLevel.I, "t", "LATER STILL", null)
        assertTrue(l.flush())
        val text = text()
        assertTrue(text, text.contains("a moment before"))
        assertTrue(text, text.contains("debug logging turned itself off after 24 hours"))
        assertFalse(text, text.contains("AFTER THE END"))
        assertFalse(text, text.contains("LATER STILL"))
    }

    @Test fun itStaysOnAcrossARestartUntilItsTimeIsUp() {
        val first = on()
        val ends = first.until
        first.put(DebugLevel.I, "t", "first run", null)
        first.flush()
        first.turnOff()
        first.flush()

        prefs.edit().putLong(DebugLogger.KEY, ends).apply()
        now.addAndGet(3_600_000)
        val second = logger().also { open += it }
        second.start()
        assertTrue(second.isOn)
        assertEquals(ends, second.until)
    }

    @Test fun aRestartAfterItsTimeLeavesItOff() {
        prefs.edit().putLong(DebugLogger.KEY, now.get() - 1).apply()
        val l = logger()
        l.start()
        assertFalse(l.isOn)
        assertFalse(prefs.contains(DebugLogger.KEY))
    }

    @Test fun aClockThatPutsTheEndFurtherThan24HoursOffCountsAsOver() {
        prefs.edit().putLong(DebugLogger.KEY, now.get() + DebugLogger.DURATION_MS + 60_000).apply()
        val l = logger()
        l.start()
        assertFalse(l.isOn)
    }

    @Test fun turningItOnAgainStartsTheDayAgain() {
        val l = on()
        now.addAndGet(3_600_000)
        l.turnOn()
        assertEquals(now.get() + DebugLogger.DURATION_MS, l.until)
    }

    @Test fun turnedOffWhatsQueuedStillGoesAndNothingAfter() {
        val l = on()
        DebugLog.use(l)
        assertTrue(DebugLog.on)
        l.put(DebugLevel.I, "t", "queued", null)
        l.turnOff()
        assertFalse(l.isOn)
        DebugLog.use(l)
        assertFalse(DebugLog.on)
        l.put(DebugLevel.I, "t", "AFTER OFF", null)
        assertTrue(l.flush())
        assertTrue(text().contains("queued"))
        assertFalse(text().contains("AFTER OFF"))
        assertFalse(prefs.contains(DebugLogger.KEY))
    }

    // ── The size limit ──

    @Test fun neverPastItsLimitAndTheOldestGoFirst() {
        val l = on(logger(fileMax = 16 * 1024, files = 5))
        val total = 16L * 1024 * 5
        repeat(4_000) { n ->
            l.put(DebugLevel.D, "t", "line ${n.toString().padStart(5, '0')} " + "x".repeat(60), null)
            if (n % 256 == 255) assertTrue(l.flush(10_000))
        }
        assertTrue(l.flush(10_000))
        val files = dir.listFiles()!!.filter { it.name.startsWith("debug") }
        assertEquals(setOf("debug.log", "debug.1.log", "debug.2.log", "debug.3.log", "debug.4.log"), files.map { it.name }.toSet())
        for (f in files) assertTrue("${f.name}: ${f.length()}", f.length() <= 16 * 1024)
        assertTrue(files.sumOf { it.length() } <= total)
        val text = text()
        assertTrue("the newest is there", text.contains("line 03999"))
        assertFalse("the oldest went", text.contains("line 00000"))
        // Oldest first, across the files.
        val numbers = Regex("""line (\d{5})""").findAll(text).map { it.groupValues[1].toInt() }.toList()
        assertEquals(numbers.sorted(), numbers)
        // Each file says what it's from at its head.
        for (f in files) assertEquals("# Hypernova debug log", f.useLines { it.first() })
    }

    @Test fun theRealLimitsAreAbout5MB() {
        assertEquals(5L * 1024 * 1024, RotatingLog.TOTAL)
        val l = on()
        val line = "x".repeat(1_000)
        repeat(7_000) { n ->
            l.put(DebugLevel.D, "t", line, null)
            if (n % 512 == 511) assertTrue(l.flush(20_000))
        }
        assertTrue(l.flush(20_000))
        l.refresh()
        val size = dir.listFiles()!!.filter { it.name.startsWith("debug") }.sumOf { it.length() }
        assertTrue("$size", size <= RotatingLog.TOTAL && size > 4 * 1024 * 1024)
        assertEquals(size, l.sizeBytes)
    }

    @Test fun aRunThatFilledTheFileStartsANewOne() {
        val first = on(logger(fileMax = 1024))
        repeat(9) { first.put(DebugLevel.D, "t", "x".repeat(100), null) }
        first.flush()
        first.turnOff()
        first.flush()
        val second = on(logger(fileMax = 1024))
        repeat(9) { second.put(DebugLevel.D, "t", "y".repeat(100), null) }
        second.flush()
        for (f in dir.listFiles()!!) assertTrue("${f.name}: ${f.length()}", f.length() <= 1024)
    }

    // ── The queue ──

    @Test fun aBurstPastTheQueueIsDroppedAndCountedAndNeverWaitedFor() {
        // A queue of one and a thread that's kept out of the file: put can't block.
        val l = on(logger(capacity = 2))
        val lock = CountDownLatch(1)
        val hold = Thread {
            synchronized(heldLock(l)) { lock.await() }
        }
        hold.start()
        Thread.sleep(50)
        val t0 = System.nanoTime()
        repeat(10_000) { l.put(DebugLevel.D, "t", "burst", null) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        lock.countDown()
        hold.join()
        assertTrue("10,000 lines took $ms ms", ms < 5_000)
        waitFor("the count to reach the file") {
            l.put(DebugLevel.I, "t", "after", null)
            l.flush(2_000)
            text().contains("lines dropped: too many at once")
        }
    }

    /** The logger's file lock, to hold its writer back. */
    private fun heldLock(l: DebugLogger): Any = DebugLogger::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(l)

    // ── Clear, and one file to send ──

    @Test fun clearTakesTheLogAndTheCrashes() {
        val l = on()
        l.put(DebugLevel.I, "t", "OLD LINE", null)
        l.crashed(Thread.currentThread(), RuntimeException())
        assertTrue(l.flush())
        l.refresh()
        waitFor("a size") { l.sizeBytes > 0 && l.hasCrash }
        l.clear()
        assertFalse(File(dir, "crashes.log").exists())
        assertFalse(text().contains("OLD LINE"))
        // Still on, it starts over with its head.
        assertTrue(text().startsWith("# Hypernova debug log"))
        assertFalse(l.hasCrash)
        l.turnOff()
        l.flush()
        l.clear()
    }

    @Test fun offClearLeavesNothing() {
        val l = on()
        l.put(DebugLevel.I, "t", "x", null)
        assertTrue(l.flush())
        l.turnOff()
        assertTrue(l.flush())
        // The line clear would write has nowhere to go.
        l.clear()
        assertEquals(0L, l.sizeBytes)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun theBundleIsTheCrashesThenTheLogOldestFirstUnderTheLimitTheServerSets() {
        val l = on()
        repeat(7_000) { n ->
            l.put(DebugLevel.D, "t", "$n " + "x".repeat(1_000), null)
            if (n % 512 == 511) assertTrue(l.flush(20_000))
        }
        repeat(50) { l.crashed(Thread.currentThread(), IllegalStateException("x")) }
        val to = File(root, "out/bundle.log")
        val size = l.bundle(to)
        assertEquals(to.length(), size)
        assertTrue("$size is under the server's 6 MB", size < 6 * 1024 * 1024)
        val text = to.readText()
        assertTrue(text.startsWith("# Hypernova debug logs from Test Model"))
        assertTrue(text.indexOf(CrashFile.MARK) < text.indexOf("# Hypernova debug log\n"))
        assertNotNull(Regex("""^=== Crash""", RegexOption.MULTILINE).find(text))
    }

    // ── Crashes ──

    @Test fun aCrashIsSavedWithTheSwitchOffStackTraceOnly() {
        val l = logger()
        val t = IllegalStateException("PLANTEDCONTENT", RuntimeException("PLANTEDCAUSE"))
        l.crashed(Thread("main-thread"), t)
        val crash = File(dir, "crashes.log").readText()
        assertTrue(crash, crash.startsWith("=== Crash "))
        assertTrue(crash, crash.contains("version 1.2.3 (45), thread main-thread"))
        assertTrue(crash, crash.contains("java.lang.IllegalStateException (its message left out, 14 characters)"))
        assertTrue(crash, crash.contains("Caused by: java.lang.RuntimeException"))
        assertTrue(crash, crash.contains("    at com.prudnikweb.hypernova.data.DebugLogTest"))
        assertFalse(crash, crash.contains("PLANTED"))
        // Off, the log itself isn't touched.
        assertFalse(File(dir, "debug.log").exists())
        l.refresh()
        assertTrue(l.hasCrash)
        assertFalse(l.empty)
    }

    @Test fun onACrashAlsoReachesTheLogBeforeItGoes() {
        val l = on()
        l.put(DebugLevel.I, "t", "just before", null)
        l.crashed(Thread("main-thread"), IllegalStateException("x"))
        val text = text()
        assertTrue(text, text.contains("just before"))
        assertTrue(text, text.contains("the app crashed on thread main-thread"))
    }

    @Test fun theCrashFileStaysSmallAndKeepsTheNewest() {
        val l = logger()
        repeat(400) { n -> l.crashed(Thread("t$n"), IllegalStateException("x")) }
        val crash = File(dir, "crashes.log")
        assertTrue("${crash.length()}", crash.length() <= CrashFile.MAX)
        val text = crash.readText()
        assertTrue(text.startsWith(CrashFile.MARK))
        assertTrue(text.contains("thread t399"))
        assertFalse(text.contains("thread t0\n"))
    }

    @Test fun theHandlerChainsToTheOneBeforeOnceWithTheSameArguments() {
        val l = logger()
        val calls = mutableListOf<Pair<Thread, Throwable>>()
        val handler = DebugLog.chained({ th, e -> calls += th to e }) { l }
        val thread = Thread("crasher")
        val t = RuntimeException("boom")
        handler.uncaughtException(thread, t)
        assertEquals(1, calls.size)
        assertSame(thread, calls[0].first)
        assertSame(t, calls[0].second)
        assertTrue(File(dir, "crashes.log").exists())
    }

    @Test fun whateverGoesWrongSavingItTheOneBeforeStillGetsIt() {
        val calls = mutableListOf<Throwable>()
        val t = RuntimeException("boom")
        // A folder that can't be: a file sits where it would go.
        File(root, "blocked").writeText("x")
        val bad = DebugLogger(File(root, "blocked/logs"), prefs, head)
        DebugLog.chained({ _, e -> calls += e }) { bad }.uncaughtException(Thread.currentThread(), t)
        DebugLog.chained({ _, e -> calls += e }) { error("no logger for you") }.uncaughtException(Thread.currentThread(), t)
        DebugLog.chained({ _, e -> calls += e }) { null }.uncaughtException(Thread.currentThread(), t)
        assertEquals(listOf<Throwable>(t, t, t), calls)
    }

    @Test fun anInstalledHandlerRunsOnARealThreadsDeathAndStaysOnce() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        val heard = CountDownLatch(1)
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> heard.countDown() }
            val l = logger()
            Thread.setDefaultUncaughtExceptionHandler(DebugLog.chained(Thread.getDefaultUncaughtExceptionHandler()) { l })
            Thread({ throw IllegalStateException("real") }, "dying").apply { start(); join() }
            assertTrue(heard.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(File(dir, "crashes.log").readText().contains("thread dying"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    // ── What it costs ──

    /** Numbers for the report, printed; the limits are loose enough for a busy CI machine. Measured, not assumed. */
    @Test fun whatItCosts() {
        val l = logger()
        DebugLog.use(l)
        var sink = 0
        // Warm up, then measure.
        repeat(200_000) { DebugLog.d("t") { sink++; "x" } }
        val n = 5_000_000
        var t0 = System.nanoTime()
        repeat(n) { DebugLog.d("t") { sink++; "x $it" } }
        val offNs = (System.nanoTime() - t0).toDouble() / n
        assertEquals("off, no words are built", 0, sink)

        l.turnOn()
        DebugLog.use(l)
        val m = 200_000
        // Under the queue's size each time, so this measures the call and not the drops.
        t0 = System.nanoTime()
        var spent = 0L
        repeat(m / 4_000) { round ->
            val r0 = System.nanoTime()
            repeat(4_000) { i -> DebugLog.d("http") { "GET /v1/sessions/s$round/events: 200 in $i ms, got 12.4 KB" } }
            spent += System.nanoTime() - r0
            assertTrue(l.flush(30_000))
        }
        val putNs = spent.toDouble() / m
        val wallMs = (System.nanoTime() - t0) / 1_000_000.0
        println("debug log, measured here: off ${"%.1f".format(offNs)} ns a call; on ${"%.0f".format(putNs)} ns a call on the caller's thread; $m lines scrubbed and written in ${"%.0f".format(wallMs)} ms in all (${"%.1f".format(wallMs * 1_000 / m)} us a line, on its own thread)")
        assertTrue("off costs $offNs ns a call", offNs < 200)
        assertTrue("on costs $putNs ns a call", putNs < 50_000)
    }

    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
        }
    }
}
