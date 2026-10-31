package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Settings → Voice: which recognizer a dictation gets, and the extras it is asked with (PhoneDictation.kt). */
class DictationChoiceTest {
    private val phone = DictationEngine.Phone
    private val onDevice = DictationEngine.OnDevice

    @Test
    fun the_default_is_the_phones_recognizer_and_unknown_keys_read_as_it() {
        assertEquals(phone, DictationEngine.DEFAULT)
        assertEquals(phone, DictationEngine.of(null))
        assertEquals(phone, DictationEngine.of("soniox"))
        assertEquals(phone, DictationEngine.of("phone"))
        assertEquals(onDevice, DictationEngine.of("onDevice"))
        for (e in DictationEngine.entries) assertEquals(e, DictationEngine.of(e.key))
    }

    @Test
    fun the_phones_recognizer_falls_back_to_on_device() {
        assertEquals(phone, DictationChoice.engine(phone, phone = true, onDevice = true))
        assertEquals(phone, DictationChoice.engine(phone, phone = true, onDevice = false))
        assertEquals(onDevice, DictationChoice.engine(phone, phone = false, onDevice = true))
        assertNull(DictationChoice.engine(phone, phone = false, onDevice = false))
    }

    @Test
    fun on_device_only_never_falls_back_to_the_phones_recognizer() {
        assertEquals(onDevice, DictationChoice.engine(onDevice, phone = true, onDevice = true))
        assertEquals(onDevice, DictationChoice.engine(onDevice, phone = false, onDevice = true))
        assertNull(DictationChoice.engine(onDevice, phone = true, onDevice = false))
        assertNull(DictationChoice.engine(onDevice, phone = false, onDevice = false))
    }

    @Test
    fun both_engines_keep_partial_results_free_form_and_the_silence_lengths() {
        for (e in DictationEngine.entries) for (sdk in listOf(29, 31, 33, 36)) {
            val x = DictationChoice.extras(e, sdk)
            assertEquals("free_form", x["android.speech.extra.LANGUAGE_MODEL"])
            assertEquals(true, x["android.speech.extra.PARTIAL_RESULTS"])
            assertEquals(4_000L, x["android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS"])
            assertEquals(4_000L, x["android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS"])
            // No language extra: the recognizer's own language, as before.
            assertFalse(x.containsKey("android.speech.extra.LANGUAGE"))
        }
    }

    @Test
    fun only_on_device_asks_to_stay_offline() {
        for (sdk in listOf(29, 33)) {
            assertEquals(true, DictationChoice.extras(onDevice, sdk)["android.speech.extra.PREFER_OFFLINE"])
            assertFalse(DictationChoice.extras(phone, sdk).containsKey("android.speech.extra.PREFER_OFFLINE"))
        }
    }

    @Test
    fun formatting_and_unmasked_words_from_android_13_for_both() {
        for (e in DictationEngine.entries) {
            val old = DictationChoice.extras(e, 32)
            assertFalse(old.containsKey("android.speech.extra.ENABLE_FORMATTING"))
            assertFalse(old.containsKey("android.speech.extra.MASK_OFFENSIVE_WORDS"))
            for (sdk in listOf(33, 36)) {
                val x = DictationChoice.extras(e, sdk)
                assertEquals("quality", x["android.speech.extra.ENABLE_FORMATTING"])
                assertEquals(false, x["android.speech.extra.MASK_OFFENSIVE_WORDS"])
            }
        }
    }
}
