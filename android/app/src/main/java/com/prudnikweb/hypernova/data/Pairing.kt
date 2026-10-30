package com.prudnikweb.hypernova.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import com.prudnikweb.hypernova.BuildConfig
import org.json.JSONObject
import java.net.URLDecoder

/**
 * A Mac this phone is paired with. [fingerprint], [lanAddresses] and [lanPort] come from the
 * pairing link (docs/PROTOCOL.md, "Local network (direct HTTPS)"): the Mac's own listener, tried
 * before the snapshot has said anything newer.
 */
@Immutable
data class Pairing(
    val baseUrl: String,
    val token: String,
    val macName: String,
    val macId: String?,
    val deviceId: String?,
    val fingerprint: String? = null,
    val lanAddresses: List<String> = emptyList(),
    val lanPort: Int = LanInfo.DEFAULT_PORT,
)

/**
 * What a pairing link carries (docs/PROTOCOL.md, "Pairing"): the computer's own pairing page,
 * `https://<its address>:47323/pair?c=<code>&n=<name>&fp=…&lan=…&lp=…` (the QR code's, whose base is
 * that page's origin), or the app link `hypernova://pair?v=1&u=<base>&c=<code>&n=<name>…`; with the
 * local listener, `fp=<fingerprint>&lan=<addr1>,<addr2>&lp=<port>`.
 */
@Immutable
data class PairLink(
    val baseUrl: String,
    val code: String,
    val macName: String,
    val fingerprint: String? = null,
    val lan: List<String> = emptyList(),
    val lanPort: Int = LanInfo.DEFAULT_PORT,
) {
    /** The link names a local listener this phone could pair through. */
    val hasLan: Boolean get() = fingerprint != null && lan.isNotEmpty()
}

object PairLinks {
    /**
     * Upstream's link domain. This app never opens it (no App Link in the manifest) and pairing never
     * reaches it: a pasted `https://hypernova.observer/pair#…` from a computer before 2026-10-30 is
     * read here, its details from the fragment, and it is never a computer's address.
     */
    const val LINK_HOST = "hypernova.observer"
    private val UNIVERSAL = Regex("""https://hypernova\.observer/pair#\S*""", RegexOption.IGNORE_CASE)
    private val WEB = Regex("""https?://[^\s/?#]+(?::\d+)?/pair\?[^\s]*""", RegexOption.IGNORE_CASE)
    private val ORIGIN = Regex("""^([a-z][a-z0-9+.-]*)://([^/?#]+)""", RegexOption.IGNORE_CASE)

    /** What a link that opened the app asks for. */
    enum class Kind { Pair }

    /**
     * Which of the app's own links [uri] is: `hypernova://pair` pairs (its details then go to
     * [parse]); the computer's pairing page hands over with exactly that. Null for anything else.
     */
    fun kind(uri: String?): Kind? {
        val m = ORIGIN.find(uri?.trim() ?: return null) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val host = m.groupValues[2].lowercase()
        val path = uri.trim().substring(m.range.last + 1).substringBefore('?').substringBefore('#').trimEnd('/')
        return when {
            scheme == "hypernova" && host == "pair" && path.isEmpty() -> Kind.Pair
            else -> null
        }
    }

    /**
     * Finds a pairing link in [text] (a scanned or pasted link, possibly with words around it).
     * Accepts the computer's own pairing page, what its QR code carries
     * (`https://<address>:47323/pair?c=<code>&n=<name>&fp=…&lan=…&lp=…`, whose base is its own
     * origin), the app link `hypernova://pair?v=1&u=<base>&c=<code>&n=<name>…`, the
     * `intent://pair?…#Intent;…` hand-over, and an older computer's
     * `https://hypernova.observer/pair#…` (its details in the fragment, read exactly as the app
     * link's query, never fetched). Null if there isn't one.
     */
    fun parse(text: String?): PairLink? {
        if (text == null) return null
        val t = text.trim()
        // The fragment as the app link's query: the same parameters, the same checks.
        UNIVERSAL.find(t)?.let { return parseApp("hypernova://pair?" + it.value.substringAfter('#')) }
        val app = t.indexOf("hypernova://", ignoreCase = true)
        if (app >= 0) return parseApp(t.substring(app).split(Regex("\\s")).first())
        val intent = t.indexOf("intent://pair", ignoreCase = true)
        if (intent >= 0) {
            val raw = t.substring(intent).split(Regex("\\s")).first().substringBefore("#Intent")
            return parseApp("hypernova://" + raw.removePrefix("intent://").removePrefix("INTENT://"))
        }
        val web = WEB.find(t)?.value ?: return null
        return parseWeb(web)
    }

