package com.prudnikweb.hypernova.data.live

import android.security.keystore.KeyGenParameterSpec
import okhttp3.tls.HeldCertificate
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyPairGeneratorSpi
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap

/**
 * "AndroidKeyStore" on the JVM, which Robolectric doesn't have: the provider DeviceCertStore asks for
 * (data/DeviceCert.kt), with an EC key pair generator that takes a [KeyGenParameterSpec] and a key
 * store of private-key entries, each with a self-signed certificate as the real one makes. The keys
 * are ordinary software keys, so they sign a request and a TLS handshake as the real ones do; what
 * the real store adds (the key never leaving secure hardware) can't be had off a phone.
 *
 * The provider is the JVM's, so it outlives a test: [reset] empties it.
 */
object FakeKeyStore {
    const val NAME = "AndroidKeyStore"

    /** Installs it once per JVM (Robolectric's sandboxes share java.security). */
    fun install() {
        if (Security.getProvider(NAME) == null) Security.addProvider(Fake())
        reset()
    }

    /** Every key goes. */
    fun reset() {
        KeyStore.getInstance(NAME).apply { load(null) }.let { ks -> ks.aliases().toList().forEach(ks::deleteEntry) }
    }

    private class Fake : Provider(NAME, 1.0, "Hypernova's tests: AndroidKeyStore on the JVM") {
        private val held = ConcurrentHashMap<String, KeyStore.PrivateKeyEntry>()

        init {
            putService(object : Service(this, "KeyStore", NAME, Store::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = Store(held)
            })
            putService(object : Service(this, "KeyPairGenerator", "EC", Gen::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = Gen(held)
            })
        }
    }

    private class Gen(private val entries: MutableMap<String, KeyStore.PrivateKeyEntry>) : KeyPairGeneratorSpi() {
        private var alias: String? = null
        private var curve = "secp256r1"

        override fun initialize(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException("a KeyGenParameterSpec, as AndroidKeyStore wants")

        override fun initialize(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            val spec = params as? KeyGenParameterSpec ?: throw java.security.InvalidAlgorithmParameterException("not a KeyGenParameterSpec")
            alias = spec.keystoreAlias
            (spec.algorithmParameterSpec as? ECGenParameterSpec)?.let { curve = it.name }
        }

        override fun generateKeyPair(): KeyPair {
            val a = alias ?: error("not initialized")
            check(curve == "secp256r1") { "only P-256 here: $curve" }
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
            // As AndroidKeyStore does: a self-signed certificate for the new key.
            val self = HeldCertificate.Builder().keyPair(kp).commonName("fake $a").build().certificate
            entries[a] = KeyStore.PrivateKeyEntry(kp.private, arrayOf<Certificate>(self))
            return kp
        }
    }

    private class Store(private val entries: MutableMap<String, KeyStore.PrivateKeyEntry>) : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineGetKey(alias: String, password: CharArray?): Key? = entries[alias]?.privateKey
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = entries[alias]?.certificateChain
        override fun engineGetCertificate(alias: String): Certificate? = entries[alias]?.certificate
        override fun engineGetCreationDate(alias: String): Date? = entries[alias]?.let { Date() }
        override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? = entries[alias]
        override fun engineSetKeyEntry(alias: String, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetKeyEntry(alias: String, key: ByteArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate?) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) { entries.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(entries.keys.toList())
        override fun engineContainsAlias(alias: String): Boolean = entries.containsKey(alias)
        override fun engineSize(): Int = entries.size
        override fun engineIsKeyEntry(alias: String): Boolean = entries.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = entries.entries.firstOrNull { it.value.certificate == cert }?.key
    }
}
