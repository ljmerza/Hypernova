package com.prudnikweb.hypernova.ui.screens

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Picked
import com.prudnikweb.hypernova.ui.share.Destinations
import com.prudnikweb.hypernova.ui.share.LauncherShortcuts
import com.prudnikweb.hypernova.ui.share.ShareLaunch
import com.prudnikweb.hypernova.ui.share.ShareStage
import com.prudnikweb.hypernova.ui.share.readShared
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * Share into Hypernova and the launcher's shortcuts (FEATURES N7; ui/share): what a share sheet's
 * intent hands over, the screen's choice of where it goes (drawn), the files copied while the
 * sender lends them, the hand-over to the new-session sheet, and the long-press menu's New session
 * and recent projects.
 */
@RunWith(RobolectricTestRunner::class)
class ShareTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    /** A sender's files: a name, the size it says, the bytes (null: it can't be read). */
    class Sender : ContentProvider() {
        companion object { val files = HashMap<String, Triple<String, Long, String>>() }
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = files[uri.lastPathSegment]?.third
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val (name, size) = files.getValue(uri.lastPathSegment!!)
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(name, size)) }
        }
        override fun openFile(uri: Uri, mode: String) = throw java.io.FileNotFoundException("not lent")
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private fun sent(key: String) = Uri.parse("content://test.sender/$key")

    private fun lend(key: String, name: String, bytes: ByteArray?, size: Long = bytes?.size?.toLong() ?: 10, mime: String = "text/plain") {
        Sender.files[key] = Triple(name, size, mime)
        if (bytes != null) shadowOf(context.contentResolver).registerInputStreamSupplier(sent(key)) { ByteArrayInputStream(bytes) }
    }

    @Before fun setUp() {
        resetPrefs()
        Robolectric.setupContentProvider(Sender::class.java, "test.sender")
        Sender.files.clear()
        ShareLaunch.newSession = null
        ShareLaunch.takeText()
    }

    // ─── What the share sheet hands over ───

    @Test fun aPageSharedFromABrowserIsItsTitleThenItsLink() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, " Parser notes ").putExtra(Intent.EXTRA_TEXT, " https://example.com/parser ")
        val s = readShared(i)
        assertEquals("Parser notes\nhttps://example.com/parser", s.text)
        assertTrue(s.uris.isEmpty())
        // A subject the text already holds isn't said twice; nothing at all is nothing.
        assertEquals("See https://example.com", readShared(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, "See").putExtra(Intent.EXTRA_TEXT, "See https://example.com")).text)
        assertNull(readShared(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "  ")).text)
    }

    @Test fun filesComeFromTheStreamAndTheClipOnceEachAndOnlyOursToRead() {
        val a = sent("a")
        val b = sent("b")
        val i = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b, Uri.parse("https://example.com/x.png")))
        i.clipData = ClipData.newRawUri("files", a).apply { addItem(ClipData.Item(Uri.parse("file:///sdcard/notes.txt"))) }
        assertEquals(listOf(a, Uri.parse("file:///sdcard/notes.txt"), b), readShared(i).uris)
    }

    // ─── The files, copied while lent ───

    @Test fun sharedFilesAreCopiedNowAndTooBigOrUnreadableOnesAreCounted() = runBlocking {
        lend("ok", "notes.txt", "Trim first.".toByteArray())
        lend("big", "dump.bin", ByteArray(1), size = Picked.MAX_BYTES + 1, mime = "application/octet-stream")
        lend("gone", "gone.txt", null)
        val r = ShareStage.stage(context, listOf(sent("ok"), sent("big"), sent("gone")))
        assertEquals(1, r.uris.size)
        assertEquals(1, r.tooBig)
        assertEquals(1, r.failed)
        val copy = r.uris.single()
        assertEquals("content", copy.scheme)
        assertEquals("${context.packageName}.files", copy.authority)
        assertTrue(copy.path!!.endsWith("/notes.txt"))
    }

    // ─── Where it goes ───

    @Test fun theShareScreenOffersANewSessionTheRecentSessionsAndTheProjects() {
        val sessions = mutableListOf<String>()
        val projects = mutableListOf<String?>()
        rule.setContent {
            Harness(spy) {
                Destinations(Samples.sessions(), Samples.projects(), onSession = { sessions += it }, onProject = { projects += it }, bottom = 0.dp)
            }
        }
        rule.onNodeWithText(str(R.string.share_new_session)).performClick()
        rule.onNodeWithText("Fix the build").performClick()
        // The project's row (a session's row names its project too).
        rule.onNode(hasText("infra") and hasText(context.resources.getQuantityString(R.plurals.sessions_count, 1, 1))).performClick()
        assertEquals(listOf(Samples.BUILD), sessions)
        assertEquals(listOf(null, "/home/owner/projects/infra"), projects)
        rule.snap("share_destinations")
    }

    @Test fun withNothingYetTheShareScreenSaysSo() {
        rule.setContent { Harness(spy) { Destinations(emptyList(), emptyList(), onSession = {}, onProject = {}, bottom = 0.dp) } }
        rule.onNodeWithText(str(R.string.share_no_sessions)).assertExists()
        rule.onNodeWithText(str(R.string.share_new_session)).assertExists()
    }

    // ─── The hand-over to the new-session sheet ───

    @Test fun aShortcutsIntentOpensTheSheetOnItsProjectAndSharedTextIsTakenOnce() {
        val i = Intent(context, MainActivity::class.java).setAction(ShareLaunch.ACTION_NEW_SESSION).putExtra(ShareLaunch.EXTRA_PROJECT, "/home/owner/projects/infra")
        assertTrue(ShareLaunch.owns(i))
        assertFalse(ShareLaunch.owns(Intent(Intent.ACTION_MAIN)))
        ShareLaunch.handle(i)
        val first = ShareLaunch.newSession!!
        assertEquals("/home/owner/projects/infra", first.projectPath)
        assertFalse("the extra is used once", i.hasExtra(ShareLaunch.EXTRA_PROJECT))
        assertNull(ShareLaunch.takeText())
        // From the share screen: where the sheet chooses, with the shared text for its box.
        ShareLaunch.openNewSession(null, "https://example.com/parser")
        val second = ShareLaunch.newSession!!
        assertNull(second.projectPath)
        assertTrue("a new hand-over each time", second.id > first.id)
        assertEquals("https://example.com/parser", ShareLaunch.takeText())
        assertNull(ShareLaunch.takeText())
    }

    // ─── The launcher's shortcuts ───

    @Test fun theLauncherOffersNewSessionAlways() {
        val xml = context.resources.getXml(R.xml.shortcuts)
        val actions = mutableListOf<String>()
        var shortcuts = 0
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType != XmlPullParser.START_TAG) continue
            if (xml.name == "shortcut") shortcuts++
            if (xml.name == "intent") actions += xml.getAttributeValue("http://schemas.android.com/apk/res/android", "action")
        }
        assertEquals(1, shortcuts)
        assertEquals(listOf(ShareLaunch.ACTION_NEW_SESSION), actions)
    }

    @Test fun theThreeProjectsWorkedInLatelyAreTheLaunchersOwn() {
        val projects = listOf(Triple("/p/app", "app", 10L), Triple("/p/infra", "infra", 50L), Triple("/p/notes", "notes", 5L), Triple("/p/web", "web", 40L))
        // Sessions' folders first, newest first; only folders the Mac lists; then the projects by their activity.
        val sessions = listOf(Triple<String?, String, Long>("/p/notes", "notes", 100L), Triple("/tmp/scratch", "scratch", 90L), Triple("/p/notes", "notes", 80L), Triple(null, "x", 70L))
        val recent = LauncherShortcuts.recent(sessions, projects)
        assertEquals(listOf("/p/notes", "/p/infra", "/p/web"), recent.map { it.path })
        // With no projects listed, any session's folder.
        assertEquals(listOf("/tmp/scratch"), LauncherShortcuts.recent(listOf(Triple("/tmp/scratch", "scratch", 1L)), emptyList()).map { it.path })

        LauncherShortcuts.publish(context, recent)
        val dynamic = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
        assertEquals(listOf("project:/p/notes", "project:/p/infra", "project:/p/web"), dynamic.sortedBy { it.rank }.map { it.id })
        val first = dynamic.first { it.id == "project:/p/notes" }
        assertEquals("notes", first.shortLabel)
        assertEquals(context.getString(R.string.shortcut_project_long, "notes"), first.longLabel)
        val intent = first.intent
        assertEquals(ShareLaunch.ACTION_NEW_SESSION, intent.action)
        assertEquals("/p/notes", intent.getStringExtra(ShareLaunch.EXTRA_PROJECT))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        // A tap on it opens the sheet on that project.
        ShareLaunch.handle(intent)
        assertEquals("/p/notes", ShareLaunch.newSession?.projectPath)
    }
}
