package com.prudnikweb.hypernova.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OwnerCheck's BiometricPrompt throws without USE_BIOMETRIC, and the emulator (no screen lock)
 * never reaches it: build 418139 told a phone with a lock to set one up. Keep the permission.
 */
class OwnerCheckManifestTest {
    @Test
    fun manifestDeclaresUseBiometric() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.USE_BIOMETRIC"))
    }
}
