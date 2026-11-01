package com.prudnikweb.hypernova.data.live

import android.os.Looper
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DlState
import com.prudnikweb.hypernova.data.FileFailure
import com.prudnikweb.hypernova.data.FileKind
import com.prudnikweb.hypernova.data.FileProblem
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.ui.changes.ChangesRepo
import com.prudnikweb.hypernova.ui.changes.LineKind
import com.prudnikweb.hypernova.ui.files.DownloadJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.net.URLDecoder

/**
 * Files and What changed from the phone (docs/PROTOCOL.md, "Files", "What changed"): a folder's
 * listing, a file's bytes, a download streamed to the cache and carried on after a drop, and the
 * What changed screen's two requests, against a [FakeDaemon].
 */
@RunWith(RobolectricTestRunner::class)
class FilesLiveTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    @After fun tearDown() = rig.close()

    private fun connect() {
        daemon.snapshot = snapshot(sessions = listOf(session("s1"))) { getJSONObject("mac").put("projectsRoot", "/home/me/Projects") }
        rig.connect()
    }

    private fun pathOf(r: FakeDaemon.Req) = URLDecoder.decode(r.query.split('&').first { it.startsWith("path=") }.removePrefix("path="), "UTF-8")

    @Test fun aFolderIsAskedForByItsPathAndListedFoldersFirst() {
        connect()
        // The Mac's own order shuffled: the phone puts folders first, then by name, whatever the case.
        val l = Fixtures.json("rest-files")
        val e = l.getJSONArray("entries")
        val shuffled = org.json.JSONArray().put(e.get(3)).put(e.get(1)).put(e.get(0)).put(e.get(2))
        daemon.ok("GET", "/v1/files", l.put("entries", shuffled))
        val listing = rig.call { rig.hub.files("/home/me/Projects/files demo") }.getOrThrow()
        assertEquals("/home/me/Projects/files demo", pathOf(daemon.calls("GET", "/v1/files").single()))
        assertEquals("/home/me/Projects/files-demo", listing.path)
        assertEquals(listOf("src", ".env", "notes.txt", "README.md"), listing.entries.map { it.name })
        assertTrue(listing.entries[0].isDir)
        assertEquals("secret", listing.entries[1].locked)
        assertFalse(listing.truncated)
        assertEquals(4, listing.total)
    }

    @Test fun aFolderTheMacWontOpenIsAFileFailureWithItsCodeAndSetting() {
        connect()
        daemon.on("GET", "/v1/files") { refusal(403, "outside_projects", "That folder is outside your projects.") { put("projectsRoot", "/home/me/Projects").put("setting", "browseOutsideProjects") } }
        val f = rig.call { rig.hub.files("/etc") }.exceptionOrNull() as FileFailure
        assertEquals("outside_projects", f.code)
        assertEquals(403, f.status)
        val p = FileProblem.of(f)
        assertEquals(FileKind.Outside, p.kind)
        assertEquals("/home/me/Projects", p.projectsRoot)
        assertTrue(p.settingOpens)
        // The server away: a network problem, to try again.
        daemon.down = true
        val away = FileProblem.of(rig.call { rig.hub.files("/home/me/Projects") }.exceptionOrNull())
        assertEquals(FileKind.Network, away.kind)
        assertTrue(away.retryable)
        daemon.down = false
    }

    @Test fun aFilesBytesComeWithTheirType() {
        connect()
        daemon.on("GET", "/v1/file") { MockResponse().setResponseCode(200).setHeader("Content-Type", "text/markdown; charset=utf-8").setBody("# Files demo\n") }
        val f = rig.call { rig.hub.file("/home/me/Projects/files-demo/README.md") }.getOrThrow()
        assertEquals("# Files demo\n", String(f.bytes))
        // Its type without the charset (Api.bytes keeps the media type alone).
        assertEquals("text/markdown", f.mime)
        val r = daemon.calls("GET", "/v1/file").single()
        assertEquals("/home/me/Projects/files-demo/README.md", pathOf(r))
        assertFalse("viewing isn't a download", r.query.contains("download=1"))
        // Too big to view: the sizes come with the refusal.
        daemon.on("GET", "/v1/file") { refusal(413, "too_large", "Too big to show.") { put("size", 90_000_000L).put("max", 32_000_000L) } }
        val big = FileProblem.of(rig.call { rig.hub.file("/home/me/Projects/big.bin") }.exceptionOrNull())
        assertEquals(FileKind.TooLarge, big.kind)
        assertEquals(90_000_000L, big.size)
        assertEquals(32_000_000L, big.max)
        // A key file.
        daemon.on("GET", "/v1/file") { refusal(403, "secret_file", "Keys stay on the server.") }
        assertEquals(FileKind.Secret, FileProblem.of(rig.call { rig.hub.file("/home/me/Projects/files-demo/.env") }.exceptionOrNull()).kind)
    }

    /** Bytes of a file to download: big enough to arrive in pieces. */
    private val content = ByteArray(300_000) { (it % 251).toByte() }

    private fun job(): DownloadJob {
        val dir = File(rig.context.cacheDir, "shared/dl/test").apply { deleteRecursively(); mkdirs() }
        return DownloadJob(rig.scope, "report.pdf", null, dir) { t, r, s, p -> rig.hub.download("/home/me/Projects/report.pdf", t, r, s, p) }
    }

    @Test fun aDownloadStreamsIntoTheCacheThenHandsOverTheFile() {
        connect()
        daemon.on("GET", "/v1/file") { MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setHeader("Content-Type", "application/pdf").setBody(okio.Buffer().write(content)) }
        val j = job()
        var handed: File? = null
        j.run { handed = it }
        rig.until("done") { j.state is DlState.Done && handed != null }
        assertEquals(DlState.Done(content.size.toLong()), j.state)
        assertArrayEquals(content, handed!!.readBytes())
        assertEquals("report.pdf", handed!!.name)
        val r = daemon.calls("GET", "/v1/file").single()
        assertEquals("/home/me/Projects/report.pdf", pathOf(r))
        assertTrue(r.query.endsWith("&download=1"))
        assertNull("a first try asks for all of it", r.headers["range"])
        // Asked again: the file that's there, no request.
        var again: File? = null
        j.run { again = it }
        assertEquals(handed, again)
        assertEquals(1, daemon.count("GET", "/v1/file"))
    }

    @Test fun aDownloadThatDroppedCarriesOnFromWhereItWasWhenTheMacStillHasTheSameFile() {
        connect()
        // The first try breaks off part way through the body.
        daemon.on("GET", "/v1/file") {
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(okio.Buffer().write(content))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }
        val j = job()
        j.run()
        rig.until("the drop") { j.state is DlState.Failed }
        val failed = j.state as DlState.Failed
        assertTrue("a dropped line is worth another try", failed.problem.retryable)
        val kept = j.bytesOnDisk
        assertTrue("part of it is kept ($kept)", kept in 1 until content.size)

        // Again: the rest, from that byte, only if it's still the same file.
        daemon.on("GET", "/v1/file") { r ->
            val from = r.headers["range"]!!.removePrefix("bytes=").removeSuffix("-").toInt()
            MockResponse().setResponseCode(206).setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes $from-${content.size - 1}/${content.size}")
                .setBody(okio.Buffer().write(content.copyOfRange(from, content.size)))
        }
        var handed: File? = null
        j.run { handed = it }
        rig.until("done") { handed != null }
        val second = daemon.calls("GET", "/v1/file").last()
        assertEquals("bytes=$kept-", second.headers["range"])
        assertEquals("\"v1\"", second.headers["if-range"])
        assertArrayEquals(content, handed!!.readBytes())
        assertEquals(DlState.Done(content.size.toLong()), j.state)
    }

    @Test fun aDownloadTheMacRefusesFailsWithItsReasonAndKeepsNothing() {
        connect()
        daemon.on("GET", "/v1/file") { refusal(404, "not_found", "That file is gone.") }
        val j = job()
        j.run()
        rig.until("the failure") { j.state is DlState.Failed }
        assertEquals(FileKind.NotFound, (j.state as DlState.Failed).problem.kind)
        assertEquals(0L, j.bytesOnDisk)
        j.dismiss()
        assertEquals(DlState.Idle, j.state)
    }

    @Test fun cancelStopsADownloadAndThrowsThePartAway() {
        connect()
        val gate = java.util.concurrent.CountDownLatch(1)
        daemon.on("GET", "/v1/file") { gate.await(5, java.util.concurrent.TimeUnit.SECONDS); MockResponse().setResponseCode(200).setBody(okio.Buffer().write(content)) }
        val j = job()
        j.run()
        rig.until("asked") { daemon.count("GET", "/v1/file") == 1 }
        j.cancel()
        gate.countDown()
        rig.pump()
        assertEquals(DlState.Idle, j.state)
        assertEquals(0L, j.bytesOnDisk)
    }

    // ─── What changed (ui/changes/ChangesModel.kt: ChangesRepo, through the active computer) ───

    private fun untilMain(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(3)
        }
    }

    @Test fun whatChangedAsksForTheSessionsOwnThenEverythingAndADiffByItsPath() {
        Core.init(rig.context as android.app.Application)
        val mac = FakeDaemon("gym")
        try {
            mac.snapshot = snapshot("gym", sessions = listOf(session("s1")))
            mac.ok("GET", "/v1/sessions/s1/changes", Fixtures.json("rest-changes"))
            mac.ok("GET", "/v1/sessions/s1/changes/diff", Fixtures.json("rest-changes-diff"))
            Core.paired(Pairing(mac.base, "tok-gym", "gym", "m-gym", "dev-1"))
            untilMain("linked") { Core.live?.link?.state == LinkState.Connected }

            val c = runBlocking { ChangesRepo.load("s1") }.getOrThrow()
            assertTrue(c.scoped)
            assertEquals(listOf("parser.ts"), c.files.map { it.path })
            assertEquals("", mac.calls("GET", "/v1/sessions/s1/changes").last().query)
            assertEquals("Bearer tok-gym", mac.calls("GET", "/v1/sessions/s1/changes").last().auth)
            runBlocking { ChangesRepo.load("s1", all = true) }
            assertEquals("all=1", mac.calls("GET", "/v1/sessions/s1/changes").last().query)

            val d = runBlocking { ChangesRepo.diff("s1", "src/a b.ts") }.getOrThrow()
            assertEquals("src/a b.ts", pathOf(mac.calls("GET", "/v1/sessions/s1/changes/diff").single()))
            assertEquals(listOf(LineKind.Hunk, LineKind.Del, LineKind.Add), d.lines.map { it.kind })
            assertEquals(1, d.lines[2].newNo)

            // Refused: the Mac's words.
            mac.on("GET", "/v1/sessions/s1/changes") { refusal(404, "no_session", "That session is gone.") }
            assertEquals("That session is gone.", runBlocking { ChangesRepo.load("s1") }.exceptionOrNull()!!.message)
        } finally {
            for (s in Core.servers.toList()) Core.forget(s.id)
            shadowOf(Looper.getMainLooper()).idle()
            mac.close()
        }
    }
}
