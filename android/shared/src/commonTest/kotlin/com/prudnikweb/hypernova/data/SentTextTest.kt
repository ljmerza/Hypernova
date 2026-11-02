package com.prudnikweb.hypernova.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This phone's copy of a prompt it sent is replaced by the Mac's copy (LiveHub's sameSend), even
 * when the Mac logged it with a space more or less: the owner's prompt-spellcheck plugin made
 * "clip board" "clipboard", and the chat drew the message twice.
 */
class SentTextTest {
    @Test fun aSpaceTheMacDroppedOrAddedStillMatches() {
        assertTrue(sameSentText(
            "links in their own black section like that if you hold press should copy to clip board",
            "links in their own black section like that if you hold press should copy to clipboard",
        ))
        assertTrue(sameSentText("fix  the\nsky ", "fix the sky"))
        assertTrue(sameSentText("! ls", "!ls"))
    }

    @Test fun otherWordsDontMatch() {
        assertFalse(sameSentText("copy to clip board", "copy to clipboard please"))
        assertFalse(sameSentText("fix the sky", "fix the sea"))
    }
}
