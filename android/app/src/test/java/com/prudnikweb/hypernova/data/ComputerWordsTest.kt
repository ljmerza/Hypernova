package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** A Linux server's words for a Mac's (data/ComputerWords.kt, audit P10). */
class ComputerWordsTest {
    private fun r(s: String) = ComputerWords.reword(s, "server")

    @Test fun theMacBecomesTheServer() {
        assertEquals("Claude Code is signed out on your server", r("Claude Code is signed out on your Mac"))
        assertEquals("Your server isn't answering.", r("Your Mac isn't answering."))
        assertEquals("The server's key for it was turned down.", r("The Mac's key for it was turned down."))
        assertEquals("Forget this server", r("Forget this Mac"))
        assertEquals("Not paired with a server.", r("Not paired with a Mac."))
        assertEquals("Sign in on the server", r("Sign in on the Mac"))
    }

    @Test fun aBareMacIsTheWordToo() {
        assertEquals("Server", r("Mac"))
        assertEquals("Server restarted", r("Mac restarted"))
        assertEquals("Open on the server. Server voice", r("Open on the Mac. Mac voice"))
    }

    @Test fun otherWordsStay() {
        assertEquals("Connected to MacBook Air", r("Connected to MacBook Air"))
        assertEquals("under Machines", r("under Machines"))
        assertEquals("macOS", r("macOS"))
        assertEquals("Nothing to change", r("Nothing to change"))
    }

    @Test fun anArticleFitsTheWord() {
        assertEquals("Not paired with an iMac-less computer", ComputerWords.reword("Not paired with an iMac-less computer", "computer"))
        assertEquals("on a computer", ComputerWords.reword("on a Mac", "computer"))
        assertEquals("on an other", ComputerWords.reword("on a Mac", "other"))
    }
}
