package com.prudnikweb.hypernova.data

import com.prudnikweb.hypernova.data.live.MapPrefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The debug log's one scrubbing layer (data/DebugScrub.kt): each kind of secret, planted in lines of every
 * sort, written through the real logger and looked for in the real files. What's useful in a line survives.
 */
class DebugScrubTest {
    private val dir: File = Files.createTempDirectory("debug-scrub").toFile()
    private val head = DebugHead("1.2.3 (45)", "debug build", "Test OS 1", "Test Model")

    @After fun tearDown() {
        DebugLog.use(null)
        dir.deleteRecursively()
    }

    /** A line someone might write, and what of it must never reach a file. */
    private val plants: List<Pair<String, String>> = listOf(
        "sent with Authorization: Bearer PLANTbearer0123" to "PLANTbearer0123",
        "headers {Authorization=Basic UExBTlRiYXNpYw==}" to "UExBTlRiYXNpYw",
        "\"authorization\":\"Bearer PLANTjsonauth\"" to "PLANTjsonauth",
        "Proxy-Authorization: Negotiate PLANTproxyauth" to "PLANTproxyauth",
        "Cookie: hn_preview=PLANTcookie; other=PLANTcookie2" to "PLANTcookie",
        "Set-Cookie: session=PLANTsetcookie; Path=/" to "PLANTsetcookie",
        "X-Api-Key: PLANTxapikey" to "PLANTxapikey",
        "Some-Header: PLANTheadervalue" to "PLANTheadervalue",
        "device token hn_PLANTdevicetoken0123 went out" to "PLANTdevicetoken",
        "token=PLANTtokenvalue" to "PLANTtokenvalue",
        "{\"token\":\"PLANTjsontoken\",\"deviceId\":\"dev\"}" to "PLANTjsontoken",
        "fire token hnf_0123456789abcdef0123456789abcdef" to "0123456789abcdef",
        "admin_token: PLANTadmintoken" to "PLANTadmintoken",
        "hook secret=PLANThooksecret" to "PLANThooksecret",
        "password: PLANTpassword" to "PLANTpassword",
        "a provider key sk-ant-api03-PLANTproviderkey" to "PLANTproviderkey",
        "ghp_PLANTgithubtoken012345" to "PLANTgithubtoken",
        "eyJhbGciOiJQTEFOVCJ9.eyJQTEFOVGp3dCI6MX0.PLANTsignature" to "PLANTsignature",
        "-----BEGIN PRIVATE KEY-----\nMIIEPLANTprivatekeybody\nmore\n-----END PRIVATE KEY----- after" to "MIIEPLANTprivatekeybody",
        "-----BEGIN CERTIFICATE-----\nMIICPLANTcertbody" to "MIICPLANTcertbody",
        "cert MIIBPLANTbase64DERcertificate0123456789abcdefghij" to "MIIBPLANTbase64DER",
        "fingerprint b43b818111636406f6a6115a8574b6141a370d6e2c0821f30f183541dea99714" to "b43b818111636406",
        "fingerprint B4:3B:81:81:11:63:64:06:F6:A6:11:5A" to "B4:3B:81",
        "pairing code: PLANTpaircode" to "PLANTpaircode",
        "code=PLANTcode" to "PLANTcode",
        "16 random bytes Xk9mPLANTq2Zr7vLw4Tn1A" to "Xk9mPLANTq2Zr7vLw4Tn1A",
        "hypernova://pair?v=1&u=https://192.168.0.2:47323&c=PLANTlinkcode&n=z2&fp=abcd" to "PLANTlinkcode",
        "open https://192.168.0.2:47323/pair?c=PLANTpagecode now" to "PLANTpagecode",
        "pair?c=PLANTbarecode" to "PLANTbarecode",
        "wss://192.168.0.2:47323/v1/terms/abc/ws?ticket=PLANTticket&cols=80" to "PLANTticket",
        "/v1/terms/abc/ws?ticket=PLANTpathticket" to "PLANTpathticket",
        "ticket=PLANTbareticket" to "PLANTbareticket",
        "https://me:PLANTuserinfo@host:1/x" to "PLANTuserinfo",
        "https://192.168.0.2:47323/p/PLANTpreviewtoken/report.pdf" to "PLANTpreviewtoken",
        "ANTHROPIC_API_KEY=PLANTenvvalue" to "PLANTenvvalue",
        "export FOO=\"PLANT env value\"" to "PLANT env value",
        "path=/home/me/PLANTpathvalue.txt" to "PLANTpathvalue",
        "reading /home/me/project/PLANTabsolute.txt" to "PLANTabsolute",
        "reading src/secret/PLANTrelative.txt" to "PLANTrelative",
        "reading C:\\Users\\me\\PLANTwindows.txt" to "PLANTwindows",
        "file:///storage/emulated/0/PLANTfileurl.pdf" to "PLANTfileurl",
        "content://com.android.providers/document/PLANTcontent.jpg" to "PLANTcontent",
        "signed in as PLANTemail@example.com" to "PLANTemail",
    )

