package com.prudnikweb.hypernova.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/*
 * The phone's device certificate (docs/PROTOCOL.md, "Local network (direct HTTPS)"): an EC P-256
 * key that never leaves AndroidKeyStore, a PKCS#10 request for it built here (CN = the deviceId),
 * and the certificate the Mac's device CA signs, presented on every local-route connection. The
 * Mac forgets the certificate the moment the phone is unpaired; the phone drops its copy too.
 */

/** Just enough DER to write a certificate request: nothing is parsed here. */
object Der {
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, body: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + len(body.size) + body
    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun int(v: Int): ByteArray = tlv(0x02, byteArrayOf(v.toByte()))
    fun utf8(s: String): ByteArray = tlv(0x0c, s.toByteArray(Charsets.UTF_8))
    /** A BIT STRING with no unused bits (keys and signatures). */
    fun bits(b: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + b)
    /** `[n]` constructed, implicit (the request's attributes). */
    fun ctx(n: Int, body: ByteArray): ByteArray = tlv(0xa0 or n, body)

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val bytes = ArrayList<Int>()
            var v = p
            do { bytes.add((v and 0x7f).toInt()); v = v shr 7 } while (v > 0)
            for (i in bytes.indices.reversed()) out.write(bytes[i] or (if (i > 0) 0x80 else 0))
        }
        return tlv(0x06, out.toByteArray())
    }
}

/** A PKCS#10 request for an EC P-256 key, signed with SHA-256. */
object Csr {
    private const val COMMON_NAME = "2.5.4.3"
    private const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"

    /**
     * `CertificationRequest ::= SEQUENCE { certificationRequestInfo, signatureAlgorithm, signature }`,
     * the info being `{ version 0, subject CN=[cn], subjectPKInfo, attributes [0] {} }`. The public
     * key's own encoding is already the SubjectPublicKeyInfo DER.
     */
    fun build(cn: String, publicKey: PublicKey, privateKey: PrivateKey): ByteArray {
        val info = info(cn, publicKey.encoded)
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey); update(info) }.sign()
        return Der.seq(info, Der.seq(Der.oid(ECDSA_SHA256)), Der.bits(sig))
    }

    fun info(cn: String, spki: ByteArray): ByteArray = Der.seq(
        Der.int(0),
        Der.seq(Der.set(Der.seq(Der.oid(COMMON_NAME), Der.utf8(cn)))),
        spki,
        Der.ctx(0, ByteArray(0)),
    )
}

/** This phone's certificate chain (its own, then the Mac's device CA) and the key behind it. */
class DeviceIdentity(val chain: Array<X509Certificate>, val key: PrivateKey) {
    /** Presents the chain to any server that asks (only the pinned local route ever does). */
    fun keyManager(): X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
        override fun getCertificateChain(alias: String?) = if (alias == ALIAS) chain else null
        override fun getPrivateKey(alias: String?) = if (alias == ALIAS) key else null
    }

    companion object {
        const val ALIAS = "hypernova-device"
    }
}

/**
 * The key in AndroidKeyStore and the certificates in app-private storage. One key per phone, shared
 * by every computer this phone pairs with; each signs its own certificate for it, kept per paired
 * computer ([server], data/Servers.kt: the first one's in the file it always had).
 */
class DeviceCertStore(context: Context, server: String = Servers.LEGACY_ID) {
    private val prefs = context.applicationContext.getSharedPreferences(Servers.scoped("hypernova.cert", server), Context.MODE_PRIVATE)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The key, made once. TLS needs the raw (NONE) digest too: Android's TLS signs the handshake itself. */
    private fun ensureKey(): Pair<PublicKey, PrivateKey> {
        val ks = keyStore()
        val have = runCatching { ks.getEntry(DeviceIdentity.ALIAS, null) as? KeyStore.PrivateKeyEntry }.getOrNull()
        if (have != null) return have.certificate.publicKey to have.privateKey
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(DeviceIdentity.ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .build(),
        )
        val kp = gen.generateKeyPair()
        return kp.public to kp.private
    }

    /** A request for a certificate naming [deviceId], as base64 DER for `POST /v1/device/cert`. */
    fun csr(deviceId: String): String {
        val (pub, priv) = ensureKey()
        return Base64.encodeToString(Csr.build(deviceId, pub, priv), Base64.NO_WRAP)
    }

    /** The certificate the Mac signed, kept with the CA that signed it and its listener's fingerprint. */
    fun install(certB64: String, caB64: String, fingerprint: String?) {
        // Parsed first, so a bad answer leaves what was there.
        parse(certB64); parse(caB64)
        prefs.edit().putString("cert", certB64).putString("ca", caB64).putString("fp", fingerprint).apply()
    }

    /** This computer's certificate goes; the key too when [dropKey] (no other computer's certificate is for it any more). */
    fun clear(dropKey: Boolean = true) {
        prefs.edit().clear().apply()
        if (dropKey) runCatching { keyStore().deleteEntry(DeviceIdentity.ALIAS) }
    }

    /** The chain and key to present, or null while the Mac hasn't signed one yet. */
    fun identity(): DeviceIdentity? {
        val cert = prefs.getString("cert", null) ?: return null
        val ca = prefs.getString("ca", null) ?: return null
        return runCatching {
            val entry = keyStore().getEntry(DeviceIdentity.ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
            DeviceIdentity(arrayOf(parse(cert), parse(ca)), entry.privateKey)
        }.getOrNull()
    }

    private fun parse(b64: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(b64, Base64.NO_WRAP).inputStream()) as X509Certificate

    /**
     * `POST /v1/device/cert` with a fresh request (over whichever route is active; the bearer token
     * authenticates it), then keeps the answer. The CN is the deviceId the pairing gave.
     */
    suspend fun request(api: Api, deviceId: String) {
        val res = api.post("/v1/device/cert", JSONObject().put("csr", csr(deviceId)))
        val cert = res.str("cert") ?: throw ApiException(Problem.Server, 200, "no_cert")
        val ca = res.str("ca") ?: throw ApiException(Problem.Server, 200, "no_ca")
        install(cert, ca, res.str("fingerprint"))
    }
}
