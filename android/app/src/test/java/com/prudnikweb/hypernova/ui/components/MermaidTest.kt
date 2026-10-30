package com.prudnikweb.hypernova.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply's ```mermaid blocks: which are drawn, and how (ui/components/Mermaid.kt, Transcript.kt's blocks()). */
class MermaidTest {
    private val flow = "flowchart TD\n  A --> B"

    @Test fun aClosedMermaidFenceIsADiagram() {
        val b = blocks("Here:\n\n```mermaid\n$flow\n```\n\nDone.")
        assertEquals(listOf(Block.Para("Here:"), Block.Mermaid(flow), Block.Para("Done.")), b)
    }

    @Test fun theLanguageIsAnyCaseAndOnlyItsFirstWord() {
        assertEquals(listOf(Block.Mermaid(flow)), blocks("```Mermaid\n$flow\n```"))
        assertEquals(listOf(Block.Mermaid(flow)), blocks("```MERMAID title=x\n$flow\n```"))
        assertEquals(listOf(Block.Mermaid(flow)), blocks("  ````mermaid\n$flow\n````"))
        assertEquals("mermaid", fenceLanguage("``` mermaid"))
        assertNull(fenceLanguage("```"))
    }

    @Test fun otherLanguagesStayCode() {
        assertEquals(listOf(Block.Code(flow)), blocks("```\n$flow\n```"))
        assertEquals(listOf(Block.Code(flow)), blocks("```mermaidjs\n$flow\n```"))
        assertEquals(listOf(Block.Code("val a = 1")), blocks("```kotlin\nval a = 1\n```"))
    }

    @Test fun oneStillBeingWrittenIsCode() {
        // Its closing fence hasn't arrived, streaming or not.
        assertEquals(listOf(Block.Code(flow)), blocks("```mermaid\n$flow", streaming = true))
        assertEquals(listOf(Block.Code("$flow\n``")), blocks("```mermaid\n$flow\n``", streaming = true))
        assertEquals(listOf(Block.Code(flow)), blocks("```mermaid\n$flow"))
        // Then it is, and it's drawn.
        assertEquals(listOf(Block.Mermaid(flow)), blocks("```mermaid\n$flow\n```", streaming = true))
    }

    @Test fun anEmptyOneIsCode() {
        assertEquals(listOf(Block.Code("")), blocks("```mermaid\n```"))
    }

    @Test fun keysFollowTheSource() {
        assertEquals(mermaidKey(flow), mermaidKey("\n$flow  \n"))
        assertNotEquals(mermaidKey(flow), mermaidKey("flowchart TD\n  A --> C"))
        assertEquals(64, mermaidKey(flow).length)
        assertTrue(mermaidKey(flow).all { it in "0123456789abcdef" })
    }

    @Test fun aWideDiagramScalesDownToTheColumn() {
        val f = diagramFit(800f, 200f, column = 400f, max = 400f)
        assertEquals(400f, f.width)
        assertEquals(100f, f.height)
        assertEquals(100f, f.shown)
        assertFalse(f.cut)
    }

    @Test fun aSmallOneKeepsItsSize() {
        val f = diagramFit(200f, 100f, column = 400f, max = 400f)
        assertEquals(200f, f.width)
        assertEquals(100f, f.height)
        assertFalse(f.cut)
    }

    @Test fun aTallOneIsCutAtTheMost() {
        val f = diagramFit(300f, 1200f, column = 360f, max = 400f)
        assertEquals(300f, f.width)
        assertEquals(1200f, f.height)
        assertEquals(400f, f.shown)
        assertTrue(f.cut)
    }
}
