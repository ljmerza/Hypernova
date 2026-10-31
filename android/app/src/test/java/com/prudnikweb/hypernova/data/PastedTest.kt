package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Pasting into the prompt box (data/Pasted.kt): images become attachments, anything else stays a paste. */
class PastedTest {
    @Test fun anImageByItsOwnTypeOrTheClips() {
        assertTrue(Pasted.isImage("image/png", emptyList()))
        assertTrue(Pasted.isImage("IMAGE/JPEG", listOf("text/plain")))
        assertTrue(Pasted.isImage("image/gif", emptyList()))
        // A keyboard's content without a type the resolver knows: the clip's types say.
        assertTrue(Pasted.isImage(null, listOf("image/webp")))
        assertTrue(Pasted.isImage(null, listOf("image/png", "image/jpeg")))
        assertFalse(Pasted.isImage(null, listOf("image/png", "text/plain")))
        assertFalse(Pasted.isImage(null, emptyList()))
        // Text, SVG (text), and a wildcard are pasted as text.
        assertFalse(Pasted.isImage("text/plain", listOf("image/png")))
        assertFalse(Pasted.isImage("image/svg+xml", emptyList()))
        assertFalse(Pasted.isImage(null, listOf("image/*")))
        assertFalse(Pasted.isImage("application/pdf", emptyList()))
    }

    @Test fun namedByWhenAndWhat() {
        assertEquals("png", Pasted.extension("image/png"))
        assertEquals("jpg", Pasted.extension("image/jpeg"))
        assertEquals("gif", Pasted.extension("image/gif; charset=binary"))
        assertEquals("webp", Pasted.extension("image/webp"))
        assertEquals("img", Pasted.extension(null))
        assertEquals("img", Pasted.extension("image/x-something"))
        val c = Calendar.getInstance(TimeZone.getDefault()).apply { clear(); set(2026, Calendar.OCTOBER, 30, 14, 25, 1) }
        assertEquals("pasted-20261030-142501.png", Pasted.name("image/png", c.time, 1))
        assertEquals("pasted-20261030-142501-2.jpg", Pasted.name("image/jpeg", c.time, 2))
    }
}
