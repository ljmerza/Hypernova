package com.prudnikweb.hypernova.data

import okhttp3.OkHttpClient

/**
 * Where a session's live terminal is (docs/PROTOCOL.md, "Shared terminals"): a single-use
 * WebSocket URL on the route the app is using, and, on the local route, the client that has to
 * open it.
 *
 * On the base URL the terminal page opens the socket itself ([client] null). On the local route it
 * can't: the page's own WebSocket refuses the listener's self-signed certificate without asking the
 * app (a socket's certificate error never reaches the WebView's client), and it has no way to show
 * this phone's device certificate, which the listener requires. So there the app opens the socket
 * with the route's pinned client and passes the frames to the page (ui/term/AppSocket.kt).
 */
class TermTicket(val url: String, val client: OkHttpClient? = null) {
    companion object {
        /**
         * The daemon's ticket [url] (a path, `/v1/terms/<id>/ws?ticket=…`, or a whole
         * URL) on [route]: the route's own origin as `ws`/`wss`, with its pinned client when local.
         * The ticket isn't tied to a host, so whichever route is active when it comes back serves.
         */
        fun of(url: String, route: NetRoute): TermTicket {
            val ws = RoutePolicy.rebase(url, route.base).replaceFirst(Regex("^http"), "ws")
            return TermTicket(ws, route.client.takeIf { route.kind != RouteKind.Remote })
        }

        /** The socket's URL with the terminal's size, as the page adds it to a URL it opens itself. */
        fun sized(url: String, cols: Int, rows: Int): String = "$url${if ('?' in url) '&' else '?'}cols=$cols&rows=$rows"
    }
}
