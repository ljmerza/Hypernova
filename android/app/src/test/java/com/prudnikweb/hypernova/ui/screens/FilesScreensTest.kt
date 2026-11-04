package com.prudnikweb.hypernova.ui.screens

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.FileBytes
import com.prudnikweb.hypernova.data.FileEntry
import com.prudnikweb.hypernova.data.FileFailure
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.ui.session.TranscriptItem
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
 * numbers, a file with no preview downloaded and handed to Share; Markdown raw first with its switch,
 * and an agent's plan, for reading only.
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

    /** The viewer reads and decodes off the main thread, which waitForIdle doesn't wait for. */
    private fun waitForText(text: String) = rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun aTextFileInMonoWithItsLineNumbers() {
        val hub = FeatureHub()
        hub.fileAnswer = { Result.success(FileBytes("export const parse = (s: string) =>\n\ts.trim()\n".toByteArray(), "text/plain")) }
        viewer(hub, "$ROOT/src/parser.ts")
        waitForText("export const parse = (s: string) =>")
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
        waitForText(str(R.string.try_again))
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        waitForText("back")
        assertEquals(2, hub.calls.count { it.startsWith("file(") })
    }

    @Test fun aFileWithNoPreviewIsDownloadedThenShared() {
        val hub = FeatureHub()
        hub.downloadBytes = "%PDF-1.7 not really".toByteArray()
        viewer(hub, "$ROOT/report.pdf")
        waitForText(str(R.string.fv_noview_title))
        // Not asked for to show: there's no preview of it.
        assertTrue(hub.calls.none { it.startsWith("file(") })
        rule.snap("file_no_preview")
        rule.onNodeWithText(str(R.string.fv_share)).performClick()
        rule.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); shadowOf(context as Application).peekNextStartedActivity() != null }
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

    // ─── Markdown: raw first, with the switch to formatted ───

    private val MD = "# Handoff\n\nWhere **things** stand.\n\n- First step\n- Second step\n\n| Step | Who |\n|---|---|\n| Build | Codex |\n\n```\nnpm test\n```\n"

    private fun markdown(hub: FeatureHub = FeatureHub(), viewOnly: Boolean = false, text: String = MD) =
        hub.apply { fileAnswer = { Result.success(FileBytes(text.toByteArray(), "text/markdown", viewOnly)) } }

    @Test fun markdownOpensRawAndSwitchesToFormattedAndBack() {
        viewer(markdown(), "$ROOT/PLAN.md")
        // Raw first: the source as written, line by line in mono, and the switch.
        waitForText("# Handoff")
        rule.onNodeWithText("- First step").assertExists()
        rule.onNodeWithText(str(R.string.fv_raw)).assertIsSelected()
        rule.onNodeWithText(str(R.string.fv_formatted)).assertIsNotSelected()
        rule.onNodeWithText(str(R.string.fv_share)).assertExists()
        rule.snap("file_markdown_raw")
        // Formatted: the chat's own drawing, the marks gone.
        rule.onNodeWithText(str(R.string.fv_formatted)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Handoff").assertExists()
        rule.onNodeWithText("First step").assertExists()
        rule.onNodeWithText("npm test").assertExists()
        assertEquals(0, rule.onAllNodesWithText("# Handoff").fetchSemanticsNodes().size)
        rule.onNodeWithText(str(R.string.fv_formatted)).assertIsSelected()
        rule.snap("file_markdown_formatted")
        rule.onNodeWithText(str(R.string.fv_raw)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("# Handoff").assertExists()
    }

    @Test fun openingMarkdownAgainStartsRawWhateverWasChosenBefore() {
        val hub = markdown()
        var opening by mutableIntStateOf(0)
        rule.setContent { Harness(spy) { key(opening) { FileViewer(hub, "$ROOT/PLAN.md", onBack = {}) } } }
        waitForText("# Handoff")
        rule.onNodeWithText(str(R.string.fv_formatted)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.fv_formatted)).assertIsSelected()
        opening++
        rule.waitForIdle()
        waitForText("# Handoff")
        rule.onNodeWithText(str(R.string.fv_raw)).assertIsSelected()
    }

    @Test fun theSwitchIsThereForAnUppercaseName() {
        viewer(markdown(), "$ROOT/NOTES.MD")
        waitForText("# Handoff")
        rule.onNodeWithText(str(R.string.fv_raw)).assertIsSelected()
    }

    @Test fun theSwitchIsThereForTheLongerName() {
        viewer(markdown(), "$ROOT/notes.markdown")
        waitForText("# Handoff")
        rule.onNodeWithText(str(R.string.fv_raw)).assertIsSelected()
    }

    @Test fun aTextFileHasNoSwitch() {
        viewer(FeatureHub().apply { fileAnswer = { Result.success(FileBytes("# not markdown\n".toByteArray(), "text/plain")) } }, "$ROOT/notes.txt")
        waitForText("# not markdown")
        assertEquals(0, rule.onAllNodesWithText(str(R.string.fv_formatted)).fetchSemanticsNodes().size)
    }

    @Test fun aLongDocumentScrollsToItsEndInBothViewsAndDrawsOnlyWhatsInView() {
        val long = (1..4000).joinToString("\n\n") { "## Part $it\n\nWhat part $it says." }
        viewer(markdown(text = long), "$ROOT/LONG.md")
        waitForText("## Part 1")
        // A list, not one long column: the far end isn't drawn until it's scrolled to.
        rule.onNodeWithText(str(R.string.fv_formatted)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Part 1").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Part 4000").fetchSemanticsNodes().size)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Part 4000"))
        rule.onNodeWithText("Part 4000").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Part 1").fetchSemanticsNodes().size)
        // The other view keeps its own place, at the top; back here is still at the end.
        rule.onNodeWithText(str(R.string.fv_raw)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("## Part 1").assertExists()
        rule.onNodeWithText(str(R.string.fv_formatted)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Part 4000").assertExists()
    }

    @Test fun anAgentsPlanIsForReadingOnly() {
        val hub = markdown(viewOnly = true)
        viewer(hub, "/home/owner/.codex/plans/handoff.md")
        waitForText("# Handoff")
        rule.onNodeWithText(str(R.string.fv_raw)).assertIsSelected()
        rule.onNodeWithText(str(R.string.fv_view_only)).assertExists()
        for (s in listOf(R.string.fv_share, R.string.fv_save, R.string.fv_open_with)) assertEquals(0, rule.onAllNodesWithText(str(s)).fetchSemanticsNodes().size)
        rule.snap("file_agent_plan")
        rule.onNodeWithText(str(R.string.fv_formatted)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Handoff").assertExists()
        assertEquals(0, rule.onAllNodesWithText(str(R.string.fv_share)).fetchSemanticsNodes().size)
        assertTrue(hub.calls.none { it.startsWith("download(") })
    }

    @Test fun aProjectsMarkdownSaysNothingOfPlans() {
        viewer(markdown(), "$ROOT/PLAN.md")
        waitForText("# Handoff")
        assertEquals(0, rule.onAllNodesWithText(str(R.string.fv_view_only)).fetchSemanticsNodes().size)
    }

    @Test fun aPlanTooLargeToShowOffersNoDownload() {
        val hub = FeatureHub()
        hub.fileAnswer = { Result.failure(FileFailure("Too large.", status = 413, code = "too_large", size = 30_000_000, max = 20_971_520, viewOnly = true)) }
        viewer(hub, "/home/owner/.codex/plans/huge.md")
        waitForText(str(R.string.fp_toolarge_title))
        assertEquals(0, rule.onAllNodesWithText(str(R.string.fp_download_instead)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText(str(R.string.fv_raw)).fetchSemanticsNodes().size)
    }

    @Test fun aFileTooLargeToShowStillOffersADownload() {
        val hub = FeatureHub()
        hub.fileAnswer = { Result.failure(FileFailure("Too large.", status = 413, code = "too_large", size = 30_000_000, max = 20_971_520)) }
        viewer(hub, "$ROOT/huge.md")
        waitForText(str(R.string.fp_download_instead))
    }

    @Test fun aPlansRowOpensFromASessionWhoseFolderIsClosed() {
        val closed = Samples.session("s-closed", "Outside", com.prudnikweb.hypernova.data.SessionState.Idle, 0).copy(browsable = false)
        val hub = FakeHub(sessions = listOf(closed), approvals = emptyMap(), transcripts = emptyMap())
        val plan = Item.Tool("t1", "Read", "handoff.md", path = "/home/owner/.codex/plans/handoff.md")
        val other = Item.Tool("t2", "Read", "main.ts", path = "/home/owner/work/main.ts")
        rule.setContent {
            Harness(spy) {
                androidx.compose.foundation.layout.Column {
                    TranscriptItem(hub, "s-closed", plan, Modifier)
                    TranscriptItem(hub, "s-closed", other, Modifier)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("main.ts", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), spy.opened)
        rule.onNodeWithText("handoff.md", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("file:/home/owner/.codex/plans/handoff.md"), spy.opened)
    }
}
