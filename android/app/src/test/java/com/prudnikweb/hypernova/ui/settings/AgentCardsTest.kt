package com.prudnikweb.hypernova.ui.settings

import com.prudnikweb.hypernova.data.CodexState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Agents & providers (ui/settings/Providers.kt): Codex's version against the minimum, and plans in words. */
class AgentCardsTest {
    @Test fun versionsCompareByTheirNumbers() {
        assertTrue(versionBelow("0.150.2", "0.163.0"))
        assertTrue(versionBelow("0.9", "0.163.0"))
        assertFalse(versionBelow("0.163.0", "0.163.0"))
        assertFalse(versionBelow("0.170.1", "0.163.0"))
        assertFalse(versionBelow("1.0", "0.163.0"))
        // A tool's own prefix around the number is read past.
        assertTrue(versionBelow("codex-cli 0.120.0", "0.163.0"))
        // Nothing to compare: not called too old.
        assertFalse(versionBelow("unknown", "0.163.0"))
    }

    @Test fun tooOldOnlyWhenItCantBeUsedAndIsBehind() {
        val base = CodexState(available = false, enabled = false, connected = false, version = "0.150.0", min = "0.163.0")
        assertTrue(codexTooOld(base))
        assertFalse(codexTooOld(base.copy(available = true)))
        assertFalse(codexTooOld(base.copy(version = null)))
        // Up to date but unusable for another reason: its problem says why, not "too old".
        assertFalse(codexTooOld(base.copy(version = "0.170.0")))
        // No minimum from the Mac: an unusable version is too old.
        assertTrue(codexTooOld(base.copy(min = null)))
    }

    @Test fun plansAreWrittenAsNames() {
        assertEquals("Max", planWord("max"))
        assertEquals("Plus", planWord("plus"))
        assertEquals("Pro Lite", planWord("prolite"))
        // A plan the app has no word for (Ultra, 2026-10-31): as Claude Code names it, capitalised.
        assertEquals("Ultra", planWord("ultra"))
        assertNull(planWord(null))
        assertNull(planWord(" "))
    }
}
