package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The new-session sheet's model picker: the Mac's lists, what's known without them, names, search, parts (data/ModelCatalog.kt). */
class ModelCatalogTest {
    private val claude = Provider("anthropic", "Claude (your subscription)", "anthropic", null, false, ProviderModels(), builtIn = true)
    private val or = Provider("or", "OpenRouter", "openrouter", "https://openrouter.ai/api", true, ProviderModels(default = "openai/gpt-5", sonnet = "anthropic/claude-sonnet-4.5"), builtIn = false)

    private val wire = JSONObject(
        """
        {"providers": [
          {"providerId": "anthropic", "source": "claude", "checkedAt": 1, "error": null, "models": [
            {"value": null, "name": "Default", "model": "claude-opus-5-5", "description": "Best for everyday, complex tasks", "context": 1000000, "price": null, "older": false, "slot": null},
            {"value": "opus", "name": "Opus 5.5", "model": "claude-opus-5-5", "description": "For complex work and everyday tasks", "context": 1000000, "older": false},
            {"value": "fable", "name": "Fable 5.1", "model": "claude-fable-5-1", "context": 1000000},
            {"value": "sonnet", "name": "Sonnet 5.5", "model": "claude-sonnet-5-5", "context": 1000000},
            {"value": "claude-opus-4-6", "name": "Opus 4.6", "model": "claude-opus-4-6", "context": 200000, "older": true},
            {"value": "nameless"}
          ]},
          {"providerId": "or", "source": "openrouter", "error": "unreachable", "models": [
            {"value": null, "name": "GPT-5", "model": "openai/gpt-5", "context": 400000, "price": {"input": 1.25, "output": 10}, "slot": "default"},
            {"value": "z-ai/glm-5.3", "name": "GLM 5.3", "model": "z-ai/glm-5.3", "context": 1048576, "price": {"input": 0.079, "output": 4.8}},
            {"value": "qwen/qwen3-coder:free", "name": "Qwen3 Coder (free)", "model": "qwen/qwen3-coder:free", "price": {"input": 0, "output": 0}}
          ]},
          {"source": "nobody"}
        ]}
        """.trimIndent(),
    )

    @Test fun theMacsListsAreReadAndAnythingMalformedIsLeftOut() {
        val lists = ModelCatalog.wire(wire)
        assertEquals(listOf("anthropic", "or"), lists.map { it.providerId })
        val c = lists[0].models
        assertEquals(listOf(null, "opus", "fable", "sonnet", "claude-opus-4-6"), c.map { it.value })
        assertTrue(c.last().older)
        assertEquals(1_000_000L, c[0].context)
        assertEquals("unreachable", lists[1].error)
        assertEquals(ModelPrice(1.25, 10.0), lists[1].models[0].price)
        assertEquals("default", lists[1].models[0].slot)
    }

    @Test fun withoutTheMacsListClaudesCurrentModelsAndTheProvidersSettings() {
        assertEquals(listOf(null, "opus", "fable", "sonnet", "haiku"), ModelCatalog.of(claude, null).map { it.value })
        assertEquals(listOf("Default", "Opus 5.5", "Fable 5.1", "Sonnet 5.5", "Haiku 5.5"), ModelCatalog.of(claude, emptyList()).map { it.name })
        val own = ModelCatalog.of(or, null)
        assertEquals(listOf(null to "openai/gpt-5", "sonnet" to "anthropic/claude-sonnet-4.5"), own.map { it.value to it.model })
        assertEquals("gpt-5", own[0].name)
        // A provider with no default named still offers its default.
        val bare = or.copy(models = ProviderModels())
        assertEquals(listOf("Default"), ModelCatalog.of(bare, null).map { it.name })
        // The Mac's list when it has one.
        assertEquals(3, ModelCatalog.of(or, ModelCatalog.wire(wire)).size)
    }

    @Test fun theDefaultGoesByTheModelItRuns() {
        val c = ModelCatalog.wire(wire)[0].models
        assertEquals("Opus 5.5", ModelCatalog.shown(c[0], c))
        assertEquals("Fable 5.1", ModelCatalog.shown(c[2], c))
        // No alias runs it: its own short name.
        assertEquals("Opus 9", ModelCatalog.shown(ModelOption(null, "Default", "claude-opus-9"), c))
        assertEquals("Default", ModelCatalog.shown(ModelOption(null, "Default"), c))
    }

