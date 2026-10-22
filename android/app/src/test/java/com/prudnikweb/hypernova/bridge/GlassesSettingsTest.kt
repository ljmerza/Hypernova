package com.prudnikweb.hypernova.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glasses' settings, kept in the app and read by Lens (GlassesSettings): Lens's own defaults,
 * a round trip through JSON, and a patch from Lens that can't put the app in a state it can't show.
 */
class GlassesSettingsTest {
    @Test fun defaultsAreLenss() {
        val d = GlassesSettings()
        assertTrue(d.readAloud)
        assertTrue(d.blip)
        assertEquals("all", d.textArrives)
        assertEquals(170, d.pace)
        assertEquals(mapOf("needs" to true, "done" to false, "stopped" to false, "limit" to false, "battery" to true, "sleep" to true, "note" to true), d.popups)
        assertFalse(d.moving)
        assertFalse(d.eclipse)
        assertNull(d.projects)
        assertEquals("en", d.language)
        assertEquals(GlassesSettings(), GlassesSettings.parse(null as String?))
        assertEquals(GlassesSettings(), GlassesSettings.parse("not json"))
    }

    @Test fun roundTrip() {
        val s = GlassesSettings(readAloud = false, textArrives = "speech", pace = 200, popups = GlassesSettings.DEFAULT_POPS + ("done" to true), moving = true, projects = listOf("hypernova", "0stems"), language = "ru", guideAsk = 42)
        val json = s.toJson()
        assertEquals(1, json.getInt("v"))
        assertEquals(s, GlassesSettings.parse(json.toString()))
        assertEquals(JSONObject.NULL, GlassesSettings().toJson().get("projects"))
    }

    @Test fun aPatchChangesOnlyWhatItKnowsAndBringsValuesIn() {
        val base = GlassesSettings()
        val patch = JSONObject()
            .put("readAloud", false)
            .put("blip", "yes")
            .put("pace", 999)
            .put("textArrives", "teleport")
            .put("popups", JSONObject().put("done", true).put("unknown", true).put("needs", "no"))
            .put("projects", JSONArray().put("a").put(" a ").put(3).put("").put("b"))
            .put("language", "Klingon!")
            .put("guideAsk", -5)
            .put("somethingNew", true)
        val s = base.merge(patch)
        assertFalse(s.readAloud)
        assertTrue("a wrong type is ignored", s.blip)
        assertEquals(GlassesSettings.PACE_MAX, s.pace)
        assertEquals("all", s.textArrives)
        assertEquals(true, s.popups["done"])
        assertEquals(true, s.popups["needs"])
        assertFalse(s.popups.containsKey("unknown"))
        assertEquals(listOf("a", "b"), s.projects)
        assertEquals("en", s.language)
        assertEquals(0L, s.guideAsk)
        assertEquals(GlassesSettings.PACE_MIN, base.merge(JSONObject().put("pace", 1)).pace)
        assertNull(s.merge(JSONObject().put("projects", JSONObject.NULL)).projects)
        assertEquals("auto", base.merge(JSONObject().put("language", "AUTO")).language)
    }

    @Test fun theLookIsOneOfTwoAndTravelsWithTheRest() {
        // The glasses' own font by default (the owner, 2026-10-18: "responsiveness really sucks with images").
        assertEquals("native", GlassesSettings().look)
        assertEquals("hypernova", GlassesSettings().merge(org.json.JSONObject().put("look", "hypernova")).look)
        assertEquals("native", GlassesSettings().merge(org.json.JSONObject().put("look", "comic sans")).look)
        assertEquals("native", GlassesSettings().merge(org.json.JSONObject().put("look", 7)).look)
        assertEquals("hypernova", GlassesSettings.parse(GlassesSettings(look = "hypernova").toJson()).look)
        assertEquals("hypernova", GlassesSettings(look = "hypernova").toJson().getString("look"))
        assertEquals("native", GlassesSettings().toJson().getString("look"))
    }

    @Test fun settingsKeptBeforeFirmwareTextWasTheDefaultTakeIt() {
        // Before version 2 every save wrote the drawn look, chosen or not: it takes today's default once.
        val old = GlassesSettings(look = "hypernova", pace = 200, moving = true).toJson().toString()
        val brought = GlassesSettings.stored(old, version = 1)
        assertEquals("native", brought.look)
        assertEquals("the rest stays", 200, brought.pace)
        assertTrue(brought.moving)
        // Kept since: a drawn look is a choice, and stays.
        assertEquals("hypernova", GlassesSettings.stored(old, version = GlassesSettings.STORE_VERSION).look)
        // Nothing kept: the defaults.
        assertEquals(GlassesSettings(), GlassesSettings.stored(null, version = 1))
    }

    @Test fun theNewSessionChoiceIsThePhonesAndTravelsToLens() {
        // Lens reads it as data/bridge.ts readNewSession does: a provider id, a model or null.
        val n = GlassesSettings.NewSession.of("p_3f9a", "openai/gpt-5")!!
        val json = GlassesSettings(newSession = n).toJson()
        assertEquals("p_3f9a", json.getJSONObject("newSession").getString("providerId"))
        assertEquals("openai/gpt-5", json.getJSONObject("newSession").getString("model"))
        assertTrue("none yet: null", GlassesSettings().toJson().isNull("newSession"))
        assertTrue("the default model: null", GlassesSettings(newSession = GlassesSettings.NewSession.of("anthropic", null)).toJson().getJSONObject("newSession").isNull("model"))
        // Kept by the app and read back.
        assertEquals(n, GlassesSettings.parse(json).newSession)
        assertEquals(n, GlassesSettings.stored(json.toString(), GlassesSettings.STORE_VERSION).newSession)
        // A change from Lens can't set it, nor clear it.
        val lens = JSONObject().put("newSession", JSONObject().put("providerId", "evil").put("model", "x")).put("moving", true)
        val after = GlassesSettings(newSession = n).merge(lens)
        assertEquals(n, after.newSession)
        assertTrue("the rest of the patch still applies", after.moving)
        assertEquals(n, GlassesSettings(newSession = n).merge(JSONObject().put("newSession", JSONObject.NULL)).newSession)
        // What isn't one reads as none.
        assertNull(GlassesSettings.NewSession.of("", "x"))
        assertNull(GlassesSettings.NewSession.of("-bad", "x"))
        assertNull(GlassesSettings.NewSession.of(null, "x"))
        assertNull("a model with a newline isn't one", GlassesSettings.NewSession.of("anthropic", "opus\nrm")!!.model)
        assertEquals(" sonnet ".trim(), GlassesSettings.NewSession.of("anthropic", " sonnet ")!!.model)
        assertNull(GlassesSettings.parse(JSONObject().put("newSession", "openrouter")).newSession)
    }
}
