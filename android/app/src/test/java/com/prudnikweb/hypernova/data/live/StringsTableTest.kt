package com.prudnikweb.hypernova.data.live

import android.os.Looper
import com.prudnikweb.hypernova.data.ComputerResources
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.id
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The shared code's strings off Android ([XmlStrings], what its tests compare on the desktop target)
 * against Android's own Resources, every one, so a test there reads what a phone shows.
 */
@RunWith(RobolectricTestRunner::class)
class StringsTableTest {
    // Without the paired computer's rewording.
    private val res = ComputerResources.plain(RuntimeEnvironment.getApplication().resources)

    // The app starting for this test may have written state (ComputerWords, after another test's Linux
    // server): Core's note to announce it is on the main thread, and Robolectric drops what is left there
    // at a test's end, after which Core announces no write again in this JVM (WidgetSyncTest fails).
    @After fun runWhatIsDue() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun everyStringReadsAsAndroidReadsIt() {
        for (key in Str.entries) assertEquals(key.name, res.getString(key.id), XmlStrings.text(key))
    }

    @Test fun everyPluralReadsAsAndroidReadsIt() {
        for (key in Plural.entries) for (n in listOf(1, 2, 7)) {
            assertEquals("${key.name} $n", res.getQuantityString(key.id, n), XmlStrings.quantityText(key, n))
        }
    }
}
