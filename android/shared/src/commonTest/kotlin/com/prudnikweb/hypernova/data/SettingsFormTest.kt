package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a client does with the server's rows: its answer for the moment, never the check that counts. */
class SettingsFormTest {
    private fun row(key: String, group: String = "look", title: String = key, description: String = "", state: String = FormRow.UNSET) =
        FormRow(key, group, group.uppercase(), title, description, FormRow.TEXT, state)

    @Test fun aSearchGoesOverNamesAndDescriptionsAndEveryWordCounts() {
        val r = row("autoCompactEnabled", title = "Auto compact", description = "Compacts when the context is full.")
        assertTrue(SettingsFormLogic.matches(r, "compact"))
        assertTrue(SettingsFormLogic.matches(r, "AUTOCOMPACT"))
        assertTrue(SettingsFormLogic.matches(r, "context full"))
        assertTrue(SettingsFormLogic.matches(r, ""))
        assertFalse(SettingsFormLogic.matches(r, "context hooks"))
    }

    @Test fun whatsSetComesFirstInItsGroupAndTheServersGroupOrderStands() {
        val rows = listOf(row("z", "perms", state = FormRow.UNSET), row("y", "perms", state = FormRow.SET), row("a", "look"), row("m", "perms", state = FormRow.OPAQUE))
        val groups = SettingsFormLogic.groups(rows, "")
        assertEquals(listOf("perms", "look"), groups.map { it.id })
        assertEquals(listOf("m", "y", "z"), groups[0].rows.map { it.key })
    }

    @Test fun aSearchFindsTheAppsOwnWordsAndAGroupWithNothingGoes() {
        val rows = listOf(row("showThinkingSummaries", "look"), row("verbose", "other"))
        val groups = SettingsFormLogic.groups(rows, "thoughts") { r -> if (r.key == "showThinkingSummaries") "Thoughts" to "Shown" else null }
        assertEquals(listOf("look"), groups.map { it.id })
    }

    @Test fun textRules() {
        assertEquals(FormProblem.Needed, SettingsFormLogic.textProblem(FormRules(minLength = 1), ""))
        assertEquals(FormProblem.TooShort(3), SettingsFormLogic.textProblem(FormRules(minLength = 3), "ab"))
        assertEquals(FormProblem.TooLong(1), SettingsFormLogic.textProblem(FormRules(maxLength = 1), "ab"))
        assertEquals(FormProblem.WrongForm, SettingsFormLogic.textProblem(FormRules(pattern = "^a$"), "b"))
        assertNull(SettingsFormLogic.textProblem(FormRules(pattern = "^a$"), "a"))
        assertNull("a pattern that doesn't read here is the server's to judge", SettingsFormLogic.textProblem(FormRules(pattern = "(?<bad"), "a"))
    }

    private fun problemOf(rules: FormRules, text: String) = (SettingsFormLogic.numberOf(rules, text).exceptionOrNull() as ProblemException).problem

    @Test fun numberRules() {
        assertEquals(60L, SettingsFormLogic.numberOf(FormRules(integer = true, min = 1.0), "60").getOrThrow())
        assertEquals(0.5, SettingsFormLogic.numberOf(FormRules(above = 0.0, max = 1.0), "0.5").getOrThrow())
        assertEquals(FormProblem.NotANumber, problemOf(FormRules(), "many"))
        assertEquals(FormProblem.NotANumber, problemOf(FormRules(), "NaN"))
        assertEquals(FormProblem.NotWhole, problemOf(FormRules(integer = true), "1.5"))
        assertEquals(FormProblem.Below("1"), problemOf(FormRules(min = 1.0), "0"))
        assertEquals(FormProblem.Above("1"), problemOf(FormRules(max = 1.0), "2"))
        assertEquals(FormProblem.NotOver("0"), problemOf(FormRules(above = 0.0), "0"))
    }

    @Test fun listRules() {
        assertEquals(FormProblem.TooMany(1), SettingsFormLogic.listProblem(FormRules(maxItems = 1), listOf("a", "b")))
        assertEquals(FormProblem.TooFew(1), SettingsFormLogic.listProblem(FormRules(minItems = 1), emptyList()))
        assertEquals(FormProblem.Twice, SettingsFormLogic.listProblem(FormRules(unique = true), listOf("a", "a")))
        assertNull(SettingsFormLogic.listProblem(FormRules(unique = true, maxItems = 2), listOf("a", "b")))
        assertEquals(FormProblem.Needed, SettingsFormLogic.itemProblem(FormRules(itemMinLength = 1), ""))
    }

