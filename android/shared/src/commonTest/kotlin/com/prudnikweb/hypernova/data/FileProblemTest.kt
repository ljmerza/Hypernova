package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every error the Mac gives for a folder or file becomes one explanation; only a connection failure may offer "Try again". */
class FileProblemTest {
    private fun fail(status: Int, code: String?, problem: Problem = Problem.Server, size: Long? = null, max: Long? = null, root: String? = null, setting: String? = null) =
        FileFailure("words", problem, status, code, size, max, root, setting)

    @Test fun `each code maps to its kind and none is retryable`() {
        val cases = mapOf(
            "outside_projects" to FileKind.Outside, "no_dir" to FileKind.Outside, "secret_file" to FileKind.Secret, "locked_folder" to FileKind.LockedFolder,
            "too_large" to FileKind.TooLarge, "not_found" to FileKind.NotFound, "attachment_gone" to FileKind.AttachmentGone,
            "mac_privacy" to FileKind.MacPrivacy, "unreadable" to FileKind.Unreadable, "not_a_dir" to FileKind.WrongKind, "is_dir" to FileKind.WrongKind,
        )
        for ((code, kind) in cases) {
            val p = FileProblem.of(fail(403, code))
            assertEquals(code, kind, p.kind)
            assertFalse("$code must not offer Try again", p.retryable)
        }
    }

    @Test fun `outside carries the projects folder and too_large its numbers`() {
        assertEquals("/Users/me/Programming", FileProblem.of(fail(403, "outside_projects", root = "/Users/me/Programming")).projectsRoot)
        val big = FileProblem.of(fail(413, "too_large", size = 120_000_000, max = 20_971_520))
        assertEquals(120_000_000L, big.size)
        assertEquals(20_971_520L, big.max)
    }

    @Test fun `Turn on in Settings only where the Mac says the setting opens it`() {
        assertTrue(FileProblem.of(fail(403, "outside_projects", setting = "browseOutsideProjects")).settingOpens)
        // Outside the home folder (/etc): the setting wouldn't help, so it isn't offered.
        assertFalse(FileProblem.of(fail(403, "outside_projects")).settingOpens)
        // A key place stays locked whatever the setting says.
        assertFalse(FileProblem.of(fail(403, "locked_folder", setting = "browseOutsideProjects")).settingOpens)
        assertFalse(FileProblem.of(fail(403, "outside_projects", setting = "somethingElse")).settingOpens)
    }

    @Test fun `folder asked for as a file keeps which way round`() {
        assertTrue(FileProblem.of(fail(400, "is_dir")).asFolder)
        assertFalse(FileProblem.of(fail(400, "not_a_dir")).asFolder)
    }

    @Test fun `a connection problem is the only retryable one`() {
        for (pr in listOf(Problem.NoNetwork, Problem.NoName, Problem.Unreachable, Problem.NotRunning, Problem.Tls)) {
            val p = FileProblem.of(fail(0, null, pr))
            assertEquals(FileKind.Network, p.kind)
            assertTrue(p.retryable)
        }
        assertTrue(FileProblem.of(fail(503, null)).retryable)
    }

    @Test fun `without a code the status decides`() {
        assertEquals(FileKind.TooLarge, FileProblem.of(fail(413, null)).kind)
        assertEquals(FileKind.NotFound, FileProblem.of(fail(404, null)).kind)
        assertEquals(FileKind.AttachmentGone, FileProblem.of(fail(410, null)).kind)
        assertEquals(FileKind.Other, FileProblem.of(fail(418, null)).kind)
        assertFalse(FileProblem.of(fail(418, null)).retryable)
    }

    @Test fun `a failure that isn't ours is Other and may be tried again`() {
        val p = FileProblem.of(java.io.IOException("boom"))
        assertEquals(FileKind.Other, p.kind)
        assertNull(p.size)
    }
}
