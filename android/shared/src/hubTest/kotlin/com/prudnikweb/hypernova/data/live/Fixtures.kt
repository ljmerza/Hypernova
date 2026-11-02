package com.prudnikweb.hypernova.data.live

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
