package com.prudnikweb.hypernova.ui.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A long project name in the sky is cut after a word, not mid-word (ui/fx/Solar.kt, audit P6). */
class NameCutsTest {
    @Test fun cutAfterEachWordLongestFirst() {
        assertEquals(
            listOf("ZAZHIMAYU-POGODA-SANKT…", "ZAZHIMAYU-POGODA…", "ZAZHIMAYU…"),
            nameCuts("ZAZHIMAYU-POGODA-SANKT-PETERBURG"),
        )
        assertEquals(listOf("MY PROJECT…"), nameCuts("MY PROJECT_V2").take(1))
        assertEquals(listOf("A.B…", "A…"), nameCuts("A.B.C"))
    }

    @Test fun separatorsInARowMakeOneCut() {
        assertEquals(listOf("SITE…"), nameCuts("SITE--OLD").take(1))
        assertEquals(listOf("SITE…"), nameCuts("SITE__-OLD").distinct().take(1))
    }

    @Test fun oneWordHasNoCut() {
        assertTrue(nameCuts("HYPERNOVA").isEmpty())
        assertTrue(nameCuts("-LEADING").isEmpty())
    }
}