    @Test fun theChoiceASessionIsOnFollowsTheModelItRuns() {
        val c = ModelCatalog.wire(wire)[0].models
        // Its alias, as it was started.
        assertEquals("fable", ModelCatalog.current(c, "fable")?.value)
        // The id the Mac reports (a reply's, the status line's): the alias that runs it, not the default.
        assertEquals("opus", ModelCatalog.current(c, "claude-opus-5-5")?.value)
        assertEquals("opus", ModelCatalog.current(c, "claude-opus-5-5-20260101[1m]")?.value)
        assertEquals("claude-opus-4-6", ModelCatalog.current(c, "claude-opus-4-6")?.value)
        // Nothing runs it here.
        assertNull(ModelCatalog.current(c, "openai/gpt-5"))
        assertNull(ModelCatalog.current(c, null))
    }

    @Test fun aSwitchCostsMoneyOnAnotherProviderUnlessTheModelIsFree() {
        val claudeList = ModelCatalog.wire(wire)[0].models
        assertEquals(false, ModelCatalog.paid(claude, claudeList[1]))
        val orList = ModelCatalog.wire(wire)[1].models
        assertEquals(true, ModelCatalog.paid(or, orList[0]))
        // "Qwen3 Coder (free)": price 0.
        assertEquals(false, ModelCatalog.paid(or, orList[2]))
    }

    @Test fun aModelWithAWeekOfItsOwnFindsIt() {
        val c = ModelCatalog.wire(wire)[0].models
        val weeks = listOf(ModelWeek("Sonnet", 0.03f, null), ModelWeek("Fable 5.1", 0.41f, null))
        assertEquals("Fable 5.1", ModelCatalog.week(c[2], weeks)?.label)
        assertEquals("Sonnet", ModelCatalog.week(c[3], weeks)?.label)
        assertNull(ModelCatalog.week(c[1], weeks))
        // The default runs Opus: no week of its own.
        assertNull(ModelCatalog.week(c[0], weeks))
        assertEquals("opus", ModelCatalog.family(c[0]))
        assertEquals("fable", ModelCatalog.family(c[2]))
        assertEquals("haiku", ModelCatalog.family(ModelOption("claude-haiku-4-5-20251001", "Haiku 4.5", "claude-haiku-4-5-20251001")))
    }

    @Test fun contextAndPricesInAFewCharacters() {
        assertEquals("1M", ModelCatalog.contextText(1_000_000))
        assertEquals("1M", ModelCatalog.contextText(1_048_576))
        assertEquals("1.1M", ModelCatalog.contextText(1_050_000 + 60_000))
        assertEquals("1M", ModelCatalog.contextText(1_050_000))
        assertEquals("400K", ModelCatalog.contextText(400_000))
        assertEquals("164K", ModelCatalog.contextText(163_840))
        assertEquals("512", ModelCatalog.contextText(512))
        assertEquals("$10", ModelCatalog.dollars(10.0))
        assertEquals("$1.25", ModelCatalog.dollars(1.25))
        assertEquals("$0.08", ModelCatalog.dollars(0.079))
        assertEquals("$0.005", ModelCatalog.dollars(0.005))
        assertTrue(ModelCatalog.free(ModelPrice(0.0, 0.0)))
    }

    @Test fun searchFindsByNameOrIdKeepingTheListsOrderAmongEquals() {
        val o = ModelCatalog.wire(wire)[1].models
        assertEquals(listOf("qwen/qwen3-coder:free"), ModelCatalog.search(o, "coder").map { it.value })
        assertEquals(listOf("z-ai/glm-5.3"), ModelCatalog.search(o, "z-ai").map { it.value })
        assertEquals(o, ModelCatalog.search(o, " "))
        assertTrue(ModelCatalog.search(o, "nothing-like-it").isEmpty())
        val c = ModelCatalog.wire(wire)[0].models
        assertEquals("fable", ModelCatalog.search(c, "fab").first().value)
    }

    @Test fun eachProvidersListInItsParts() {
        val lists = ModelCatalog.wire(wire)
        assertEquals(
            listOf(ModelCatalog.Part.Current to 4, ModelCatalog.Part.Older to 1),
            ModelCatalog.parts(lists[0].models, builtIn = true).map { it.first to it.second.size },
        )
        assertEquals(
            listOf(ModelCatalog.Part.Own to 1, ModelCatalog.Part.Catalogue to 2),
            ModelCatalog.parts(lists[1].models, builtIn = false).map { it.first to it.second.size },
        )
        // Only its settings' models: one part.
        assertEquals(listOf(ModelCatalog.Part.Current), ModelCatalog.parts(ModelCatalog.of(or, null), builtIn = false).map { it.first })
    }
}
