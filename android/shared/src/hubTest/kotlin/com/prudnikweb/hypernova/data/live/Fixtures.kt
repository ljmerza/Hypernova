package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.ConnLog
import com.prudnikweb.hypernova.data.Net
import org.json.JSONObject
import java.io.File

/**
 * The protocol's contract fixtures (protocol/fixtures/, on the test classpath) as a [FakeDaemon]'s
 * answers and messages: what the daemon really sends, so a rig test reads the same shapes the
 * contract test checks.
 */
object Fixtures {
    private val dir: File by lazy { File(Fixtures::class.java.classLoader!!.getResource("fixtures")!!.toURI()) }

    fun json(name: String): JSONObject = JSONObject(File(dir, "$name.json").readText())
}

/** For a wait that timed out: what the shared client has under way, and the connection log's last words. */
fun linkStory(): String {
    val d = Net.client.dispatcher
    val log = ConnLog.entries().take(12).joinToString(" | ") { "${it.kind} ${it.result} ${it.target.orEmpty()} ${it.text.orEmpty()}" }
    return "running ${d.runningCalls().map { it.request().url.encodedPath }}, queued ${d.queuedCalls().map { it.request().url.encodedPath }}; log: $log"
}
