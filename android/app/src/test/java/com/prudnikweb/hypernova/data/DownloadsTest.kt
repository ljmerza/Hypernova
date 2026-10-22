package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val net = FileProblem(FileKind.Network, retryable = true, message = "")
    private val gone = FileProblem(FileKind.NotFound, retryable = false, message = "")

    // The state machine
    @Test fun `a download runs, reports progress and finishes`() {
        var s: DlState = DlState.Idle
        s = s.reduce(DlEvent.Start())
        assertEquals(DlState.Running(0, -1), s)
        s = s.reduce(DlEvent.Progress(10, 100))
        s = s.reduce(DlEvent.Progress(60, 100))
        assertEquals(DlState.Running(60, 100), s)
        assertEquals(DlState.Done(100), s.reduce(DlEvent.Finished(100)))
    }

    @Test fun `progress never goes backwards and keeps the known total`() {
        val s = DlState.Running(50, 100).reduce(DlEvent.Progress(40, -1))
        assertEquals(DlState.Running(50, 100), s)
    }

    @Test fun `cancel returns to idle and a second start is ignored while running`() {
        val running = DlState.Idle.reduce(DlEvent.Start())
        assertEquals(running, running.reduce(DlEvent.Start()))
        assertEquals(DlState.Idle, running.reduce(DlEvent.Cancel))
    }

    @Test fun `a connection failure keeps the bytes and the next start resumes from them`() {
        val failed = DlState.Running(30, 100).reduce(DlEvent.Failure(net))
        assertEquals(DlState.Failed(net, 30), failed)
        assertEquals(DlState.Running(30, -1), failed.reduce(DlEvent.Start(kept = 30)))
    }

    @Test fun `a failure that can't be retried starts over`() {
        val failed = DlState.Running(30, 100).reduce(DlEvent.Failure(gone))
        assertEquals(DlState.Running(0, -1), failed.reduce(DlEvent.Start(kept = 30)))
    }

    @Test fun `events that don't apply change nothing`() {
        assertEquals(DlState.Idle, DlState.Idle.reduce(DlEvent.Progress(5, 10)))
        assertEquals(DlState.Idle, DlState.Idle.reduce(DlEvent.Finished(5)))
        assertEquals(DlState.Done(5), DlState.Done(5).reduce(DlEvent.Failure(net)))
        assertEquals(DlState.Done(5), DlState.Done(5).reduce(DlEvent.Cancel))
    }

    // Resuming
    @Test fun `a resume needs a part, an etag and a file that isn't whole yet`() {
        assertEquals(DownloadResume(10, "\"a\""), Downloads.resumePlan(10, "\"a\"", 100))
        assertEquals(DownloadResume(10, "\"a\""), Downloads.resumePlan(10, "\"a\"", null))
        assertNull(Downloads.resumePlan(0, "\"a\"", 100))
        assertNull(Downloads.resumePlan(10, null, 100))
        assertNull(Downloads.resumePlan(100, "\"a\"", 100))
    }

    @Test fun `a 206 carries on only from the byte asked for`() {
        assertEquals(Downloads.Placement(10, 100), Downloads.place(206, 10, "bytes 10-99/100", 90))
        assertNull(Downloads.place(206, 10, "bytes 0-99/100", 100))
        assertNull(Downloads.place(206, 10, null, 90))
    }

    @Test fun `a 200 answer is the whole file even when a resume was asked`() {
        assertEquals(Downloads.Placement(0, 100), Downloads.place(200, 10, null, 100))
    }

    // Names
    @Test fun `non-ASCII names are kept`() {
        assertEquals("отчёт.pdf", Downloads.safeName("отчёт.pdf"))
        assertEquals("報告書 最終.docx", Downloads.safeName("/Users/me/報告書 最終.docx"))
        assertEquals("café.txt", Downloads.safeName("café.txt"))
    }

    @Test fun `separators and control characters are replaced`() {
        assertEquals("a_b_c.txt", Downloads.safeName("a:b*c.txt"))
        assertEquals("x_y.txt", Downloads.safeName("x\u0000y.txt"))
        assertEquals("passwd", Downloads.safeName("../../etc/passwd"))
        assertEquals("file", Downloads.safeName("..."))
        assertEquals("file", Downloads.safeName(""))
    }

    @Test fun `a long name keeps its extension and never splits an emoji`() {
        val n = Downloads.safeName("😀".repeat(100) + ".pdf", max = 20)
        assertTrue(n.endsWith(".pdf"))
        assertTrue(n.length <= 20)
        assertFalse(Character.isHighSurrogate(n[n.length - 5]))
    }

    @Test fun `types come from the Mac or from the extension`() {
        assertEquals("application/pdf", Downloads.mimeFor("отчёт.PDF", null))
        assertEquals("application/pdf", Downloads.mimeFor("x.pdf", "application/octet-stream"))
        assertEquals("image/png", Downloads.mimeFor("x", "image/png"))
        assertEquals("application/octet-stream", Downloads.mimeFor("Makefile", null))
    }

    @Test fun `which types go straight to share and save`() {
        assertFalse(Downloads.previewable("report.pdf"))
        assertFalse(Downloads.previewable("a.ZIP"))
        assertTrue(Downloads.previewable("main.kt"))
        assertTrue(Downloads.previewable("Makefile"))
        assertTrue(Downloads.previewable("photo.png"))
    }

    @Test fun `the cache folder key is stable and differs by path`() {
        assertEquals(Downloads.key("/a/b.pdf"), Downloads.key("/a/b.pdf"))
        assertFalse(Downloads.key("/a/b.pdf") == Downloads.key("/a/c.pdf"))
    }

    @Test fun `a sweep removes old downloads and empty folders only`() {
        val old = tmp.newFolder("k1").resolve("old.pdf").apply { writeText("x"); setLastModified(1_000) }
        val fresh = tmp.newFolder("k2").resolve("new.pdf").apply { writeText("x"); setLastModified(20 * 3600_000L) }
        Downloads.sweep(tmp.root, now = 40 * 3600_000L, maxAgeMs = 24 * 3600_000L)
        assertFalse(old.exists())
        assertFalse(old.parentFile.exists())
        assertTrue(fresh.exists())
        val keep = tmp.newFolder("k3").resolve("keep.pdf").apply { writeText("x"); setLastModified(40 * 3600_000L - 1000) }
        Downloads.sweep(tmp.root, now = 40 * 3600_000L, maxAgeMs = 24 * 3600_000L)
        assertTrue(keep.exists())
    }
}
