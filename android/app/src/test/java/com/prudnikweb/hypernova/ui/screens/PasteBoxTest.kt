package com.prudnikweb.hypernova.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.NewSessionMemory
import com.prudnikweb.hypernova.ui.home.DRAFT
import com.prudnikweb.hypernova.ui.home.NewSessionSheet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Pasting from the clipboard into a box with a paperclip (ui/session/Attach.kt, data/Pasted.kt),
 * through Compose's own paste: the New session sheet's box, on the receiver the session's box shares.
 * A `file:` Uri stands in for a provider's, which gives no type from the resolver: what a screenshot
 * copied from an app whose provider says nothing looks like.
 */
@RunWith(RobolectricTestRunner::class)
class PasteBoxTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val context get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Before fun setUp() {
        resetPrefs()
        NewSessionMemory.draft = ""
        AttachDrafts.take(DRAFT)
    }

    @After fun tearDown() {
        AttachDrafts.take(DRAFT)
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)

    private fun file(name: String, bytes: ByteArray): Uri =
        Uri.fromFile(File(context.cacheDir, name).apply { parentFile?.mkdirs(); writeBytes(bytes) })

    private fun clip(mimes: Array<String>, item: ClipData.Item) = clipboard.setPrimaryClip(ClipData(ClipDescription("copied", mimes), item))

    private fun paste() {
        rule.setContent { Harness(spy) { NewSessionSheet(FakeHub(), initialProject = null, onStarted = {}) } }
        rule.waitForIdle()
        val box = rule.onNode(hasSetTextAction())
        box.performClick()
        rule.waitForIdle()
        // Compose reads what the clipboard holds when the field takes focus.
        rule.waitUntil(5_000) { runCatching { box.fetchSemanticsNode().config.contains(SemanticsActions.PasteText) }.getOrDefault(false) }
        box.performSemanticsAction(SemanticsActions.PasteText)
        rule.waitForIdle()
    }

    private fun attached(n: Int = 1) = rule.waitUntil(5_000) { AttachDrafts.of(DRAFT).size == n }

    @Test fun aScreenshotDescribedAsAnImageAttaches() {
        clip(arrayOf("image/png"), ClipData.Item(file("shot.png", png)))
        paste()
        attached()
        assertTrue(AttachDrafts.of(DRAFT).single().name.endsWith(".png"))
    }

    @Test fun aClipThatDoesntSayItsAnImageAttachesByItsBytes() {
        // Refused before, silently: the clip has no image/* type, so its Uri was read for text, of which there's none.
        clip(arrayOf("text/uri-list"), ClipData.Item(file("noname", png)))
        paste()
        attached()
        assertTrue("named by what it is", AttachDrafts.of(DRAFT).single().name.endsWith(".png"))
    }

    @Test fun aWildcardClipAttachesByItsBytes() {
        clip(arrayOf("image/*"), ClipData.Item(file("wild", png)))
        paste()
        attached()
    }

    @Test fun aFileThatIsNoImageSaysSoRatherThanNothing() {
        clip(arrayOf("text/uri-list"), ClipData.Item(file("notes", "%PDF-1.7 not an image".toByteArray())))
        paste()
        rule.waitUntil(5_000) { str(R.string.paste_unusable) in spy.toasts }
        assertTrue(AttachDrafts.of(DRAFT).isEmpty())
    }

    @Test fun aFileThatCantBeReadSaysSo() {
        clip(arrayOf("image/png"), ClipData.Item(Uri.fromFile(File(context.cacheDir, "not-there.png"))))
        paste()
        rule.waitUntil(5_000) { str(R.string.paste_failed) in spy.toasts }
        assertTrue(AttachDrafts.of(DRAFT).isEmpty())
    }

    @Test fun anImageWithWordsKeepsBoth() {
        clip(arrayOf("image/png"), ClipData.Item("look at this", null, null, file("both.png", png)))
        paste()
        attached()
        rule.onNode(hasSetTextAction()).assert(hasText("look at this"))
    }

    @Test fun plainTextStaysTheBoxsOwnPaste() {
        clipboard.setPrimaryClip(ClipData.newPlainText("words", "just words"))
        paste()
        rule.onNode(hasSetTextAction()).assert(hasText("just words"))
        assertTrue(AttachDrafts.of(DRAFT).isEmpty())
        assertEquals(emptyList<String>(), spy.toasts)
    }

    @Test fun aWebAddressWithNoTextGoesInAsWords() {
        clip(arrayOf("text/uri-list"), ClipData.Item(Uri.parse("https://example.com/shot")))
        paste()
        rule.onNode(hasSetTextAction()).assert(hasText("https://example.com/shot"))
        assertTrue(AttachDrafts.of(DRAFT).isEmpty())
    }

    @Test fun typingAndTheBoxsWordsStillWork() {
        rule.setContent { Harness(spy) { NewSessionSheet(FakeHub(), initialProject = null, onStarted = {}) } }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("/review the plan")
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assert(hasText("/review the plan"))
    }
}
