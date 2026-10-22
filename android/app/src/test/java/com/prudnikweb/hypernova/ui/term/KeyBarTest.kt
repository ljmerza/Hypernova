package com.prudnikweb.hypernova.ui.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The key bar's sticky Ctrl turns the next letter into its control code (ui/term/TerminalScreen.kt). */
class KeyBarTest {
    @Test fun ctrlWithALetterIsItsControlCode() {
        assertEquals('\u0012', ctrlCode('r'))
        assertEquals('\u0012', ctrlCode('R'))
        assertEquals('\u0003', ctrlCode('c'))
        assertEquals('\u001b', ctrlCode('['))
        assertNull(ctrlCode('1'))
        assertNull(ctrlCode(' '))
    }
}
