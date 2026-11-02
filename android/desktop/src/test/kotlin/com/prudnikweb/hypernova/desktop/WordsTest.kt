package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.live.XmlStrings
import com.prudnikweb.hypernova.data.problemText
import com.prudnikweb.hypernova.desktop.ui.Atlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** The words and the tokens: this app's table against the phone's own files, every key. */
class WordsTest {
    @Test fun everyStrHasItsText() {
        for (k in Str.entries) assertTrue(k.name, DesktopStrings.text(k).isNotEmpty())
        assertTrue("the table's all there: the seam's ${Str.entries.size} keys", Str.entries.size > 250)
    }

    @Test fun everyPluralHasItsText() {
        for (k in Plural.entries) {
            assertTrue(k.name, DesktopStrings.quantityText(k, 1).isNotEmpty())
            assertTrue(k.name, DesktopStrings.quantityText(k, 2).isNotEmpty())
        }
    }

    /** StringsTableTest holds XmlStrings to Android's Resources, so this holds the table to what a phone shows. */
    @Test fun theyAreThePhonesWordsExceptWhereTheySayPhone() {
        for (k in Str.entries) assertEquals(k.name, DesktopStrings.forDesktop(XmlStrings.text(k)), DesktopStrings.text(k))
        for (k in Plural.entries) assertEquals(k.name, DesktopStrings.forDesktop(XmlStrings.quantityText(k, 2)), DesktopStrings.quantityText(k, 2))
    }

    @Test fun everyWordOfTheScreensHasItsText() {
        for (u in Ui.entries) assertTrue("${u.name} is in res/values or strings_desktop.xml", DesktopStrings.has(u.name))
    }

    @Test fun thisAppsOwnWordsDontSayTheNameItIsAboutToLose() {
        val own = DesktopStrings::class.java.classLoader.getResourceAsStream("desktop/strings_desktop.xml")!!.bufferedReader().readText()
        assertFalse("the name is in AppName.kt", own.contains(APP_NAME))
    }

    /** Only the phone's words get reworded. This app's mean the phone when they say it: it's where a question is answered. */
    @Test fun thisAppsOwnWordsKeepThePhoneTheyMean() {
        assertTrue(Ui.desk_answer_on_phone_question.text().endsWith("on your phone."))
        assertTrue(Ui.desk_answer_on_phone_plan.text().endsWith("on your phone."))
        assertTrue(Ui.desk_not_whole.text().endsWith("on your phone."))
    }

    @Test fun thisComputerIsntAPhoneAndScansNothing() {
        assertEquals("This computer is offline.", DesktopStrings.forDesktop("This phone is offline."))
        assertEquals("check that this computer's on it", DesktopStrings.forDesktop("check that this phone's on it"))
        assertEquals("run it and paste the link.", DesktopStrings.forDesktop("run it and scan the code."))
        assertEquals("and paste the new link.", DesktopStrings.forDesktop("and scan the new code."))
        // What the offline card and the pairing screen can say, on a server's words.
        val server = ComputerWords.wordedFor(DesktopStrings, com.prudnikweb.hypernova.data.Mac.LINUX)
        for (p in Problem.entries) {
            val words = problemText(server, p)
            assertFalse("$p: $words", Regex("""\b[Pp]hone\b""").containsMatchIn(words))
            assertFalse("$p: $words", words.contains("scan"))
            assertFalse("$p: $words", Regex("""\bMac\b""").containsMatchIn(words))
        }
    }

    @Test fun formattingAndClockAreThePlatformsOwn() {
        assertEquals("Can't reach z2.", Ui.cant_reach.format("z2"))
        assertTrue(DesktopStrings.timePattern(false).isNotBlank())
        assertTrue(DesktopStrings.timePattern(true).startsWith("EEE "))
        assertEquals(Locale.getDefault(), DesktopStrings.locale)
    }

    /** Tokens.kt loads its fonts through R, so Theme.kt repeats its colours: a change there fails here. */
    @Test fun theColoursAreTheAppsColours() {
        val tokens = listOf("../app/src/main/java/com/prudnikweb/hypernova/ui/theme/Tokens.kt").map(::File).first { it.isFile }.readText()
        val theirs = Regex("""(\w+) = Color\(0x([0-9A-Fa-f]{8})\)""").findAll(tokens).associate { it.groupValues[1] to it.groupValues[2].uppercase() }
        val ours = mapOf(
            "space" to Atlas.space, "ink" to Atlas.ink, "dim" to Atlas.dim, "line" to Atlas.line, "live" to Atlas.live,
            "ask" to Atlas.ask, "stop" to Atlas.stop, "idle" to Atlas.idle, "light" to Atlas.light, "glow" to Atlas.glow,
        )
        assertEquals("the app has as many tokens", theirs.keys, ours.keys)
        for ((name, color) in ours) {
            val argb = (color.value shr 32).toLong() and 0xFFFFFFFFL
            assertEquals(name, theirs[name], "%08X".format(argb))
        }
    }

    @Test fun theFontsAndTheMarksComeFromTheApp() {
        val cl = WordsTest::class.java.classLoader
        assertTrue(cl.getResource("fonts/manrope.ttf") != null)
        assertTrue(cl.getResource("fonts/jetbrains_mono.ttf") != null)
        // Compiled from the app's own file, not a copy of it.
        Class.forName("com.prudnikweb.hypernova.ui.components.MarksKt")
    }
}
