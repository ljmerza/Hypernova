package com.prudnikweb.hypernova.data

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Each HTTP call as one line of the debug log: method, the route with its ids, status, how long, sizes, a
 * request id if there is one, and TLS handshakes and failed connections as they happen. Off, a call gets
 * OkHttp's own [EventListener.NONE] and pays nothing. No body or header ever reaches this, bar the request
 * id's. A WebSocket's upgrade has its own lines (LiveHub): OkHttp doesn't tell a listener about those.
 */
object DebugNet {
    val events = EventListener.Factory { call -> if (DebugLog.on) CallWatch(call) else EventListener.NONE }

    /** A socket's or a call's TLS, in words. */
    fun tlsWords(h: Handshake?): String =
        // A resumed session keeps no record of the certificate it was made with, so its absence here says nothing.
        if (h == null) "no TLS" else "${h.tlsVersion.javaName} ${h.cipherSuite.javaName}" + if (h.localCertificates.isEmpty()) "" else ", device certificate sent"

    private class CallWatch(call: Call) : EventListener() {
        private val method = call.request().method
        private val route = DebugScrub.route(call.request().url.encodedPath)
        private val host = "${call.request().url.host}:${call.request().url.port}"
        private val started = System.nanoTime()
        private var tlsAt = 0L
        private var sent = -1L
        private var got = -1L
        private var status = 0
        private var requestId: String? = null

        private fun ms(from: Long = started) = (System.nanoTime() - from) / 1_000_000

        override fun secureConnectStart(call: Call) {
            tlsAt = System.nanoTime()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            DebugLog.i("tls") { "handshake with $host done in ${ms(tlsAt)} ms: ${tlsWords(handshake)}" }
        }

        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            // A probe given up on because another answered isn't news.
            if (call.isCanceled()) return
            DebugLog.w("net") { "couldn't connect to $host after ${ms()} ms: ${ioe.javaClass.simpleName}" }
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            sent = byteCount
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            status = response.code
            requestId = DebugScrub.id(response.header("X-Request-Id") ?: response.header("Request-Id"))
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            got = byteCount
        }

        override fun callEnd(call: Call) {
            DebugLog.d("http") { "$method $route: $status in ${ms()} ms${sizes()}" }
        }

        override fun callFailed(call: Call, ioe: IOException) {
            // Cancelled: a screen left, or a probe another route beat. Otherwise why is the class, and any words only if they're the network's.
            if (call.isCanceled()) {
                DebugLog.d("http") { "$method $route: cancelled after ${ms()} ms" }
                return
            }
            DebugLog.w("http", ioe.takeIf { status == 0 }) { "$method $route: failed after ${ms()} ms${if (status != 0) " (it had said $status)" else ""}${sizes()}" }
        }

        private fun sizes() = buildString {
            if (sent > 0) append(", sent ").append(DebugLog.size(sent))
            if (got >= 0) append(", got ").append(DebugLog.size(got))
            requestId?.let { append(", request ").append(it) }
        }
    }
}
