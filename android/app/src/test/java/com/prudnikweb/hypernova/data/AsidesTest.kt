package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "/btw" from the prompt box (data/Asides.kt): what goes as a side question, and its line in the chat. */
class AsidesTest {
    private fun aside(id: String, at: Long, state: String = Aside.ASKING, q: String = "why $id?") = Aside(id, q, state, null, null, false, at)

    @Test fun onlyBtwWithAQuestionIsASideQuestion() {
        assertEquals("why is it slow?", Asides.sideQuestion("/btw why is it slow?"))
        assertEquals("I'm testing the btw", Asides.sideQuestion("  /btw I'm testing the btw \n"))
        // Lines are one: Claude Code puts the question on one line.
        assertEquals("first second", Asides.sideQuestion("/btw first\nsecond"))
        assertNull(Asides.sideQuestion("/btw"))
        assertNull(Asides.sideQuestion("/btw   "))
        assertNull(Asides.sideQuestion("/btwx why"))
        assertNull(Asides.sideQuestion("/model opus"))
        assertNull(Asides.sideQuestion("please /btw why"))
        assertNull(Asides.sideQuestion("why is it slow?"))
    }

    @Test fun anUpdateReplacesItInPlaceAndKeepsWhereItWasAsked() {
        val a = aside("a", 100)
        val list = Asides.merged(emptyList(), a, chat = true)
        val answered = a.copy(state = Aside.ANSWERED, answer = "Because.")
        // The poll doesn't know where it was asked: it stays the prompt box's.
        val next = Asides.merged(list, answered, chat = false)
        assertEquals(1, next.size)
        assertEquals(Aside.ANSWERED, next[0].aside.state)
        assertTrue(next[0].chat)
    }

    @Test fun keptOldestFirstAndOnlyTheNewest() {
        var list = emptyList<Asides.Entry>()
        list = Asides.merged(list, aside("b", 200), chat = true)
        list = Asides.merged(list, aside("a", 100), chat = false)
        assertEquals(listOf("a", "b"), list.map { it.aside.id })
        for (i in 0 until Asides.MAX + 5) list = Asides.merged(list, aside("n$i", 1000L + i), chat = true)
        assertEquals(Asides.MAX, list.size)
        assertEquals("n${Asides.MAX + 4}", list.last().aside.id)
    }

    @Test fun itsLineIsClaudeCodesOwnCommandAsTyped() {
        val e = Asides.event(Asides.Entry(aside("x", 5, q = "I'm testing the btw"), chat = true))
        assertEquals("${Asides.KEY}x", e.id)
        assertEquals("/btw I'm testing the btw", e.text)
        assertEquals(CommandRun("/btw", "I'm testing the btw", "local"), e.command)
        assertEquals(CommandKind.Local, e.command!!.type)
    }

    @Test fun claudeCodesOwnCommandsReadAsTyped() {
        // Typed on the phone, before the Mac's copy (or with none: /btw never reaches the transcript).
        assertEquals(CommandKind.Local, CommandRun.typed("/btw I'm testing the btw")!!.type)
        assertEquals("I'm testing the btw", CommandRun.typed("/btw I'm testing the btw")!!.args)
        assertEquals(CommandKind.Local, CommandRun.typed("/model opus")!!.type)
        assertEquals(CommandKind.Local, CommandRun.typed("/stop")!!.type)
        // A skill or the owner's own command stays a message with its chip.
        assertEquals(CommandKind.Prompt, CommandRun.typed("/hyper-cosmos make it glow")!!.type)
    }

    @Test fun linesGoWhereTheyFallInTime() {
        val events = listOf<WEvent>(
            WEvent.User("u1", 100, "fix it"),
            WEvent.Assistant("a1", 200, "On it.", streaming = false),
            WEvent.Assistant("a2", 400, "Done.", streaming = false),
        )
        val asides = listOf(Asides.Entry(aside("late", 900), true), Asides.Entry(aside("mid", 300), true), Asides.Entry(aside("first", 50), false))
        val out = Asides.into(events, asides).map { it.id }
        assertEquals(listOf("aside:first", "u1", "a1", "aside:mid", "a2", "aside:late"), out)
        // None: the same list back.
        assertTrue(Asides.into(events, emptyList()) === events)
    }
}
