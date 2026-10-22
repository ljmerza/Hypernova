package com.prudnikweb.hypernova.bridge

import java.math.BigInteger
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * How the bridge and Lens agree access and sign calls without the token ever travelling
 * (docs/PROTOCOL.md, "The phone bridge"). Any app on the phone can take 127.0.0.1:47380 before this
 * one does and answer Lens as if it were Hypernova, so nothing Lens sends there may be worth stealing:
 *
 * - **Access is agreed** (X25519, RFC 7748): Lens sends its public key and a commitment to a nonce;
 *   the bridge answers with its own key and nonce; then Lens shows its nonce. Both work out the
 *   same token from the shared secret, and the same six-digit code from the whole exchange, which
 *   both screens show. Something in between agrees one key with Lens and another with the app, and
 *   it must give Lens its half before it learns Lens's nonce: it can't steer the two codes to match.
 * - **Calls are signed**: HMAC-SHA-256 with the token's SHA-256 (all the bridge keeps) over the
 *   listener's id (new each time the bridge opens its port), the method, the path, the query less
 *   the signature, the time, a nonce and the body's SHA-256. Good once, for that listener, for a
 *   minute either side, and only with that very body.
 * - **Calls are sealed** ([BridgeSeal]): the body and the whole answer (its status, its headers and
 *   its body) under a key of that one call's own ([callKey]: the key, the listener and the call's
 *   nonce), AES-256-GCM, each direction with its own label, each piece with its own counter and an
 *   end marker. Whatever relays or reads the loopback connection sees ciphertext, can't change a
 *   byte of it undetected, and can't play it again (the nonce is good once). The same for every
 *   WebSocket message, both ways.
 * - **The listener proves itself** before Lens signs anything: the ping answers a challenge with
 *   the same key; and only the holder of the key can seal an answer, so a sealed answer is the
 *   app's word, and anything else answering on the port is noticed at once.
 *
 * Every message starts with its own label, so a MAC made for one (a ping's proof, which anyone can
 * ask for) is never one for another (a call). Nothing of Android in here (unit-tested, with the
 * same numbers Lens's tests use).
 */
object BridgeCrypto {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        for (p in parts) d.update(p)
        return d.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) m.update(p)
        return m.doFinal()
    }

    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** Base64url without padding, and nothing else; null otherwise. */
    fun unb64(s: String?): ByteArray? {
        if (s == null || !B64.matches(s) || s.length % 4 == 1) return null
        return runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** Compared in full, whatever differs first. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun text(s: String) = s.toByteArray(Charsets.UTF_8)

    // ─── X25519 (RFC 7748, section 5) ───────────────────────────────────────

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    private fun littleEndian(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun toLittleEndian(n: BigInteger): ByteArray {
        val big = n.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, big.size)) out[i] = big[big.size - 1 - i]
        return out
    }

    /** The scalar [k] (clamped here) times the point [u], 32 bytes each. */
    fun x25519(k: ByteArray, u: ByteArray): ByteArray {
        require(k.size == 32 && u.size == 32) { "32 bytes each" }
        val s = k.copyOf()
        s[0] = (s[0].toInt() and 248).toByte()
        s[31] = ((s[31].toInt() and 127) or 64).toByte()
        val scalar = littleEndian(s)
        val uu = u.copyOf()
        uu[31] = (uu[31].toInt() and 127).toByte()
        val x1 = littleEndian(uu).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = scalar.testBit(t)
            if (swap != bit) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            swap = bit
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val sum = da.add(cb).mod(P)
            val diff = da.subtract(cb).mod(P)
            x3 = sum.multiply(sum).mod(P)
            z3 = x1.multiply(diff.multiply(diff).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap) {
            x2 = x3.also { x3 = x2 }
            z2 = z3.also { z3 = z2 }
        }
        return toLittleEndian(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }

    fun x25519Public(priv: ByteArray): ByteArray = x25519(priv, BASE)

    // ─── The protocol (the same derivations as Lens's data/bridge.ts) ───────

    /** Lens's commitment to its nonce, sent with the ask before it sees the bridge's half. */
    fun commitment(lensNonce: ByteArray): String = b64(sha256(text("hnb2-commit\n"), lensNonce))

    /** The six digits both screens show, from the whole exchange. */
    fun code(lensKey: ByteArray, appKey: ByteArray, lensNonce: ByteArray, appNonce: ByteArray): String {
        val h = sha256(text("hnb2-code\n"), lensKey, appKey, lensNonce, appNonce)
        val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        // Western digits whatever the phone's language: the glasses show the same six.
        return String.format(java.util.Locale.ROOT, "%06d", n % 1_000_000)
    }

    /** The token both sides work out from the agreed secret; it never travels, and only its SHA-256 is kept here. */
    fun token(shared: ByteArray, lensKey: ByteArray, appKey: ByteArray, lensNonce: ByteArray, appNonce: ByteArray): String =
        BridgeAccess.TOKEN_PREFIX + b64(hmac(shared, text("hnb2-token\n"), lensKey, appKey, lensNonce, appNonce))

    /** A key's short public name (what a signed call and a ping's challenge give to say which key). */
    fun kid(key: ByteArray): String = b64(sha256(text("hnb2-kid\n"), key)).take(16)

    /** The bridge's word that it allowed an ask, made with the agreed key. */
    fun confirmation(key: ByteArray, requestId: String): String = b64(hmac(key, text("hnb2-confirm\n$requestId")))

    /** The ping's answer to Lens's challenge [n]: that this listener holds the key. */
    fun pingProof(key: ByteArray, listener: String, n: String): String = b64(hmac(key, text("hnb3-ping\n$listener\n$n")))

    /**
     * The key one call's body, answer or socket is sealed with ([BridgeSeal]): the agreed key, this
     * listener and the call's own nonce, which the bridge takes once. So nothing sealed for one
     * call opens another's, and a relay that plays a call again gets nothing.
     */
    fun callKey(key: ByteArray, listener: String, nonce: String): ByteArray = hmac(key, text("hnb3-key\n$listener\n$nonce"))

    /** A body as a signature names it: its SHA-256, base64url (of the body as sent: the sealed bytes). */
    fun bodyHash(body: ByteArray): String = b64(sha256(body))

    /** What a call without a body signs (a `GET`, a socket's opening). */
    val EMPTY_BODY: String = bodyHash(ByteArray(0))

    /** What a call's signature covers. */
    fun signature(key: ByteArray, listener: String, method: String, path: String, query: String, ts: String, nonce: String, body: String): String =
        b64(hmac(key, text("hnb3\n$listener\n$method\n$path\n$query\n$ts\n$nonce\n$body")))

    /** A query as it's signed: its parts as sent, less empty ones and the signature's own (`hnb`). */
    fun canonicalQuery(raw: String?): String =
        raw.orEmpty().split('&').filter { it.isNotEmpty() && decode(it.substringBefore('=')) != BridgeHttp.SIGNATURE_PARAM }.joinToString("&")

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private val B64 = Regex("^[A-Za-z0-9_-]*$")
}
