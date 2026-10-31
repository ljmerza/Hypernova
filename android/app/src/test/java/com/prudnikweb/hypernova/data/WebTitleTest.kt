package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** A web page's top bar (data/WebTitle.kt): a preview says what was tapped, never the server's address. */
class WebTitleTest {
    private val proxy = "192.168.1.76:47323"

    @Test fun aPreviewIsTitledWithWhatWasTappedAndThePagesOwnTitleUnder() {
        assertEquals(WebTitle.Bar("localhost:5173", "Vite + React"), WebTitle.bar("localhost:5173", "Vite + React", proxy, preview = true))
        assertEquals(WebTitle.Bar("index.html", "My report"), WebTitle.bar("index.html", "My report", proxy, preview = true))
    }

    /** No <title> of its own (or none yet): the WebView says the address, which never shows. */
    @Test fun aPreviewWithoutATitleOfItsOwnHasNoSubtitle() {
        assertEquals(WebTitle.Bar("localhost:5173", null), WebTitle.bar("localhost:5173", null, proxy, preview = true))
        assertEquals(WebTitle.Bar("localhost:5173", null), WebTitle.bar("localhost:5173", "  ", proxy, preview = true))
        assertEquals(WebTitle.Bar("localhost:5173", null), WebTitle.bar("localhost:5173", "https://$proxy/p/tok/", proxy, preview = true))
        assertEquals(WebTitle.Bar("localhost:5173", null), WebTitle.bar("localhost:5173", "$proxy/p/tok/", proxy, preview = true))
        // The file's name as its title: said once.
        assertEquals(WebTitle.Bar("index.html", null), WebTitle.bar("index.html", "index.html", proxy, preview = true))
    }

    @Test fun anyOtherPageKeepsItsTitleOverItsAddress() {
        assertEquals(WebTitle.Bar("Testing checklist", "127.0.0.1:47321"), WebTitle.bar("Checklist", "Testing checklist", "127.0.0.1:47321", preview = false))
        assertEquals(WebTitle.Bar("Checklist", "127.0.0.1:47321"), WebTitle.bar("Checklist", "http://127.0.0.1:47321/qa", "127.0.0.1:47321", preview = false))
    }
}
