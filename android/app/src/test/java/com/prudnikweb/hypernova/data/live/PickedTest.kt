package com.prudnikweb.hypernova.data.live

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.HubException
import com.prudnikweb.hypernova.data.Picked
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A picked file read for upload (Picked.read; docs/PROTOCOL.md, "Attachments"): its name and type
 * as the picker gives them, its bytes as they are, and the phone's own refusals before anything goes:
 * over 30 MB, or a document that can't be read. The picker here is a content provider of the test's.
 */
@RunWith(RobolectricTestRunner::class)
class PickedTest {
    /** The picker's files: a name, the size it reports and the bytes (null: it can't be opened). */
    class Files : ContentProvider() {
        companion object {
            val files = HashMap<String, Triple<String, Long, ByteArray?>>()
            val types = HashMap<String, String>()
        }

        override fun onCreate() = true
        override fun getType(uri: Uri): String? = types[uri.lastPathSegment]
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val (name, size) = files.getValue(uri.lastPathSegment!!)
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(name, size)) }
        }
        // Readable files' bytes come through the resolver's registered streams ([pick]): this is a document that isn't a file.
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw java.io.FileNotFoundException("not a file")
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private fun uri(key: String) = Uri.parse("content://test.picked/$key")

    /** A file the picker gives as [key]: its name, the size it reports, and its bytes (null: it can't be opened). */
    private fun pick(key: String, name: String, size: Long, bytes: ByteArray?, mime: String) {
        Files.files[key] = Triple(name, size, bytes)
        Files.types[key] = mime
        if (bytes != null) shadowOf(context.contentResolver).registerInputStreamSupplier(uri(key)) { ByteArrayInputStream(bytes) }
    }

    @Before fun setUp() {
        Robolectric.setupContentProvider(Files::class.java, "test.picked")
        Files.files.clear()
        Files.types.clear()
    }

    @Test fun aFileGoesAsItIsUnderItsName() = runBlocking {
        val bytes = "%PDF-1.7 the plan".toByteArray()
        pick("doc", "Plan.pdf", bytes.size.toLong(), bytes, "application/pdf")
        val up = Picked.read(context, uri("doc"))
        assertEquals("Plan.pdf", up.name)
        assertEquals("application/pdf", up.mime)
        val out = ByteArrayOutputStream()
        up.write(out)
        assertTrue(bytes.contentEquals(out.toByteArray()))
    }

    @Test fun overThirtyMegabytesIsRefusedOnThePhone() = runBlocking {
        pick("big", "Backup.zip", Picked.MAX_BYTES + 1, ByteArray(1), "application/zip")
        try {
            Picked.read(context, uri("big"))
            fail("read a file over 30 MB")
        } catch (e: HubException) {
            assertEquals(context.getString(R.string.attach_too_big), e.message)
        }
        // Exactly 30 MB is fine.
        pick("most", "Most.zip", Picked.MAX_BYTES, ByteArray(1), "application/zip")
        assertEquals("Most.zip", Picked.read(context, uri("most")).name)
    }

    @Test fun aDocumentThatIsntAFileIsSaidBeforeTheUpload() = runBlocking {
        pick("drive", "Notes", 10L, null, "application/vnd.google-apps.document")
        try {
            Picked.read(context, uri("drive"))
            fail("read a document that can't be opened")
        } catch (e: HubException) {
            assertEquals(context.getString(R.string.attach_unreadable), e.message)
        }
    }
}
