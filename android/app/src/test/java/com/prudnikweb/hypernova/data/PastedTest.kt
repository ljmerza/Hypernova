package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Pasting into the prompt box (data/Pasted.kt): images become attachments, anything else stays a paste. */
class PastedTest {

    @Test fun anImageByItsOwnSpecificTypeOrTheClips() {
        assertEquals(PasteKind.Image, Pasted.kind("image/png", emptyList()))
        assertEquals(PasteKind.Image, Pasted.kind("IMAGE/JPEG", listOf("text/plain")))
        assertEquals(PasteKind.Image, Pasted.kind("image/gif; charset=binary", emptyList()))
        // A keyboard's content without a type the resolver knows: the clip's types say.
        assertEquals(PasteKind.Image, Pasted.kind(null, listOf("image/webp")))
        assertEquals(PasteKind.Image, Pasted.kind(null, listOf("image/png", "image/jpeg")))
        // A generic type says nothing, so a specific clip still does (it was refused, silently, before).
        assertEquals(PasteKind.Image, Pasted.kind("application/octet-stream", listOf("image/png")))
        assertEquals(PasteKind.Image, Pasted.kind("image/*", listOf("image/png")))
    }

    @Test fun nothingSpecificLeavesItToTheFile() {
        for (generic in listOf(null, "", "*/*", "image/*", "application/octet-stream", "binary/octet-stream", "application/unknown", "content/unknown")) {
            assertEquals("$generic", PasteKind.Maybe, Pasted.kind(generic, emptyList()))
            // A clip described by a copy whose provider gave no type, or by a wildcard.
            assertEquals("$generic", PasteKind.Maybe, Pasted.kind(generic, listOf("text/uri-list")))
            assertEquals("$generic", PasteKind.Maybe, Pasted.kind(generic, listOf("image/*")))
            assertEquals("$generic", PasteKind.Maybe, Pasted.kind(generic, listOf("image/png", "text/plain")))
        }
    }

    @Test fun anotherSpecificTypeIsNoImage() {
        assertEquals(PasteKind.NotImage, Pasted.kind("text/plain", listOf("image/png")))
        assertEquals(PasteKind.NotImage, Pasted.kind("application/pdf", emptyList()))
        // SVG is text.
        assertEquals(PasteKind.NotImage, Pasted.kind("image/svg+xml", emptyList()))
    }

    @Test fun eachItemOfAClipGoesWhereItCan() {
        // Words alone: the box's own paste. Nothing at all: said, not silent.
        assertEquals(PasteTo.Box, Pasted.route(null, true, null))
        assertEquals(PasteTo.Refuse, Pasted.route(null, false, null))
        // An image, with its words kept when it has them.
        assertEquals(PasteTo.Attach, Pasted.route("content", false, PasteKind.Image))
        assertEquals(PasteTo.AttachAndText, Pasted.route("content", true, PasteKind.Image))
        // Unknown: the file decides, unless it has words to be pasted instead.
        assertEquals(PasteTo.Attach, Pasted.route("content", false, PasteKind.Maybe))
        assertEquals(PasteTo.Box, Pasted.route("content", true, PasteKind.Maybe))
        // Not an image: its words, else said.
        assertEquals(PasteTo.Box, Pasted.route("content", true, PasteKind.NotImage))
        assertEquals(PasteTo.Refuse, Pasted.route("content", false, PasteKind.NotImage))
        // A web address is words, whatever it points at.
        assertEquals(PasteTo.Address, Pasted.route("https", false, PasteKind.Maybe))
        assertEquals(PasteTo.Address, Pasted.route("HTTP", false, PasteKind.Maybe))
        assertEquals(PasteTo.Box, Pasted.route("https", true, PasteKind.Maybe))
    }

    private fun bytes(vararg b: Int) = b.map { it.toByte() }.toByteArray()

    @Test fun theFirstBytesSayTheImage() {
        assertEquals("image/png", Pasted.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals("image/jpeg", Pasted.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/gif", Pasted.sniff("GIF89a".toByteArray()))
        assertEquals("image/webp", Pasted.sniff("RIFF\u0001\u0002\u0003\u0004WEBPVP8 ".toByteArray()))
        assertEquals("image/bmp", Pasted.sniff("BM".toByteArray()))
        assertEquals("image/heic", Pasted.sniff(bytes(0, 0, 0, 24) + "ftypheic".toByteArray()))
        assertEquals("image/avif", Pasted.sniff(bytes(0, 0, 0, 24) + "ftypavif".toByteArray()))
        // Not images: a PDF, an MP4, RIFF that isn't WebP, too little to say, nothing.
        assertNull(Pasted.sniff("%PDF-1.7".toByteArray()))
        assertNull(Pasted.sniff(bytes(0, 0, 0, 24) + "ftypisom".toByteArray()))
        assertNull(Pasted.sniff("RIFF\u0001\u0002\u0003\u0004WAVEfmt ".toByteArray()))
        assertNull(Pasted.sniff(bytes(0x89)))
        assertNull(Pasted.sniff(ByteArray(0)))
    }

    @Test fun keptUnderTheResolversImageTypeElseItsBytes() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47)
        assertEquals("image/jpeg", Pasted.typeOf("image/jpeg", png))
        assertEquals("image/png", Pasted.typeOf(null, png))
        assertEquals("image/png", Pasted.typeOf("application/octet-stream", png))
        assertEquals("image/png", Pasted.typeOf("image/*", png))
        assertNull(Pasted.typeOf(null, "%PDF".toByteArray()))
        assertEquals("avif", Pasted.extension("image/avif"))
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
