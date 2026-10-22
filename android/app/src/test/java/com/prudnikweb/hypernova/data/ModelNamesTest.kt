package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A model's name as its chip shows it (data/ModelNames.kt). */
class ModelNamesTest {
    @Test fun theChipDropsTheProvidersPrefixAndClaude() {
        assertEquals("opus", ModelNames.chip("opus"))
        assertEquals("Opus 5.5", ModelNames.chip("Opus 5.5"))
        assertEquals("gpt-5", ModelNames.chip("openai/gpt-5"))
        assertEquals("Sonnet 4.5", ModelNames.chip("anthropic/claude-sonnet-4.5"))
        assertEquals("Opus 5.5", ModelNames.chip("claude-opus-5-5"))
        // Nothing left after the cut: the name as it came.
        assertEquals("claude-", ModelNames.chip("claude-"))
        assertEquals("x/", ModelNames.chip("x/"))
    }

    @Test fun aFullIdLosesItsDateAndKeepsItsMillionAsAMark() {
        // The ids the Mac now reports as the session's model (a reply's, the status line's).
        assertEquals("Opus 4.5", ModelNames.chip("claude-opus-4-5-20251101"))
        assertEquals("Sonnet 4", ModelNames.chip("claude-sonnet-4-20250514"))
        assertEquals("Opus 4.6 1M", ModelNames.chip("claude-opus-4-6[1m]"))
        assertEquals("Sonnet 4.5 1M", ModelNames.chip("claude-sonnet-4-5-20250929[1M]"))
        assertEquals("opus 1M", ModelNames.chip(" opus[1m] "))
        assertEquals("Sonnet 3.5", ModelNames.chip("claude-3-5-sonnet-20241022"))
        assertEquals("Opus 3", ModelNames.chip("claude-3-opus-20240229"))
        // Another provider's ids stay as they are.
        assertEquals("glm-5.3", ModelNames.chip("z-ai/glm-5.3"))
        assertEquals("qwen3-coder:free", ModelNames.chip("qwen/qwen3-coder:free"))
    }

    @Test fun theFamilyIsTheNameBeforeItsVersion() {
        assertEquals("Opus", ModelNames.family("Opus 5.5"))
        assertEquals("Sonnet", ModelNames.family("Sonnet 4.5"))
        assertEquals("Sonnet", ModelNames.family("anthropic/claude-sonnet-4.5"))
        assertEquals("gpt", ModelNames.family("openai/gpt-5"))
        assertEquals("Opus", ModelNames.family("claude-opus-5-5-20260101"))
        assertNull(ModelNames.family("opus"))
    }

    @Test fun anAliasAndAnIdOfItsFamilyAreTheSameModel() {
        assertTrue(ModelNames.same("claude-opus-5-5", "claude-opus-5-5-20260101"))
        assertTrue(ModelNames.same("anthropic/claude-sonnet-4.5", "claude-sonnet-4-5[1m]"))
        assertTrue(ModelNames.same("opus", "claude-opus-5-5"))
        assertTrue(ModelNames.same("claude-sonnet-5-5", "sonnet"))
        assertFalse(ModelNames.same("opus", "claude-sonnet-5-5"))
        assertFalse(ModelNames.same("claude-opus-4-6", "claude-opus-5-5"))
        assertFalse(ModelNames.same(null, "opus"))
        assertFalse(ModelNames.same("", ""))
    }
}