    private fun logger() = DebugLogger(File(dir, "logs"), MapPrefs(), head).also { DebugLog.use(it) }

    private fun everything(l: DebugLogger): String = l.folder.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }

    @Test fun eachPlantedSecretIsCutFromALine() {
        for ((line, secret) in plants) {
            val out = DebugScrub.line(line)
            assertFalse("$line left: $out", out.contains(secret))
        }
    }

    @Test fun noneReachesTheFilesWhateverLineItCameIn() {
        val l = logger()
        l.turnOn()
        DebugLog.use(l)
        for ((line, _) in plants) {
            l.put(DebugLevel.D, "test", line, null)
            DebugLog.w("test") { "warned: $line" }
            // A caught exception's message, of a kind whose message goes in and one whose doesn't.
            DebugLog.e("test", java.net.ConnectException(line)) { "caught" }
            DebugLog.e("test", IllegalStateException(line, RuntimeException(line))) { "caught" }
            // The connection log's lines go through it again.
            ConnLog.add(ConnKind.Link, ConnResult.Failed, text = line)
        }
        // The crash file has its own way in.
        l.crashed(Thread.currentThread(), IllegalStateException(plants.joinToString(" ") { it.first }))
        assertTrue(l.flush(10_000))
        val bundle = File(dir, "bundle.log")
        l.bundle(bundle)
        val files = everything(l) + bundle.readText()
        assertTrue("it wrote the lines at all", files.contains("warned:"))
        assertTrue("and the crash", files.contains(CrashFile.MARK))
        for ((line, secret) in plants) assertFalse("$line reached a file", files.contains(secret))
        assertFalse(files, files.contains("PLANT"))
    }

    @Test fun whatASecretIsThisRunHoldsIsCutWhateverItLooksLike() {
        DebugScrub.remember("plain-looking")
        assertEquals("sent … now", DebugScrub.line("sent plain-looking now"))
        // Too short to be one, it would eat ordinary words.
        DebugScrub.remember("the")
        assertEquals("the end", DebugScrub.line("the end"))
    }

    @Test fun anApiCarriesItsTokenIntoTheScrubber() {
        Api("https://127.0.0.1:1", "looks-like-nothing-special")
        assertEquals("a …", DebugScrub.line("a looks-like-nothing-special"))
    }

    @Test fun whatsUsefulSurvives() {
        for (line in listOf(
            "GET /v1/sessions/5e550001-aaaa-4bbb-8ccc-0123456789ab/events: 200 in 84 ms, got 12.4 KB",
            "POST /v1/sessions/s1/rename: 409 in 3 ms",
            "handshake with 192.168.0.149:47323 done in 31 ms: TLSv1.3 TLS_AES_128_GCM_SHA256, device certificate sent",
            "probe, local, refused, 192.168.0.149:47323, 4 ms",
            "the Mac closed the socket: 1006",
            "next try in 30s (attempt 3)",
            "the network changed: Wi-Fi, not validated yet",
            "started: version 1.2.3 (45), debug build, Android 14 (SDK 34), Google Pixel 7",
            "\"session.removed\", 1.2 KB, session s1",
        )) assertEquals(line, DebugScrub.line(line))
    }

    @Test fun anEventIsOneLineAndNotEndless() {
        assertEquals("one two three", DebugScrub.line("one\ntwo\r\n\tthree"))
        // A control or a direction override can't dress one line up as another.
        assertFalse(DebugScrub.line("a\u202eb\u0000c").contains('\u202e'))
        assertEquals(DebugScrub.MAX_LINE, DebugScrub.line("word ".repeat(1000)).length)
    }

    @Test fun aRouteKeepsItsIdsAndLosesEverythingElse() {
        assertEquals("/v1/sessions/s1/events", DebugScrub.route("/v1/sessions/s1/events?after=2&path=/home/me/secret.txt"))
        assertEquals("/v1/terms/abc/ws", DebugScrub.route("wss://192.168.0.2:47323/v1/terms/abc/ws?ticket=SECRET"))
        assertEquals("/v1/file", DebugScrub.route("https://h:1/v1/file?path=%2Fhome%2Fme%2Fnotes.txt#top"))
        // A preview's link is a credential and a file's name.
        assertEquals("/p/…", DebugScrub.route("/p/PREVIEWTOKEN/notes.txt"))
        // Something that can't be a route's word or an id doesn't go through as one.
        assertEquals("/v1/x/…/y", DebugScrub.route("/v1/x/my%20file.txt/y"))
        assertEquals("/", DebugScrub.route("https://h:1"))
    }

    @Test fun onlyTheNetworksOwnExceptionsSayWhyAndACrashSaysNothing() {
        val parse = org.json.JSONException("Value PLANTEDCONTENT of type java.lang.String cannot be converted")
        val text = DebugScrub.throwable(parse).first()
        assertTrue(text, text.startsWith("org.json.JSONException (its message left out, "))
        assertFalse(text, text.contains("PLANTEDCONTENT"))

        val refused = DebugScrub.throwable(java.net.ConnectException("Failed to connect to /192.168.0.2:47323")).first()
        assertEquals("java.net.ConnectException: Failed to connect to /192.168.0.2:47323", refused)
        assertFalse(DebugScrub.throwable(java.net.ConnectException("Failed to connect"), messages = false).first().contains("Failed"))

        val api = DebugScrub.throwable(ApiException(Problem.Server, 409, "already_running", "PLANTEDSERVERWORDS")).first()
        assertTrue(api, api.contains("Server, status 409, already_running"))
        assertFalse(api, api.contains("PLANTEDSERVERWORDS"))
    }

    @Test fun aStackTraceHasItsFramesAndItsCauses() {
        val t = IllegalStateException("x", RuntimeException("y"))
        val lines = DebugScrub.throwable(t)
        assertTrue(lines.any { it.startsWith("    at com.prudnikweb.hypernova.data.DebugScrubTest.") })
        assertTrue(lines.any { it.startsWith("Caused by: java.lang.RuntimeException") })
        // A cause that points back at itself can't hold it up.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(2, DebugScrub.throwable(a).count { "RuntimeException" in it })
    }

    @Test fun aFeedLineIsItsTypeAndSizeAndNothingOfWhatItCarries() {
        val l = logger()
        l.turnOn()
        DebugLog.use(l)
        val msg = JSONObject().put("t", "event").put("sessionId", "s1").put("event", JSONObject().put("text", "PLANTEDREPLY"))
        DebugLog.feed(msg.toString(), msg)
        DebugLog.feed("not json PLANTEDBROKEN", null)
        DebugLog.feed(JSONObject().put("t", "PLANTED TYPE WITH SPACES").toString(), JSONObject().put("t", "PLANTED TYPE WITH SPACES"))
        assertTrue(l.flush(10_000))
        val text = everything(l)
        assertTrue(text, text.contains("\"event\", ${msg.toString().length} B, session s1"))
        assertTrue(text, text.contains("\"unreadable\""))
        assertFalse(text, text.contains("PLANTED"))
    }

    @Test fun aScreenIsItsKindAndIdsAndNeverATitleOrAPath() {
        val screens = listOf(
            Route.Files("/home/me/PLANTEDROOT", "PLANTEDTITLE"),
            Route.FileView("/home/me/PLANTEDFILE.txt"),
            Route.Web("https://h/?q=PLANTEDURL", "PLANTEDTITLE"),
            Route.McpServerPage("claude", "PLANTEDSERVER", "user", "/home/me/PLANTEDFOLDER"),
            Route.PluginPage("claude", "PLANTEDPLUGIN", "user", null, true),
            Route.ServerSettings("PLANTEDSERVER"),
        )
        for (r in screens) assertFalse(r.debugWords, r.debugWords.contains("PLANTED"))
        assertEquals("session s1", Route.Session("s1").debugWords)
        assertEquals("agent a1 of session s1", Route.Agent("s1", "a1").debugWords)
    }
}
