package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.choose
import com.prudnikweb.hypernova.desktop.DesktopRig.inPane
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import com.prudnikweb.hypernova.desktop.ui.BOX_TAG
import com.prudnikweb.hypernova.desktop.ui.ChatDrafts
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** The box's "/" list and its attachments, with the phone's rules, through the real screen over [FakeDaemon] on real mutual TLS. */
@OptIn(ExperimentalTestApi::class)
class ChatBoxExtrasTest {
    private val shell get() = DesktopRig.shell
    private lateinit var d: FakeDaemon
    private val name = "Stream invoice exports"
    private val prompt = "/v1/sessions/s1/prompt"
    private val upload = "/v1/attachments"
    private val folder: File = Files.createTempDirectory("hn-attach").toFile()
    private fun word(key: Str) = DesktopStrings.getString(key)

    /** Stands in for the file dialog and the clipboard, which a test scene hasn't got. */
    private class Picks(val files: List<File> = emptyList(), val pasted: File? = null) : DeskActions {
        override fun openFolder(folder: File) = false
        override fun copy(text: String) {}
        override fun pickFiles(title: String) = files
        override fun clipboardImage() = pasted
    }

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name) { put("projectName", "invoices").put("cwd", "/home/dev/invoices") }))
        d.ok("POST", prompt, JSONObject().put("session", session("s1", state = "working", title = name)))
        d.ok(
            "GET", "/v1/commands",
            JSONObject().put(
                "commands",
                JSONArray()
                    .put(JSONObject().put("name", "compact").put("description", "Compact the conversation").put("source", "builtin").put("argumentHint", "[instructions]"))
                    .put(JSONObject().put("name", "clear").put("description", "Start the conversation again").put("source", "builtin"))
                    .put(JSONObject().put("name", "release-notes").put("description", "Write the notes").put("source", "user")),
            ),
        )
        DesktopRig.main {
            ChatDrafts.set("s1", "")
            AttachDrafts.take("s1")
            AttachDrafts.takeBack("s1")
            shell.settings = false
        }
    }

    @After fun tearDown() {
        DesktopRig.reset()
        d.close()
        folder.deleteRecursively()
    }

    private fun ComposeUiTest.up() {
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { (Core.live?.sessions?.size ?: 0) >= 1 }
        waitForIdle()
        choose(name)
        waitForIdle()
    }

    private fun ComposeUiTest.box() = onNode(hasTestTag(BOX_TAG))
    private fun ComposeUiTest.clip() = onNode(hasContentDescription(word(Str.attach)) and hasClickAction())

    private fun uploads(answer: String = "/srv/attachments/one.txt") = d.ok(
        "POST", upload, JSONObject().put("attachment", JSONObject().put("path", answer).put("name", "one.txt").put("mime", "text/plain").put("size", 3)),
    )

    private fun withDesk(desk: DeskActions, block: () -> Unit) {
        val before = shell.desk
        shell.desk = desk
        try {
            block()
        } finally {
            shell.desk = before
        }
    }

    private fun png(width: Int, height: Int, file: File): File {
        val surface = Surface.makeRasterN32Premul(width, height)
        file.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return file
    }

    // ─── The "/" list ───

    @Test fun aSlashListsTheCommandsAndAClickPutsOneIn() = runDesktopComposeUiTest(1400, 1000) {
        up()
        box().performTextInput("/")
        until("the list") { runCatching { inPane("/release-notes", substring = true).assertIsDisplayed() }.isSuccess }
        inPane("/release-notes", substring = true).performClick()
        waitForIdle()
        assertEquals("/release-notes ", ChatDrafts.of("s1"))
    }

    @Test fun clearInTheListSendsAtOnceAndLeavesTheBoxEmpty() = runDesktopComposeUiTest(1400, 1000) {
        up()
        box().performTextInput("/cl")
        until("the list") { runCatching { inPane("/clear", substring = true).assertIsDisplayed() }.isSuccess }
        inPane("/clear", substring = true).performClick()
        until("it's sent") { d.count("POST", prompt) == 1 }
        assertEquals("/clear", d.calls("POST", prompt).last().json.getString("text"))
        assertEquals("", ChatDrafts.of("s1"))
    }

    @Test fun tabPutsTheBestMatchIn() = runDesktopComposeUiTest(1400, 1000) {
        up()
        box().performTextInput("/comp")
        until("the list") { runCatching { inPane("/compact", substring = true).assertIsDisplayed() }.isSuccess }
        box().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        assertEquals("/compact ", ChatDrafts.of("s1"))
    }

    @Test fun aCodexSessionHasNoList() = runDesktopComposeUiTest(1400, 1000) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name) { put("agent", "codex") }))
        up()
        box().performTextInput("/")
        waitForIdle()
        assertEquals(0, d.count("GET", "/v1/commands"))
    }

    // ─── Attachments ───

    @Test fun aChosenFileUploadsAndGoesWithThePrompt() = runDesktopComposeUiTest(1400, 1000) {
        uploads()
        val file = File(folder, "one.txt").apply { writeText("abc") }
        up()
        withDesk(Picks(files = listOf(file))) {
            clip().performClick()
            until("uploaded") { d.count("POST", upload) == 1 && AttachDrafts.of("s1").singleOrNull()?.attachment != null }
            box().performTextInput("see this")
            box().performKeyInput { pressKey(Key.Enter) }
            until("the prompt") { d.count("POST", prompt) == 1 }
        }
        val sent = d.calls("POST", prompt).single().json
        assertEquals("/srv/attachments/one.txt", sent.getJSONArray("attachments").getString(0))
        assertTrue("the box's files went with it", AttachDrafts.of("s1").isEmpty())
    }

    @Test fun aFailedUploadStaysAndStopsTheSendTillItGoes() = runDesktopComposeUiTest(1400, 1000) {
        d.on("POST", upload) { FakeDaemon.refusal(500, "nope", "The disk is full") }
        val file = File(folder, "one.txt").apply { writeText("abc") }
        up()
        withDesk(Picks(files = listOf(file))) {
            clip().performClick()
            until("it failed") { AttachDrafts.of("s1").singleOrNull()?.failed != null }
            until("it says which") { shell.toast?.contains("one.txt") == true }
            box().performTextInput("see this")
            box().performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("nothing goes without it", 0, d.count("POST", prompt))
            assertEquals("see this", ChatDrafts.of("s1"))
        }
    }

    @Test fun aPictureOnTheClipboardAttachesAndItsCopyGoes() = runDesktopComposeUiTest(1400, 1000) {
        uploads("/srv/attachments/pasted.png")
        val pasted = png(40, 30, File(folder, "pasted.png"))
        up()
        withDesk(Picks(pasted = pasted)) {
            box().performClick()
            waitForIdle()
            box().performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.V)
                keyUp(Key.CtrlLeft)
            }
            until("uploaded") { AttachDrafts.of("s1").singleOrNull()?.attachment != null }
        }
        until("our copy's gone") { !pasted.exists() }
        assertTrue(AttachDrafts.of("s1").single().isImage)
    }

    @Test fun pasteWithNoPictureIsTheBoxsOwn() = runDesktopComposeUiTest(1400, 1000) {
        up()
        withDesk(Picks()) {
            box().performClick()
            waitForIdle()
            box().performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.V)
                keyUp(Key.CtrlLeft)
            }
            waitForIdle()
            assertTrue(AttachDrafts.of("s1").isEmpty())
        }
    }

    // ─── The phone's rules ───

    @Test fun aBigPhotoGoesAt2048AndASmallPngAsItIs() = runBlocking {
        val big = png(4000, 1000, File(folder, "big.png"))
        val small = png(100, 100, File(folder, "small.png"))
        val out = ByteArrayOutputStream()
        val scaled = DeskAttach.read(big)
        scaled.write(out)
        val image = org.jetbrains.skia.Image.makeFromEncoded(out.toByteArray())
        assertEquals(2048, image.width)
        assertEquals(512, image.height)
        assertEquals("big.png", scaled.name)
        val asIs = DeskAttach.read(small)
        val again = ByteArrayOutputStream()
        asIs.write(again)
        assertTrue(again.toByteArray().contentEquals(small.readBytes()))
    }

    @Test fun anotherFileMustFit() {
        assertTrue(DeskAttach.fits(image = false, size = DeskAttach.MAX_BYTES))
        assertFalse(DeskAttach.fits(image = false, size = DeskAttach.MAX_BYTES + 1))
        assertTrue("a photo is made smaller", DeskAttach.fits(image = true, size = DeskAttach.MAX_BYTES + 1))
    }
}
