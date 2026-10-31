package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server updating itself, as the app reads it (data/ServerUpdate.kt; docs/PROTOCOL.md, "The server's own update"). */
class ServerUpdateTest {
    @Test fun aDaemonTooOldToSayHasNoSwitch() {
        assertNull(ServerUpdate.parse(null))
        assertNull(ServerUpdate.parse(JSONObject()))
    }

    @Test fun offUnlessTheServerSaysOn() {
        val u = ServerUpdate.parse(JSONObject("""{"auto":false,"available":true,"reason":null,"state":"idle","target":null,"last":null}"""))!!
        assertFalse(u.auto)
        assertTrue(u.available)
        assertNull(u.reason)
        assertEquals(ServerUpdate.IDLE, u.state)
        assertNull(u.last)
    }

    @Test fun notAvailableHereSaysWhy() {
        val u = ServerUpdate.parse(JSONObject("""{"auto":true,"available":false,"reason":"Installed from a checkout.","state":"idle"}"""))!!
        assertTrue(u.auto)
        assertFalse(u.available)
        assertEquals("Installed from a checkout.", u.reason)
    }

    @Test fun waitingAndInstallingCarryTheirTag_anUnknownStateIsIdle() {
        assertEquals("v0.9.0", ServerUpdate.parse(JSONObject("""{"auto":true,"available":true,"state":"waiting","target":"v0.9.0"}"""))!!.target)
        assertEquals(ServerUpdate.INSTALLING, ServerUpdate.parse(JSONObject("""{"auto":true,"available":true,"state":"installing","target":"v0.9.0"}"""))!!.state)
        val odd = ServerUpdate.parse(JSONObject("""{"auto":true,"available":true,"state":"exploding","target":"v0.9.0"}"""))!!
        assertEquals(ServerUpdate.IDLE, odd.state)
        assertNull(odd.target)
    }

    @Test fun theLastUpdate() {
        val u = ServerUpdate.parse(JSONObject("""{"auto":true,"available":true,"state":"idle","last":{"from":"0.8.0","to":"0.9.0","at":1800000000000}}"""))!!
        assertEquals(ServerUpdate.Last("0.8.0", "0.9.0", 1800000000000L), u.last)
        assertNull(ServerUpdate.parse(JSONObject("""{"auto":true,"available":true,"last":{"from":"","to":"0.9.0"}}"""))!!.last)
    }

    @Test fun aVersionChangeIsALogLine() {
        assertEquals("the server updated: 0.8.0 → 0.9.0", ServerUpdate.versionChange("0.8.0", "0.9.0"))
        assertNull(ServerUpdate.versionChange("0.9.0", "0.9.0"))
        assertNull(ServerUpdate.versionChange(null, "0.9.0"))
    }

    @Test fun theSnapshotCarriesIt() {
        val mac = Wire.mac(JSONObject("""{"id":"m","name":"z2","update":{"auto":true,"available":true,"state":"idle"}}"""))
        assertTrue(mac.update!!.auto)
        assertNull(Wire.mac(JSONObject("""{"id":"m","name":"z2"}""")).update)
    }
}
