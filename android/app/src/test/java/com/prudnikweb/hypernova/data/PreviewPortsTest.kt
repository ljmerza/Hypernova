package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewPortsTest {
    @Test fun aDevServerIsOfferedHypernovasOwnPortsAreNot() {
        assertEquals(listOf(5173), Items.ports("Local: http://localhost:5173/"))
        // `hypernova status` prints its own API and local route: no "Open preview" for those.
        assertEquals(emptyList<Int>(), Items.ports("Phone API    127.0.0.1:47321\nlocal api on 127.0.0.1:47322\nlan on 127.0.0.1:47323"))
        assertEquals(listOf(3000), Items.ports("api on 127.0.0.1:47321, app on http://localhost:3000"))
    }
}
