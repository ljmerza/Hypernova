package com.prudnikweb.hypernova.ui.onboarding

import com.prudnikweb.hypernova.data.PairLinks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The scanner against the real thing: the lines `hypernova pair` draws (protocol/pair-qr.json, which
 * daemon/test/linux-cli.test.ts holds to daemon/src/cli/qr.ts), turned into camera frames the way a
 * terminal puts them on a screen, read by [QrFrames], exactly what the camera's frames go through.
 * No real camera or real screen is in this: the "photographs" are drawn here.
 */
class QrFramesTest {
    private class Code(val link: String, val lines: List<String>)

    private val codes: List<Code> by lazy {
        val all = JSONObject(javaClass.classLoader!!.getResource("pair-qr.json")!!.readText()).getJSONArray("codes")
        List(all.length()) { i ->
            val c = all.getJSONObject(i)
            val lines = c.getJSONArray("lines")
            Code(c.getString("link"), List(lines.length()) { lines.getString(it) })
        }
    }

    /** Light 0..255 per pixel, row by row. */
    private class Frame(val w: Int, val h: Int) {
        val px = ByteArray(w * h)
        operator fun set(x: Int, y: Int, v: Int) { px[y * w + x] = v.coerceIn(0, 255).toByte() }
        operator fun get(x: Int, y: Int): Int = px[y * w + x].toInt() and 0xff
        fun fill(v: Int) = px.fill(v.toByte())
    }

    /**
     * The lines in a terminal's cells: each cell [cw] wide and [ch] high, the upper half lit by ▀ and
     * █, the lower by ▄ and █, in [dark] on [light]. [gap] rows of [light] show between lines, as a
     * font whose blocks don't reach the line's full height leaves them.
     */
    private fun terminal(lines: List<String>, cw: Int, ch: Int, gap: Int = 0, dark: Int = 0, light: Int = 255, margin: Int = 24): Frame {
        val cols = lines.maxOf { it.length }
        val f = Frame(cols * cw + margin * 2, lines.size * ch + margin * 2)
        f.fill(light)
        for ((row, line) in lines.withIndex()) for ((col, c) in line.withIndex()) {
            val top = c == '▀' || c == '█'
            val bottom = c == '▄' || c == '█'
            for (y in 0 until ch - gap) for (x in 0 until cw) {
                if (if (y < (ch - gap) / 2) top else bottom) f[margin + col * cw + x, margin + row * ch + y] = dark
            }
        }
        return f
    }

    /** The picture as a phone might see it: turned, smaller, blurred, dim, with sensor noise, in the middle of a wider frame. */
    private fun photo(src: Frame, w: Int, h: Int, degrees: Double, scale: Double, blur: Int, floor: Int, ceil: Int, seed: Long = 7, surround: Int = 40): Frame {
        val out = Frame(w, h)
        val a = Math.toRadians(degrees)
        val rnd = Random(seed)
        fun at(x: Int, y: Int): Int? = if (x < 0 || y < 0 || x >= src.w || y >= src.h) null else src[x, y]
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - w / 2.0) / scale
            val dy = (y - h / 2.0) / scale
            val sx = cos(a) * dx - sin(a) * dy + src.w / 2.0
            val sy = sin(a) * dx + cos(a) * dy + src.h / 2.0
            val fx = floor(sx).toInt()
            val fy = floor(sy).toInt()
            var sum = 0
            var n = 0
            for (oy in -blur..blur) for (ox in -blur..blur) {
                sum += at(fx + ox, fy + oy) ?: surround
                n++
            }
            val light = sum / n
            out[x, y] = floor + (ceil - floor) * light / 255 + rnd.nextInt(13) - 6
        }
        return out
    }

    private fun read(f: Frame) = QrFrames.read(f.px, f.w, f.w, f.h)

    /**
     * A camera never holds still, and the scanner reads every frame: the screen through a few poses,
     * as a second of frames would be, must read as the link, and never as anything else. A single
     * dead-straight frame can miss even a perfect code (ZXing's detector, seen on 3 of 60 sample links
     * with every module exact; a bit of a turn reads them all), so one frame isn't the test.
     */
    private fun assertReads(c: Code, screen: Frame, how: String) {
        val poses = listOf(2.0 to 0.9, -6.0 to 0.85, 11.0 to 0.75, -17.0 to 0.65, 4.0 to 1.0, -3.0 to 0.6, 23.0 to 0.7, 8.0 to 0.8)
        val reads = poses.mapIndexed { i, (degrees, scale) -> read(photo(screen, 1280, 720, degrees, scale, blur = 1, floor = 30 + i * 4, ceil = 215 - i * 4, seed = i.toLong())) }
        for (text in reads) if (text != null) assertEquals(how, c.link, text)
        val read = reads.count { it != null }
        println("$how: ${c.link.length} bytes, read in $read of ${poses.size} frames")
        assertTrue("$how: read in only $read of ${poses.size} frames", read >= 2)
        assertNotNull(how, PairLinks.parse(reads.first { it != null }))
    }

    @Test fun readsWhatTheTerminalDraws() {
        for (c in codes) assertReads(c, terminal(c.lines, cw = 8, ch = 16), "half blocks")
    }

    /**
     * A font or a line spacing whose blocks don't fill the line leaves light stripes through the code,
     * which a frame close to straight reads with the rows closed up (QrFrames). At these spacings the
     * longest link here, with its four addresses, doesn't reliably read: Paste is there for that.
     */
    @Test fun readsItWithGapsBetweenTheTerminalsLines() {
        for (c in codes) assertReads(c, terminal(c.lines, cw = 8, ch = 17, gap = 1), "a 1 pixel line between rows")
        val usual = codes.first()
        assertReads(usual, terminal(usual.lines, cw = 9, ch = 20, gap = 2), "line spacing 1.1")
        assertReads(usual, terminal(usual.lines, cw = 9, ch = 22, gap = 4), "line spacing 1.2")
    }

    /** A pipe or a log has no colours of its own: light blocks on a dark terminal, a photo negative of the code. */
    @Test fun readsItLightOnDarkToo() {
        for (c in codes) assertReads(c, terminal(c.lines, cw = 8, ch = 16, dark = 235, light = 20), "inverted")
    }

    /** A camera's plane is usually wider than its picture: the rest of each row is padding. */
    @Test fun readsAFrameWithPaddingAtTheEndOfEachRow() {
        val c = codes.first()
        val f = terminal(c.lines, cw = 8, ch = 16)
        val stride = f.w + 64
        val padded = ByteArray(stride * f.h) { 90 }
        for (y in 0 until f.h) System.arraycopy(f.px, y * f.w, padded, y * stride, f.w)
        assertEquals(c.link, QrFrames.read(padded, stride, f.w, f.h))
    }

    @Test fun aFrameWithNoCodeReadsAsNothing() {
        assertNull(read(Frame(640, 480).also { it.fill(128) }))
        val rnd = Random(3)
        val noise = Frame(640, 480)
        for (y in 0 until noise.h) for (x in 0 until noise.w) noise[x, y] = rnd.nextInt(256)
        assertNull(read(noise))
    }

    @Test fun aCodeTooFarOffIsNotMisread() {
        // Far too few pixels a module: it either reads as nothing or as the link itself, never as something else.
        val c = codes.first()
        val text = read(photo(terminal(c.lines, cw = 8, ch = 16), 1280, 720, degrees = 3.0, scale = 0.12, blur = 1, floor = 60, ceil = 190))
        assert(text == null || text == c.link) { "misread" }
    }
}
