package com.prudnikweb.hypernova.ui.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What an address tapped in the terminal may be: whatever a program printed can arrive here (ui/term/TerminalScreen.kt). */
class TermLinkTest {
    @Test fun theWebAndADevServerGoThrough() {
        assertEquals("https://github.com/x?y=1#z", termLink("https://github.com/x?y=1#z"))
        assertEquals("http://a.org", termLink("http://a.org"))
        assertEquals("HTTPS://A.ORG/b", termLink("HTTPS://A.ORG/b"))
        assertEquals("http://localhost:5173/", termLink("http://localhost:5173/"))
    }

    @Test fun nothingElseDoes() {
        for (bad in listOf(
            "",
            "javascript:alert(1)",
            "intent://x#Intent;scheme=hypernova;end",
            "file:///etc/passwd",
            "content://x/y",
            "hypernova://pair?c=1",
            "data:text/html,hi",
            "httpx://a.org",
            "https://",
            "www.example.com",
        )) assertNull(bad, termLink(bad))
    }

    @Test fun anAddressOfMegabytesDoesNot() {
        assertEquals(8192, termLink("https://a.org/" + "x".repeat(8192 - 14))?.length)
        assertNull(termLink("https://a.org/" + "x".repeat(8192)))
    }
}
