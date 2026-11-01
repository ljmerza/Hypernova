package com.prudnikweb.hypernova.ui.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The terminal's two pages (ui/term/TermEngine.kt): xterm.js stays the default, and the WebView
 * serves each page only the files directly in an engine's folder. The pages themselves run in a
 * headless Chromium: scripts/term-pages.mjs.
 */
class TermEngineTest {
    private val assets = File("src/main/assets")

    @Test fun xtermIsTheDefault() {
        assertEquals(TermEngine.Xterm, TermEngine.of(null))
        assertEquals(TermEngine.Xterm, TermEngine.of("nonsense"))
        assertEquals(TermEngine.Ghostty, TermEngine.of("Ghostty"))
        assertEquals("https://appassets.androidplatform.net/term/index.html", TermEngine.Xterm.page)
        assertEquals("https://appassets.androidplatform.net/term-ghostty/index.html", TermEngine.Ghostty.page)
    }

    @Test fun onlyFilesInAnEnginesFolder() {
        assertEquals("term/xterm.js", termAsset("/term/xterm.js"))
        assertEquals("term-ghostty/ghostty-web.js", termAsset("/term-ghostty/ghostty-web.js"))
        // The Ghostty page's font is term/'s.
        assertEquals("term/JetBrainsMono.ttf", termAsset("/term/JetBrainsMono.ttf"))
        assertNull(termAsset(null))
        assertNull(termAsset("/term/"))
        assertNull(termAsset("/term/../mermaid/mermaid.min.js"))
        assertNull(termAsset("/term/sub/x.js"))
        assertNull(termAsset("/mermaid/index.html"))
        assertNull(termAsset("/termx/index.html"))
        assertNull(termAsset("/term-ghostty/..%2Fx"))
    }

    @Test fun everyFileThePagesAskForIsThere() {
        for (e in TermEngine.entries) {
            val page = File(assets, "${e.dir}/index.html")
            assertTrue("$page", page.isFile)
            // src="…" and url("…") of each page, resolved as the WebView would, then served by termAsset.
            val refs = Regex("""(?:src|href)="([^"]+)"|url\("([^"]+)"\)""").findAll(page.readText())
                .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .filter { !it.startsWith("data:") }
                .toList()
            assertTrue("${e.dir} has its scripts", refs.any { it.endsWith(".js") })
            for (ref in refs) {
                val path = java.net.URI("https://h/${e.dir}/index.html").resolve(ref).path
                val asset = termAsset(path)
                assertTrue("${e.dir}: $ref → $path", asset != null && File(assets, asset).isFile)
            }
        }
    }
}
