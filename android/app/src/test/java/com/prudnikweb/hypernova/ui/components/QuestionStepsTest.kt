package com.prudnikweb.hypernova.ui.components

import com.prudnikweb.hypernova.data.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The question card's steps (owner, 2026-10-31): with two questions he couldn't find Send or move
 * between them. Back and Next keep the answers, Send waits for every question, a dot jumps, and
 * the answers go as the daemon expects them (question text → label, multi-select joined by ", ").
 */
class QuestionStepsTest {
    private fun opts(vararg l: String) = l.map { Question.Option(it, "about $it") }
    private val versioning = Question("Which versioning scheme?", "Versioning", false, opts("Semver", "CalVer", "Build number"))
    private val first = Question("What's the first version?", "First version", false, opts("0.1.0", "1.0.0", "Keep current"))
    private val extras = Question("Which extras?", "Extras", true, opts("Changelog", "Tags, signed", "Release notes"))

    @Test fun oneQuestionIsOneStepWithoutBackOrNext() {
        val s = QuestionSteps(listOf(versioning))
        assertFalse(s.stepped)
        assertTrue(s.isFirst && s.isLast)
        assertFalse(s.complete)
        val picked = s.pick("CalVer")
        assertTrue(picked.complete)
        assertEquals(mapOf("Which versioning scheme?" to "CalVer"), picked.answers())
    }

    @Test fun nextAndBackKeepTheAnswers() {
        var s = QuestionSteps(listOf(versioning, first))
        assertTrue(s.stepped)
        assertEquals(versioning, s.current)
        s = s.pick("Semver").next()
        assertEquals(1, s.at)
        assertEquals(first, s.current)
        assertTrue(s.isLast)
        // Next on the last step stays there; Back on the first too.
        assertEquals(1, s.next().at)
        s = s.pick("1.0.0").back()
        assertEquals(0, s.at)
        assertEquals(0, s.back().at)
        assertEquals(setOf("Semver"), s.chosen[0])
        assertEquals(setOf("1.0.0"), s.chosen[1])
        // Changing the first answer leaves the second alone.
        s = s.pick("CalVer").next()
        assertEquals(mapOf("Which versioning scheme?" to "CalVer", "What's the first version?" to "1.0.0"), s.answers())
    }

    @Test fun sendWaitsForEveryQuestion() {
        var s = QuestionSteps(listOf(versioning, first))
        assertFalse(s.complete)
        assertEquals(0, s.firstUnanswered)
        s = s.next().pick("0.1.0")
        // The last one answered, the first not: still not ready, and it says which.
        assertFalse(s.complete)
        assertEquals(0, s.firstUnanswered)
        s = s.jump(0).pick("Semver")
        assertTrue(s.complete)
        assertNull(s.firstUnanswered)
    }

    @Test fun aDotJumpsToItsQuestion() {
        val s = QuestionSteps(listOf(versioning, first, extras))
        assertEquals(2, s.jump(2).at)
        assertEquals(1, s.jump(2).jump(1).at)
        // Out of range: stays.
        assertEquals(0, s.jump(3).at)
        assertEquals(0, s.jump(-1).at)
    }

    @Test fun multiSelectNeedsAtLeastOneAndJoinsInTheOrderOffered() {
        var s = QuestionSteps(listOf(extras))
        s = s.pick("Release notes").pick("Changelog")
        assertEquals(setOf("Release notes", "Changelog"), s.chosen[0])
        assertTrue(s.complete)
        // A label with ", " in it is quoted, as Claude Code joins them.
        s = s.pick("Tags, signed")
        assertEquals(mapOf("Which extras?" to "Changelog, \"Tags, signed\", Release notes"), s.answers())
        // Untick all: no answer.
        s = s.pick("Changelog").pick("Tags, signed").pick("Release notes")
        assertFalse(s.complete)
        assertEquals(emptyMap<String, String>(), s.answers())
    }

    @Test fun otherIsAnAnswerOnlyWithWords() {
        var s = QuestionSteps(listOf(versioning)).pick("Semver").toggleOther()
        // Single-select: Other replaces the option, and counts once something is typed.
        assertEquals(emptySet<String>(), s.chosen[0])
        assertFalse(s.complete)
        s = s.type("   ")
        assertFalse(s.complete)
        s = s.type("  Date-based, like 2026.10  ")
        assertTrue(s.complete)
        // Single-select goes as typed (trimmed), never quoted.
        assertEquals(mapOf("Which versioning scheme?" to "Date-based, like 2026.10"), s.answers())
        // Picking an option again unticks Other; the words wait in case it's ticked again.
        s = s.pick("CalVer")
        assertFalse(0 in s.otherOn)
        assertEquals(mapOf("Which versioning scheme?" to "CalVer"), s.answers())
        assertEquals("  Date-based, like 2026.10  ", s.other[0])
    }

    @Test fun multiSelectOtherGoesLast() {
        val s = QuestionSteps(listOf(extras)).toggleOther().type("Badges").pick("Changelog")
        assertTrue(0 in s.otherOn)
        assertEquals(mapOf("Which extras?" to "Changelog, Badges"), s.answers())
    }

    @Test fun answersArePerQuestionWhereverTheyWereGiven() {
        val s = QuestionSteps(listOf(versioning, first, extras))
            .jump(2).pick("Changelog")
            .jump(0).pick("Build number")
            .next().toggleOther().type("2.0.0-rc1")
        assertTrue(s.complete)
        assertEquals(
            mapOf(
                "Which versioning scheme?" to "Build number",
                "What's the first version?" to "2.0.0-rc1",
                "Which extras?" to "Changelog",
            ),
            s.answers(),
        )
    }

    @Test fun theAnswersGoAsOneObjectOfStrings() {
        // As LiveHub.answerAsync sends them: {decision: "allow", answers: {question: answer}}.
        val s = QuestionSteps(listOf(versioning, extras)).pick("Semver").next().pick("Changelog").pick("Release notes")
        val body = org.json.JSONObject().put("decision", "allow").put("answers", org.json.JSONObject(s.answers() as Map<*, *>))
        val answers = body.getJSONObject("answers")
        assertEquals(2, answers.length())
        assertEquals("Semver", answers.getString("Which versioning scheme?"))
        assertEquals("Changelog, Release notes", answers.getString("Which extras?"))
    }

    @Test fun noQuestionsIsNeverComplete() {
        val s = QuestionSteps(emptyList())
        assertFalse(s.complete)
        assertNull(s.current)
        assertEquals(s, s.pick("x").toggleOther().next().back())
    }
}
