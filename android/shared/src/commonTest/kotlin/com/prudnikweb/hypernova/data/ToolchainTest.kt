package com.prudnikweb.hypernova.data

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the module rests on, on each target: Compose's snapshot state, the JDK, OkHttp and org.json from common code. */
class ToolchainTest {
    @Test fun composeSnapshotStateWorks() {
        val state = mutableStateOf(1)
        val snapshot = Snapshot.takeMutableSnapshot()
        snapshot.enter { state.value = 2 }
        assertEquals(1, state.value)
        snapshot.apply().check()
        snapshot.dispose()
        assertEquals(2, state.value)
    }

    @Test fun theJdkOkHttpAndOrgJsonAreThere() {
        assertEquals(36, java.util.UUID.randomUUID().toString().length)
        assertEquals("127.0.0.1", "https://127.0.0.1:47323/v1/health".toHttpUrl().host)
        assertEquals("b", JSONObject("""{"a":"b"}""").getString("a"))
    }
}
