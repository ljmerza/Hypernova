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
    /** The computer's name on its tailnet (docs/PROTOCOL.md, "Tailscale built in"); null when the link said nothing. */
    val tailnetName: String? = null,
    val tailnetIp: String? = null,
    /**
     * Where the computer serves the phone API on its tailnet, as it said last (its snapshot): set
     * when it moved since the pairing (the Tailscale app quit and the built-in node took over, or
     * the other way round). Null: the base URL or the tailnet name with the base URL's port.
     */
    val tailnetUrl: String? = null,
    /** The account the computer signed in with: the sign-in screen says "sign in as you@…". */
    val tailnetAccount: String? = null,
    /** The coordination server when it isn't tailscale.com (Headscale). */
    val controlUrl: String? = null,
    /**
     * The user agreed to that control server ("This computer uses its own Tailscale server: …").
     * Until then the app's node is never started against it (a pairing link is anyone's to write).
     */
    val controlConfirmed: Boolean = false,
)

/**
 * What the QR code carries: `https://hypernova.observer/pair#v=1&u=<base>&c=<code>&n=<mac name>`
 * (or the app link `hypernova://pair?…` with the same parameters), plus, from a Mac with the local
 * listener, `fp=<fingerprint>&lan=<addr1>,<addr2>&lp=<port>`.
 */
@Immutable
data class PairLink(
    val baseUrl: String,
    val code: String,
    val macName: String,
    val fingerprint: String? = null,
    val lan: List<String> = emptyList(),
    val lanPort: Int = LanInfo.DEFAULT_PORT,
    /** `ts`, `ti`, `ta`, `tc`: the computer on its tailnet, for the built-in node. */
    val tailnetName: String? = null,
    val tailnetIp: String? = null,
    val tailnetAccount: String? = null,
    val controlUrl: String? = null,
) {
    /** The link names a local listener this phone could pair through. */
    val hasLan: Boolean get() = fingerprint != null && lan.isNotEmpty()
    /** The link names the computer on a tailnet, which the built-in node could reach. */
    val hasTailnet: Boolean get() = tailnetName != null
}

object PairLinks {
    /**
     * The domain whose https links open this app (Android App Links: the manifest's autoVerify
     * filter, and `names/src/links.ts`, which serves its assetlinks.json and the pages a browser
     * shows). Only `/pair` there; it's never a Mac's address.
     */
    const val LINK_HOST = "hypernova.observer"
    private val UNIVERSAL = Regex("""https://hypernova\.observer/pair#\S*""", RegexOption.IGNORE_CASE)
    private val WEB = Regex("""https?://[^\s/?#]+(?::\d+)?/pair\?[^\s]*""", RegexOption.IGNORE_CASE)
    private val ORIGIN = Regex("""^([a-z][a-z0-9+.-]*)://([^/?#]+)""", RegexOption.IGNORE_CASE)

    /** What a link that opened the app asks for. */
    enum class Kind { Pair }

    /**
     * Which of the app's own links [uri] is: `hypernova://pair` and `https://hypernova.observer/pair`
     * pair (their details then go to [parse]). Null for anything else.
     */
    fun kind(uri: String?): Kind? {
        val m = ORIGIN.find(uri?.trim() ?: return null) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val host = m.groupValues[2].lowercase()
        val path = uri.trim().substring(m.range.last + 1).substringBefore('?').substringBefore('#').trimEnd('/')
        return when {
            scheme == "hypernova" && host == "pair" && path.isEmpty() -> Kind.Pair
            scheme == "https" && host == LINK_HOST && path == "/pair" -> Kind.Pair
            else -> null
        }
    }

