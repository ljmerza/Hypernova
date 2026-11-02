package com.prudnikweb.hypernova.data

/**
 * What a web page's top bar says (ui/web/WebScreen.kt). A preview (a localhost link, a file opened
 * as a page; docs/PROTOCOL.md, "Artifacts and previews") comes through the server's own address,
 * which means nothing to the owner: its title is what he tapped ("localhost:5173", "index.html"),
 * its subtitle the page's own <title> when it has one. Any other page (the testing checklist): its
 * <title>, else the name it was opened with, over its address.
 */
object WebTitle {
    data class Bar(val title: String, val subtitle: String?)

    /** [label]: what it was opened as; [pageTitle]: the WebView's; [address]: the page's host and port. */
    fun bar(label: String, pageTitle: String?, address: String, preview: Boolean): Bar {
        val page = pageTitle?.trim()?.takeIf { own(it, label, address) }
        return if (preview) Bar(label, page) else Bar(page ?: label, address)
    }

    /** A title the page gave itself: a WebView with none says its address instead. */
    private fun own(t: String, label: String, address: String): Boolean =
        t.isNotEmpty() && !t.startsWith("http") && t != label && (address.isEmpty() || !t.startsWith(address))
}