    @Test fun jsonIsTheJsonTabsParser() {
        assertNull(SettingsFormLogic.jsonProblem("{\"a\": 1}"))
        assertEquals(2, (SettingsFormLogic.jsonProblem("{\n  oops\n}") as FormProblem.BadJson).line)
    }

    @Test fun aNumberReadsAsAPersonWritesIt() {
        assertEquals("30", SettingsFormLogic.numberWords(30.0))
        assertEquals("0.05", SettingsFormLogic.numberWords(0.05))
    }

    @Test fun aSwitchShowsTheFileOrItsDefault() {
        val base = row("a")
        assertTrue(base.copy(defaultOn = true).showsOn)
        assertFalse(base.copy(defaultOn = true, on = false, state = FormRow.SET).showsOn)
        assertFalse(base.showsOn)
    }

    @Test fun aRowsValueComesOffTheWireAsItsOwnKind() {
        val r = SettingsFormReply.wire(
            JSONObject(
                """{"agent":"claude","source":{"from":"S","checkedAgainst":"C"},"placeholder":"<h>","state":"ok","hash":"h","whyNot":null,"undo":{"key":"k","asks":true},"hiddenKeys":2,"rows":[
                {"key":"a","group":"g","groupTitle":"G","title":"A","description":"d","control":"switch","state":"set","value":true,"removable":true,"default":false},
                {"key":"b","group":"g","groupTitle":"G","title":"B","description":"","control":"number","state":"set","value":30,"rules":{"integer":true,"min":1},"default":30},
                {"key":"c","group":"g","groupTitle":"G","title":"C","description":"","control":"list","state":"set","value":["x","y"]},
                {"key":"d","group":"g","groupTitle":"G","title":"D","description":"","control":"pick","state":"unset","of":[{"value":"plan","asks":null},{"value":"auto","asks":"loosens"},{"value":"gpt","label":"GPT","asks":null}],"default":"plan","applies":"new_sessions"},
                {"key":"e","group":"g","groupTitle":"G","title":"E","description":"","control":"json","state":"set","value":{"z":1},"text":"{\n  \"z\": 1\n}","hidden":1,"asks":"runs"},
                {"key":"f","group":"g","groupTitle":"G","title":"F","description":"","control":"link","state":"opaque","screen":"env"},
                {"key":"g","group":"g","groupTitle":"G","title":"G","description":"","control":"readonly","state":"opaque","readOnly":"managed","notRead":true,"notInSchema":true},
                {"group":"no-key"}]}""",
            ),
        )
        assertEquals(7, r.rows.size)
        assertEquals("S", r.from)
        assertEquals(FormUndo("k", true), r.undo)
        assertEquals(2, r.hiddenKeys)
        assertTrue(r.canChange)
        val (a, b, c, d, e) = r.rows
        assertEquals(true, a.on)
        assertEquals(false, a.defaultOn)
        assertEquals("30", b.number)
        assertEquals("30", b.defaultWords)
        assertEquals(1.0, b.rules.min)
        assertTrue(b.rules.integer)
        assertEquals(listOf("x", "y"), c.items)
        assertEquals(listOf("plan", "auto", "gpt"), d.choices.map { it.text })
        assertEquals("loosens", d.choices[1].asks)
        assertEquals("GPT", d.choices[2].label)
        assertEquals("new_sessions", d.applies)
        assertEquals("{\n  \"z\": 1\n}", e.json)
        assertEquals(1, e.hidden)
        assertEquals("env", r.rows[5].screen)
        assertEquals("managed", r.rows[6].readOnly)
        assertTrue(r.rows[6].notRead && r.rows[6].notInSchema)
    }

    @Test fun aLinkOrAReadOnlyFileChangesNothing() {
        val base = SettingsFormReply("claude", "", "", "", AgentSettingsFile.OK, null, null, null, emptyList(), 0)
        assertTrue(base.canChange)
        assertFalse(base.copy(whyNot = AgentSettingsFile.LINK).canChange)
        assertFalse(base.copy(state = AgentSettingsFile.MALFORMED).canChange)
        assertTrue(base.copy(state = AgentSettingsFile.MISSING).canChange)
    }
}
