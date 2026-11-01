package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An MCP call's row (data/Items.kt): its tool, then what it was called with, never its name twice. */
class McpRowTest {
    private fun row(name: String, summary: String?): Item.Tool {
        val e = WEvent.Tool("t", 1_000L, "tu", name, summary, emptyMap(), "done", "ok", null, 5L, null, null)
        return Items.build(listOf(e), emptyMap(), emptyMap(), emptyMap(), "s", "/p", now = 10_000L, settled = true).single() as Item.Tool
    }

    @Test fun theToolThenItsArgument() {
        val r = row("mcp__home-assistant__ha_get_state", "light.kitchen")
        assertEquals("ha_get_state", r.verb)
        assertEquals("light.kitchen", r.target)
        assertEquals("home-assistant", r.server)
    }

    @Test fun anOlderDaemonsSummaryOrTheServerAloneRepeatsTheNameSoItIsLeftOut() {
        assertEquals("", row("mcp__home-assistant__ha_get_state", "home-assistant · ha_get_state").target)
        assertEquals("", row("mcp__home-assistant__ha_get_state", "home-assistant").target)
        assertEquals("", row("mcp__home-assistant__ha_get_state", null).target)
    }

    @Test fun builtInToolsHaveNoServer() {
        assertNull(row("WebSearch", "kitchen lights").server)
        assertNull(Items.mcpServer("mcp__broken"))
        assertEquals("plugin_context7_context7", Items.mcpServer("mcp__plugin_context7_context7__query-docs"))
    }
}
