package com.prudnikweb.hypernova.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.data.DebugScrub
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
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Where this computer's key is now, as Settings says it. */
sealed interface KeyPlace {
    /** None yet, or none it can reach: one's made at the next certificate request. */
    data object Nowhere : KeyPlace
    data object InKeychain : KeyPlace
    data class InFile(val why: FileWhy) : KeyPlace
}

/**
 * This computer's device key (data/DeviceCert.kt), EC P-256 as on a phone, in the system's keychain ([Keychain]) where
 * one answers, else in one owner-only file ([file]), which is never left unable to connect.
 *
 * Either way this is weaker than a phone, and plainly so. Android's keystore does the signing itself, often in
 * hardware, and the app never holds the key's bytes. Here the bytes are in this process's memory, and any program
 * running as this user can ask a keychain for them while it's unlocked, as it can read the file. What a keychain adds is
 * that the key isn't lying readable on the disk or in a backup of the home folder. docs/DECISIONS.md, "The desktop
 * client's key in the keychain". The key is never sent anywhere, written to the log or put in a settings file.
 *
 * **The file wins.** It only exists because the keychain hadn't taken the key (a key from before there was one here, or
 * one made while it was locked), and it goes only once the keychain has given the same key back and that key has
 * signed. So what's in the file is always the newest, and a crash at any point loses nothing.
 */
class DesktopKeys(private val file: Path, private val log: Log, choice: KeychainChoice = KeychainChoice.OFF) : DeviceKeys {
    private val keychain = choice.keychain
    private var why = choice.without
    private var loaded: KeyPair? = null
    /** It didn't answer in this run: asked no more until the next start, which keeps a window from waiting on it again and again. */
    private var down = false

    var place by mutableStateOf<KeyPlace>(KeyPlace.Nowhere)
        private set

    /**
     * The first look: before there's a window, as a locked keychain may ask for its password here and the answer can
     * take a while. After this nothing on the event thread has to wait for a prompt, unless pairing makes the first key.
     */
    @Synchronized
    fun prepare() {
        runCatching { load() }
    }

    /** The keychain didn't answer in this run (its [prepare] waited out a prompt, say). The tokens needn't wait out the same one. */
    val keychainDown: Boolean
        @Synchronized get() = down && why == FileWhy.Down

    /**
     * Someone unlocked it, and said so ([DesktopTokens.retry]): asked again, not on the event thread. A key in the file moves
     * across, and one that couldn't be read comes from the keychain, so no new one is made for nothing.
     */
    @Synchronized
    fun retry() {
        if (keychain == null) return
        down = false
        runCatching {
            val pair = loaded
            if (pair == null) load() else if (place is KeyPlace.InFile) moveToKeychain(pair)
        }
    }

    /** The key, made if it isn't there. */
    @Synchronized
    override fun ensure(): Pair<PublicKey, PrivateKey> {
        val kp = load() ?: generate().also(::keep)
        return kp.public to kp.private
    }

    @Synchronized
    override fun privateKey(): PrivateKey? = load()?.private

    @Synchronized
    override fun publicKey(): PublicKey? = load()?.public

    @Synchronized
    override fun delete() {
        loaded = null
        Files.deleteIfExists(file)
        // One left in a locked keychain does no harm: a new pairing's certificate is what counts, and the file's next key replaces it.
        keychain?.let { k -> runCatching { k.delete() } }
        place = KeyPlace.Nowhere
    }

    private fun load(): KeyPair? {
        loaded?.let { return it }
        if (Files.isSymbolicLink(file)) throw IOException("$file is a symbolic link: refusing to read it as the device key.")
        readFile()?.let { pair ->
            loaded = pair
            place = KeyPlace.InFile(why)
            moveToKeychain(pair)
            return pair
        }
        val k = keychain.takeUnless { down } ?: return null
        val answer = runCatching { k.read() }.getOrElse { KeychainRead.Down }
        when (answer) {
            is KeychainRead.Found -> {
                val pair = parse(answer.text)
                if (pair == null) {
                    log.w(TAG, "the keychain's device key isn't readable: a new key will be made")
                    return null
                }
                // What a broken file left: the keychain's is the one in use.
                Files.deleteIfExists(file)
                loaded = pair
                place = KeyPlace.InKeychain
                return pair
            }
            KeychainRead.Missing -> return null
            KeychainRead.Down -> {
                wentDown()
                return null
            }
        }
    }