    /**
     * Finds a pairing link in [text] (a scanned or pasted link, possibly with words around it).
     * Accepts the link the Mac's QR code carries, `https://hypernova.observer/pair#v=1&u=<base>&c=<code>…`
     * (its details in the fragment, read exactly as the app link's query), the app link
     * `hypernova://pair?v=1&u=<base>&c=<code>&n=<name>`, the web link of a Mac's own pairing page
     * (`https://<mac>.ts.net:47321/pair?c=<code>&n=<name>`, whose base is its own origin), and the
     * `intent://pair?…#Intent;…` hand-over. Null if there isn't one.
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

    /** `fp`, `lan` and `lp`: only together do they name a listener; a bad fingerprint drops all three. */
    private fun withLan(link: PairLink, q: Map<String, String>): PairLink {
        val withTs = withTailnet(link, q)
        val fp = q["fp"]?.trim()?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return withTs
        val lan = q["lan"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val port = q["lp"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: LanInfo.DEFAULT_PORT
        return withTs.copy(fingerprint = fp, lan = lan, lanPort = port)
    }

    private val DNS_NAME = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$""")

    /** `ts` (a DNS name), with `ti`, `ta` and `tc` when they look right; nothing without a good `ts`. */
    private fun withTailnet(link: PairLink, q: Map<String, String>): PairLink {
        val name = q["ts"]?.trim()?.lowercase()?.takeIf { it.length <= 253 && DNS_NAME.matches(it) } ?: return link
        val ip = q["ti"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 45 && it.all { c -> c.isDigit() || c == '.' || c == ':' || c in 'a'..'f' || c in 'A'..'F' } }
        val account = q["ta"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 && it.none { c -> c.isWhitespace() || c in "<>\"'" } }
        val control = q["tc"]?.trim()?.trimEnd('/')?.takeIf { controlUrlOk(it) }
        return link.copy(tailnetName = name, tailnetIp = ip, tailnetAccount = account, controlUrl = control)
    }

    /**
     * What a control server in a link may be: `https://…`, or plain `http://` only to a private
     * address (a Headscale on the LAN, a test), never to the internet in the clear. No credentials,
     * no query, at most 200 characters.
     */
    fun controlUrlOk(url: String): Boolean {
        if (url.length > 200 || url.any { it.isWhitespace() }) return false
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() } ?: return false
        if (u.userInfo != null || u.rawQuery != null || u.rawFragment != null) return false
        return when (u.scheme?.lowercase()) {
            "https" -> true
            "http" -> privateHost(host)
            else -> false
        }
    }

    /** Loopback, RFC 1918, CGNAT (the tailnet's own) or IPv6 ULA: an address that isn't the open internet. */
    fun privateHost(host: String): Boolean {
        if (host == "localhost" || host == "::1") return true
        // Decimal octets without leading zeros ("010" is octal to some resolvers).
        val v4 = host.split('.').takeIf { it.size == 4 }?.map { o -> if (!Regex("0|[1-9][0-9]{0,2}").matches(o)) return false; o.toInt() }
        if (v4 != null) {
            val (a, b) = v4
            return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
        }
        return host.length > 4 && (host.startsWith("fc") || host.startsWith("fd")) && host[4] == ':'
    }
}

/** Pairing and app preferences, in app-private storage. */
class PairingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hypernova", Context.MODE_PRIVATE)

    fun load(): Pairing? {
        val base = prefs.getString("baseUrl", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        return Pairing(
            base, token, prefs.getString("macName", null) ?: "Mac", prefs.getString("macId", null), prefs.getString("deviceId", null),
            fingerprint = prefs.getString("fingerprint", null),
            lanAddresses = prefs.getString("lan", null).orEmpty().split(',').filter { it.isNotEmpty() },
            lanPort = prefs.getInt("lanPort", LanInfo.DEFAULT_PORT),
            tailnetName = prefs.getString("tailnetName", null),
            tailnetIp = prefs.getString("tailnetIp", null),
            tailnetUrl = prefs.getString("tailnetUrl", null),
            tailnetAccount = prefs.getString("tailnetAccount", null),
            controlUrl = prefs.getString("controlUrl", null),
            controlConfirmed = prefs.getBoolean("controlConfirmed", false),
        )
    }

    fun save(p: Pairing) {
        prefs.edit()
            .putString("baseUrl", p.baseUrl).putString("token", p.token).putString("macName", p.macName)
            .putString("macId", p.macId).putString("deviceId", p.deviceId)
            .putString("fingerprint", p.fingerprint).putString("lan", p.lanAddresses.joinToString(",")).putInt("lanPort", p.lanPort)
            .putString("tailnetName", p.tailnetName).putString("tailnetIp", p.tailnetIp).putString("tailnetUrl", p.tailnetUrl).putString("tailnetAccount", p.tailnetAccount).putString("controlUrl", p.controlUrl)
            .putBoolean("controlConfirmed", p.controlConfirmed)
            .apply()
    }

    fun clear() {
        prefs.edit().remove("baseUrl").remove("token").remove("macName").remove("macId").remove("deviceId")
            .remove("fingerprint").remove("lan").remove("lanPort")
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
     * `POST <u>/v1/pair`. Any 4xx means the code is no good (expired, used, or mistyped). When the
     * base can't be reached and the link names the Mac's local listener, the same request goes to
     * those addresses with the certificate pinned (the first to answer wins): pairing on the same
     * Wi-Fi, or with the Mac on this phone's hotspot, needs no Tailscale.
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
        if (BuildConfig.DEBUG) android.util.Log.d("Hypernova", "pair: base=${link.baseUrl} lan=${link.hasLan} tailnet=${link.tailnetName} node=${Tsnet.status.state}")
        val res = try {
            post(Api(link.baseUrl, token = null), body)
        } catch (e: ApiException) {
            if (e.problem == Problem.CodeExpired || e.problem == Problem.Server) throw e
            // The local network first (no account needed), then the built-in node while it runs.
            (if (link.hasLan) pairOverLan(link, body) else null)
                ?: (if (link.hasTailnet) pairOverTailnet(link, body) else null)
                ?: throw e
        }
        val token = res.str("token") ?: throw ApiException(Problem.Server, 200, "no_token")
        val mac = res.obj("mac")
        return Pairing(
            baseUrl = link.baseUrl,
            token = token,
            macName = mac?.str("name") ?: link.macName,
            macId = mac?.str("id"),
            deviceId = res.str("deviceId"),
            fingerprint = link.fingerprint,
            lanAddresses = link.lan,
            lanPort = link.lanPort,
            tailnetName = link.tailnetName,
            tailnetIp = link.tailnetIp,
            tailnetAccount = link.tailnetAccount,
            controlUrl = link.controlUrl,
        )
    }

    /**
     * The pairing through the built-in Tailscale node (data/Tsnet.kt), when it is signed in: the
     * same request to the computer's tailnet name through the node's proxy. Null when the node
     * isn't running or nothing answered there; a refusal is final, as over the local network.
     */
    private suspend fun pairOverTailnet(link: PairLink, body: JSONObject): JSONObject? {
        val node = Tsnet.status.takeIf { it.running } ?: return null
        // The node must be the one for this link's control server (never another server's node).
        if (!Tsnet.serves(link.controlUrl)) return null
        val target = TailnetTarget.of(Pairing(link.baseUrl, "", link.macName, null, null, link.fingerprint, tailnetName = link.tailnetName, tailnetIp = link.tailnetIp)) ?: return null
        // Added next to the current computer's destination, never in its place: a failed pairing leaves the current route alone.
        target.hostPort?.let { Tsnet.allow(it) }
        val client = TsnetTls.client(target.base, node.proxyPort, node.proxyToken, target.fingerprint, identity = null)
        return try {
            post(Api(link.baseUrl, token = null, fixed = NetRoute(target.base, RouteKind.Tailscale, client)), body)
        } catch (e: ApiException) {
            if (e.problem == Problem.CodeExpired || e.code == "too_many_tries") throw e
            null
        }
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
     * good answer kept. A refusal from any address is final (the code is spent, no code is being
     * shown, or too many wrong ones came from this network); nobody answering is null, so the
     * caller reports the base's failure.
     */
    private suspend fun pairOverLan(link: PairLink, body: JSONObject): JSONObject? {
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
        return if (winner != null) answer else null
    }
}

/**
 * The computer moved on its tailnet (data/Protocol.kt [TailnetHint]): a phone paired while the
 * Tailscale app served the phone API keeps the app's node as its tailnet target, and once the app is
 * quit and Hypernova's own node serves instead, the phone's node would dial a machine that's gone.
 * So the pairing follows what the computer says (its snapshot, `{t:"tsnet"}`), over the route it
 * already trusts. Only on the same control server: another one needs the user's consent, which a
 * new pairing asks for. Null when nothing changes.
 */
object TailnetFollow {
    fun next(p: Pairing, h: TailnetHint?): Pairing? {
        if (h == null || h.control?.trimEnd('/')?.lowercase() != p.controlUrl?.trimEnd('/')?.lowercase()) return null
        val o = RoutePolicy.originOf(h.url) ?: return null
        val name = h.name.lowercase()
        // The URL must be the computer itself on the tailnet: its name or its address there.
        if (o.scheme != "https" || (o.host != name && o.host != h.ip?.lowercase())) return null
        val same = p.tailnetName?.trimEnd('.')?.lowercase() == name && p.tailnetIp == h.ip && (p.tailnetUrl ?: p.baseUrl).trimEnd('/') == h.url
        if (same) return null
        return p.copy(tailnetName = h.name, tailnetIp = h.ip, tailnetUrl = h.url)
    }
}
