package com.prudnikweb.hypernova.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.FileBytes
import com.prudnikweb.hypernova.data.FileEntry
import com.prudnikweb.hypernova.data.FileFailure
import com.prudnikweb.hypernova.data.Listing
import com.prudnikweb.hypernova.data.Route
import com.prudnikweb.hypernova.ui.files.FileViewer
import com.prudnikweb.hypernova.ui.files.FilesScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Files and a file (ui/files/Files.kt, FileViewer.kt) over a scripted hub: a folder's rows, into a
 * folder and back up, a file opened, a key file's row explained; a text file in mono with its line
 * numbers, and a file with no preview downloaded and handed to Share.
 */
@RunWith(RobolectricTestRunner::class)
class FilesScreensTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private var backs = 0

    private companion object {
        const val ROOT = "/home/owner/projects/hypernova"
    }

    @Before fun setUp() = resetPrefs()

    private fun entry(name: String, dir: Boolean = false, size: Long = 0, locked: String? = null, agoMin: Long = 3) =
        FileEntry(name, "$ROOT/$name".replace("$ROOT/src/", "$ROOT/src/"), dir, size, Samples.NOW - agoMin * 60_000L, if (dir) null else "text/plain", locked)

    private fun hub(): FeatureHub = FeatureHub().apply {
        listings = { path ->
            when (path) {
                ROOT -> Result.success(Listing(ROOT, listOf(entry("src", dir = true), entry(".env", size = 23, locked = FileEntry.LOCKED_SECRET), entry("README.md", size = 1_250), entry("notes.txt", size = 8, agoMin = 90)), truncated = true, total = 120))
                "$ROOT/src" -> Result.success(Listing(path, listOf(FileEntry("parser.ts", "$ROOT/src/parser.ts", false, 512, Samples.NOW - 60_000L, "text/plain"))))
                else -> Result.failure(FileFailure("That folder is outside your projects.", code = "outside_projects", projectsRoot = "/home/owner/projects", setting = "browseOutsideProjects"))
            }
        }
    }

    private fun files(hub: FeatureHub, root: String = ROOT) {
        rule.setContent { Harness(spy) { FilesScreen(hub, Route.Files(root, "hypernova"), onBack = { backs++ }) } }
        rule.waitForIdle()
    }

    @Test fun aFoldersRowsFoldersFirstAndHowManyMore() {
        val hub = hub()
        files(hub)
        assertEquals(listOf("files($ROOT)"), hub.calls)
        rule.onNodeWithText("README.md", useUnmergedTree = true).assertExists()
        rule.onNode(hasContentDescription(str(R.string.fl_row_folder, "src"))).assertExists()
        rule.onNode(hasContentDescription(str(R.string.fl_row_locked, ".env"))).assertExists()
        rule.onNodeWithText(str(R.string.fl_truncated, "4", "120")).assertExists()
        rule.snap("files_folder")
    }

    @Test fun intoAFolderAndBackUpThenAFileOpens() {
        val hub = hub()
        files(hub)
        rule.onNode(hasContentDescription(str(R.string.fl_row_folder, "src"))).performClick()
        rule.waitForIdle()
        assertEquals("files($ROOT/src)", hub.calls.last())
        rule.onNodeWithText("parser.ts", useUnmergedTree = true).assertExists()
        rule.onNode(hasContentDescription(str(R.string.up_one))).performClick()
        rule.waitForIdle()
        assertEquals("files($ROOT)", hub.calls.last())
        rule.onNode(hasContentDescription(str(R.string.up_one))).assertDoesNotExist()
        rule.onNodeWithText("README.md", useUnmergedTree = true).performClick()
        assertEquals(listOf("file:$ROOT/README.md"), spy.opened)
        assertEquals(0, backs)
    }

    @Test fun aKeyFilesRowSaysWhyItWontOpen() {
        files(hub())
        rule.onNode(hasContentDescription(str(R.string.fl_row_locked, ".env"))).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.fp_secret_body)).assertExists()
        assertTrue("nothing opened", spy.opened.isEmpty())
    }

    @Test fun aFolderOutsideTheProjectsOffersTheSetting() {
        val hub = hub()
        hub.base.macState = hub.base.macState.copy(browseOutsideProjects = false)
        files(hub, root = "/etc")
        rule.onNodeWithText(str(R.string.fp_turn_on)).performClick()
        assertEquals(listOf("settings:${com.prudnikweb.hypernova.ui.settings.SettingsJump.FILES}"), spy.opened)
    }

    // ─── A file ───

    private fun viewer(hub: FeatureHub, path: String) {
        rule.setContent { Harness(spy) { FileViewer(hub, path, onBack = { backs++ }) } }
        rule.waitForIdle()
    }

    @Test fun aTextFileInMonoWithItsLineNumbers() {
        val hub = FeatureHub()
        hub.fileAnswer = { Result.success(FileBytes("export const parse = (s: string) =>\n\ts.trim()\n".toByteArray(), "text/plain")) }
        viewer(hub, "$ROOT/src/parser.ts")
        assertEquals(listOf("file($ROOT/src/parser.ts)"), hub.calls)
        rule.onNodeWithText("export const parse = (s: string) =>").assertExists()
        // Tabs as four spaces.
        rule.onNodeWithText("    s.trim()").assertExists()
        rule.onNodeWithText(str(R.string.fv_share)).assertExists()
        rule.snap("file_text")
    }

    @Test fun aFileTheServerWontShowSaysWhyWithTryAgain() {
        val hub = FeatureHub()
        var n = 0
        hub.fileAnswer = { if (n++ == 0) Result.failure(FileFailure("The server isn’t answering.", problem = com.prudnikweb.hypernova.data.Problem.Unreachable)) else Result.success(FileBytes("back\n".toByteArray(), "text/plain")) }
        viewer(hub, "$ROOT/notes.txt")
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("back").assertExists()
        assertEquals(2, hub.calls.count { it.startsWith("file(") })
    }

    @Test fun aFileWithNoPreviewIsDownloadedThenShared() {
        val hub = FeatureHub()
        hub.downloadBytes = "%PDF-1.7 not really".toByteArray()
        viewer(hub, "$ROOT/report.pdf")
        // Not asked for to show: there's no preview of it.
        assertTrue(hub.calls.none { it.startsWith("file(") })
        rule.onNodeWithText(str(R.string.fv_noview_title)).assertExists()
        rule.snap("file_no_preview")
        rule.onNodeWithText(str(R.string.fv_share)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("download($ROOT/report.pdf)"), hub.calls)
        val chooser = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        // Shared again: the file that's here, not downloaded twice.
        rule.onNodeWithText(str(R.string.fv_share)).performClick()
        rule.waitForIdle()
        assertEquals(1, hub.calls.count { it.startsWith("download(") })
    }
}