    /** The query of [raw] as a map, percent-decoded, first value per key. Plain Kotlin, so it runs in unit tests. */
    fun query(raw: String): Map<String, String> {
        val q = raw.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = decode(pair.substringBefore('='))
            val v = decode(pair.substringAfter('=', ""))
            out.putIfAbsent(k, v)
        }
        return out
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun parseApp(raw: String): PairLink? {
        val m = ORIGIN.find(raw) ?: return null
        if (!m.groupValues[1].equals("hypernova", true) || !m.groupValues[2].equals("pair", true)) return null
        val q = query(raw)
        val v = q["v"]
        if (v != null && v != "1") return null
        val base = q["u"]?.trim()?.trimEnd('/') ?: return null
        val code = q["c"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!base.startsWith("https://") && !base.startsWith("http://")) return null
        val name = q["n"]?.trim()?.takeIf { it.isNotEmpty() } ?: ORIGIN.find(base)?.groupValues?.get(2)?.substringBefore(':') ?: "Mac"
        return withLan(PairLink(base, code, name), q)
    }

    private fun parseWeb(raw: String): PairLink? {
        val m = ORIGIN.find(raw) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val hostPort = m.groupValues[2]
        val host = hostPort.substringBefore(':').takeIf { it.isNotEmpty() } ?: return null
        // The links' own domain is never a Mac (and a query there would have reached its server).
        if (host.equals(LINK_HOST, ignoreCase = true) || host.equals("api.$LINK_HOST", ignoreCase = true)) return null
        val q = query(raw)
        val code = q["c"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // An explicit u= wins (same as the app link); otherwise the page's own origin is the API.
        val base = q["u"]?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") } ?: "$scheme://$hostPort"
        val name = q["n"]?.trim()?.takeIf { it.isNotEmpty() } ?: host.substringBefore('.')
        return withLan(PairLink(base, code, name), q)
    }

    /**
     * `fp`, `lan` and `lp`: only together do they name a listener; a bad fingerprint drops all three.
     * Anything else in the link (an older computer's `ts`, `ti`, `ta`, `tc` tailnet hints) is ignored.
     */
    private fun withLan(link: PairLink, q: Map<String, String>): PairLink {
        val fp = q["fp"]?.trim()?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return link
        val lan = q["lan"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val port = q["lp"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: LanInfo.DEFAULT_PORT
        return link.copy(fingerprint = fp, lan = lan, lanPort = port)
    }

    /**
     * The base URL a pairing keeps, which is also how the app tells its computers' routes apart
     * ([ActiveRoutes], the statistics' cache go by it): the link's `u`, the computer's own listener
     * since 2026-10-30. A plain-http `u` (the emulator's `http://10.0.2.2:47321`, which every
     * computer's link carried before, so two computers paired that way shared one base) is replaced
     * by the listener address that answered [winner] when the pairing went over it.
     */
    fun keptBase(link: PairLink, winner: String?): String =
        if (winner != null && !link.baseUrl.startsWith("https://", ignoreCase = true)) RoutePolicy.baseOf(winner, link.lanPort) else link.baseUrl
}

/**
 * Pairing and app preferences, in app-private storage. The single pairing's keys are where the
 * list of paired computers ([ServerBook]) came from, and still hold the first computer's pairing.
 */
class PairingStore(context: Context) : LegacyPairing {
    private val prefs = context.applicationContext.getSharedPreferences("hypernova", Context.MODE_PRIVATE)

    /** The paired computers and the active one (data/Servers.kt), kept next to the rest. */
    val servers = ServerBook(PrefsKeyValue(prefs), this)

    override fun load(): Pairing? {
        val base = prefs.getString("baseUrl", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        return Pairing(
            base, token, prefs.getString("macName", null) ?: "Mac", prefs.getString("macId", null), prefs.getString("deviceId", null),
            fingerprint = prefs.getString("fingerprint", null),
            lanAddresses = prefs.getString("lan", null).orEmpty().split(',').filter { it.isNotEmpty() },
            lanPort = prefs.getInt("lanPort", LanInfo.DEFAULT_PORT),
        )
    }

    override fun save(p: Pairing) {
        prefs.edit()
            .putString("baseUrl", p.baseUrl).putString("token", p.token).putString("macName", p.macName)
            .putString("macId", p.macId).putString("deviceId", p.deviceId)
            .putString("fingerprint", p.fingerprint).putString("lan", p.lanAddresses.joinToString(",")).putInt("lanPort", p.lanPort)
            .apply()
    }

    override fun clear() {
        prefs.edit().remove("baseUrl").remove("token").remove("macName").remove("macId").remove("deviceId")
            .remove("fingerprint").remove("lan").remove("lanPort")
            // What an older build kept about the computer's tailnet, which nothing reads any more.
            .remove("tailnetName").remove("tailnetIp").remove("tailnetUrl").remove("tailnetAccount").remove("controlUrl").remove("controlConfirmed").apply()
    }

    var demo: Boolean
        get() = prefs.getBoolean("demo", false)
        set(v) { prefs.edit().putBoolean("demo", v).apply() }

    /** "Stay connected in the background" (default on). */
    var background: Boolean
        get() = prefs.getBoolean("background", true)
        set(v) { prefs.edit().putBoolean("background", v).apply() }

    /** Asked for notification permission once already (Android 13+). */
    var askedNotifications: Boolean
        get() = prefs.getBoolean("askedNotifications", false)
        set(v) { prefs.edit().putBoolean("askedNotifications", v).apply() }
}

object Pairer {
    /** The name the Mac shows for this phone, e.g. "Nothing Phone (3)". */
    fun deviceName(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    /**
     * The app install's stable id (8–64 of letters, digits, "_" and "-"): made once, kept with the
     * app's data (not with a pairing, so forgetting a computer keeps it), gone with an uninstall.
     */
    fun installId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("hypernova", Context.MODE_PRIVATE)
        prefs.getString("installId", null)?.takeIf { INSTALL_ID.matches(it) }?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("installId", id).apply()
        return id
    }

    private val INSTALL_ID = Regex("^[\\w-]{8,64}$")

    /**
     * `POST /v1/pair`, first to the computer's local listener when the link names it: its addresses
     * at once, the certificate pinned (the first to answer wins), on the same Wi-Fi, over a VPN into
     * the computer's network, or with it on this phone's hotspot. Then, when nobody there answered
     * (or the link names no listener), to the base URL `u` as it is (an emulator's 10.0.2.2), on a
     * route of its own: never through [ActiveRoutes], which would hand the request to a computer
     * already paired under the same base (every pairing made before 2026-10-30 had the emulator's)
     * and have that one turn down a code it never showed. Any 4xx means the code is no good
     * (expired, used, mistyped, or no code shown).
     */
    suspend fun pair(context: Context, link: PairLink): Pairing {
        val body = JSONObject()
            .put("code", link.code)
            .put("deviceName", deviceName(context))
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("appVersion", BuildConfig.VERSION_NAME)
            // This install's own id: pairing it again replaces its old device on the computer
            // rather than adding a twin to its list (docs/PROTOCOL.md, Pairing).
            .put("installId", installId(context))
        if (BuildConfig.DEBUG) android.util.Log.d("Hypernova", "pair: base=${link.baseUrl} lan=${link.hasLan}")
        val local = if (link.hasLan) pairOverLan(link, body) else null
        val res = local?.first ?: run {
            // The base URL is the listener itself (the link's own page): nobody answered there already.
            if (link.hasLan && RoutePolicy.isListener(link.baseUrl, link.lan, link.lanPort)) throw ApiException(Problem.Unreachable)
            try {
                post(Api(link.baseUrl, token = null, fixed = NetRoute.remote(link.baseUrl)), body)
            } catch (e: ApiException) {
                if (e.problem == Problem.CodeExpired || e.problem == Problem.Server || !link.hasLan) throw e
                // The listener's silence is the news, not the emulator's address refused as plain http.
                throw ApiException(Problem.Unreachable, cause = e)
            }
        }
        val token = res.str("token") ?: throw ApiException(Problem.Server, 200, "no_token")
        val mac = res.obj("mac")
        return Pairing(
            baseUrl = PairLinks.keptBase(link, local?.second),
            token = token,
            macName = mac?.str("name") ?: link.macName,
            macId = mac?.str("id"),
            deviceId = res.str("deviceId"),
            fingerprint = link.fingerprint,
            lanAddresses = link.lan,
            lanPort = link.lanPort,
        )
    }

    private suspend fun post(api: Api, body: JSONObject): JSONObject = try {
        api.post("/v1/pair", body)
    } catch (e: ApiException) {
        if (e.problem == Problem.Unauthorized || (e.status in 400..499 && e.status != 429)) {
            throw ApiException(Problem.CodeExpired, e.status, e.code, e.serverMessage, e)
        }
        throw e
    }

    /**
     * The pairing over the local listener: one pinned client, every address at once, the first
     * good answer kept, with the address that gave it. A refusal from any address is final (the code
     * is spent, no code is being shown, or too many wrong ones came from this network); nobody
     * answering is null, so the caller tries the base URL.
     */
    private suspend fun pairOverLan(link: PairLink, body: JSONObject): Pair<JSONObject, String>? {
        val fp = link.fingerprint ?: return null
        val client = PinnedTls.client(fp, identity = null)
        var answer: JSONObject? = null
        var refused: ApiException? = null
        val winner = RoutePolicy.choose(link.lan, timeoutMs = 6_000) { addr ->
            val api = Api(link.baseUrl, token = null, fixed = NetRoute(RoutePolicy.baseOf(addr, link.lanPort), RouteKind.Local, client))
            try {
                answer = post(api, body)
                true
            } catch (e: ApiException) {
                if (e.problem == Problem.CodeExpired || e.code == "too_many_tries") refused = e
                false
            }
        }
        refused?.let { if (winner == null) throw it }
        val a = answer
        return if (winner != null && a != null) a to winner else null
    }
}