    private fun readFile(): KeyPair? {
        if (!Files.exists(file)) return null
        // Someone's chmod, or a restored backup: it may have been read, so it's said, and closed again.
        if (OwnerOnly.isOwnerOnly(file) != true) {
            log.w(TAG, "the device key could be read by others: made owner-only again. Unpair this computer and pair it again if that worries you.")
            OwnerOnly.protect(file, directory = false)
        }
        // An unreadable key is as good as none: the next certificate request makes a new one.
        return runCatching { parse(Files.readString(file)) }.getOrNull()
            ?: run {
                log.w(TAG, "the device key's file isn't readable: a new key will be made")
                null
            }
    }

    /** A new key: straight to the keychain if it takes it and gives it back, else the file. */
    private fun keep(pair: KeyPair) {
        loaded = pair
        val text = encode(pair)
        if (keychain != null && !down && intoKeychain(keychain, text, pair)) {
            Files.deleteIfExists(file)
            place = KeyPlace.InKeychain
            log.i(TAG, "the device key is in the keychain")
            return
        }
        OwnerOnly.write(file, text.toByteArray(Charsets.UTF_8))
        place = KeyPlace.InFile(why)
    }

    /** The file's key to the keychain. The file goes only once the keychain has proved it holds that key. */
    private fun moveToKeychain(pair: KeyPair) {
        val k = keychain.takeUnless { down } ?: return
        if (!intoKeychain(k, encode(pair), pair)) return
        try {
            Files.delete(file)
        } catch (e: IOException) {
            // In both for now: the next start tries again.
            log.w(TAG, "the device key's old file wouldn't go (${e.javaClass.simpleName})")
        }
        place = KeyPlace.InKeychain
        log.i(TAG, "the device key moved to the keychain, and its file is gone")
    }

    /** Written and read back, and what came back has signed for this key's public half. Never a word of what it said. */
    private fun intoKeychain(k: Keychain, text: String, pair: KeyPair): Boolean {
        val took = runCatching { k.write(text) }.getOrDefault(false)
        if (!took) {
            wentDown()
            return false
        }
        val back = runCatching { k.read() }.getOrElse { KeychainRead.Down }
        if (back !is KeychainRead.Found) {
            // Took it and then can't find it: nothing here says which of the two it means.
            if (back == KeychainRead.Down) wentDown() else didNotHold()
            return false
        }
        val same = parse(back.text)
        if (same == null || !proves(same, pair)) {
            didNotHold()
            return false
        }
        return true
    }

    private fun wentDown() {
        down = true
        why = FileWhy.Down
        log.w(TAG, "the keychain didn't answer: the device key is in its private file for now")
        if (place is KeyPlace.InFile) place = KeyPlace.InFile(why)
    }

    private fun didNotHold() {
        down = true
        why = FileWhy.DidNotHold
        log.w(TAG, "the keychain didn't give the device key back: it stays in its private file")
        if (place is KeyPlace.InFile) place = KeyPlace.InFile(why)
    }

    private fun proves(candidate: KeyPair, pair: KeyPair): Boolean = runCatching {
        if (!candidate.public.encoded.contentEquals(pair.public.encoded)) return false
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val signature = Signature.getInstance(SIGNATURE).apply { initSign(candidate.private); update(challenge) }.sign()
        Signature.getInstance(SIGNATURE).apply { initVerify(pair.public); update(challenge) }.verify(signature)
    }.getOrDefault(false)

    /** Null for anything but a whole key. Nothing of an exception's text goes further: a parser's can quote what it read. */
    private fun parse(text: String): KeyPair? = runCatching {
        val o = JSONObject(text)
        val ec = KeyFactory.getInstance(ALGORITHM)
        val decode = Base64.getDecoder()
        val secret = o.getString("private")
        val pair = KeyPair(
            ec.generatePublic(X509EncodedKeySpec(decode.decode(o.getString("public")))),
            ec.generatePrivate(PKCS8EncodedKeySpec(decode.decode(secret))),
        )
        // Cut from every debug-log line, whatever a line ever makes of it.
        DebugScrub.remember(secret)
        pair
    }.getOrNull()

    private fun generate(): KeyPair = KeyPairGenerator.getInstance(ALGORITHM).apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun encode(kp: KeyPair): String {
        val encode = Base64.getEncoder()
        val secret = encode.encodeToString(kp.private.encoded)
        DebugScrub.remember(secret)
        return JSONObject().put("public", encode.encodeToString(kp.public.encoded)).put("private", secret).toString()
    }

    private companion object {
        const val ALGORITHM = "EC"
        const val SIGNATURE = "SHA256withECDSA"
        const val TAG = "DeviceKey"
    }
}
