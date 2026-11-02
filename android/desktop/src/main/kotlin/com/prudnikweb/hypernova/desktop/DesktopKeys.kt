package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.DeviceKeys
import com.prudnikweb.hypernova.data.Log
import org.json.JSONObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * This computer's device key (data/DeviceCert.kt), EC P-256 as on a phone, in one owner-only file.
 *
 * This is weaker than a phone, and plainly so. Android's keystore does the signing itself, often in
 * hardware, and the app never holds the key's bytes. Here the bytes are in a file and in this process's
 * memory: nothing but the file's mode stands between the key and any program running as this user, and
 * whoever reads the disk or a backup of the home folder has it too, unless the disk is encrypted. The key
 * is never sent anywhere, written to the log or put in a settings file. docs/DECISIONS.md, "The desktop app".
 */
class DesktopKeys(private val file: Path, private val log: Log) : DeviceKeys {
    private var loaded: KeyPair? = null

    /** The key, made if it isn't there. */
    @Synchronized
    override fun ensure(): Pair<PublicKey, PrivateKey> {
        val kp = load() ?: generate().also { write(it); loaded = it }
        return kp.public to kp.private
    }

    @Synchronized
    override fun privateKey(): PrivateKey? = load()?.private

    @Synchronized
    override fun delete() {
        loaded = null
        Files.deleteIfExists(file)
    }

    private fun load(): KeyPair? {
        loaded?.let { return it }
        if (Files.isSymbolicLink(file)) throw IOException("$file is a symbolic link: refusing to read it as the device key.")
        if (!Files.exists(file)) return null
        // Someone's chmod, or a restored backup: it may have been read, so it's said, and closed again.
        if (OwnerOnly.isOwnerOnly(file) != true) {
            log.w(TAG, "the device key could be read by others: made owner-only again. Unpair this computer and pair it again if that worries you.")
            OwnerOnly.protect(file, directory = false)
        }
        // An unreadable key is as good as none: the next certificate request makes a new one.
        val pair = runCatching {
            val o = JSONObject(Files.readString(file))
            val ec = KeyFactory.getInstance(ALGORITHM)
            val decode = Base64.getDecoder()
            KeyPair(
                ec.generatePublic(X509EncodedKeySpec(decode.decode(o.getString("public")))),
                ec.generatePrivate(PKCS8EncodedKeySpec(decode.decode(o.getString("private")))),
            )
        }.onFailure { log.w(TAG, "the device key's file isn't readable (${it.javaClass.simpleName}): a new key will be made") }.getOrNull()
        loaded = pair
        return pair
    }

    private fun generate(): KeyPair = KeyPairGenerator.getInstance(ALGORITHM).apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun write(kp: KeyPair) {
        val encode = Base64.getEncoder()
        val text = JSONObject().put("public", encode.encodeToString(kp.public.encoded)).put("private", encode.encodeToString(kp.private.encoded)).toString()
        OwnerOnly.write(file, text.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val ALGORITHM = "EC"
        const val TAG = "DeviceKey"
    }
}
