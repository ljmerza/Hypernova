package com.prudnikweb.hypernova.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge's cryptography (BridgeCrypto): X25519 as RFC 7748 gives it, and the protocol's numbers
 * for one exchange with fixed keys and nonces, the same ones Lens's tests hold its own code to
 * (lens/test/bridge.test.ts, "the protocol's numbers"), there checked against node:crypto too. So
 * the app and Lens work out the same code, token, key, proof and signature, or both tests fail.
 */
class BridgeCryptoTest {
    private fun hex(s: String) = BridgeCrypto.unhex(s)

    @Test fun x25519AsRfc7748GivesIt() {
        // Section 5.2.
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            BridgeCrypto.hex(BridgeCrypto.x25519(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"), hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))),
        )
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            BridgeCrypto.hex(BridgeCrypto.x25519(hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"), hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"))),
        )
        // Section 6.1: Alice and Bob agree.
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", BridgeCrypto.hex(BridgeCrypto.x25519Public(alice)))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", BridgeCrypto.hex(BridgeCrypto.x25519Public(bob)))
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, BridgeCrypto.hex(BridgeCrypto.x25519(alice, BridgeCrypto.x25519Public(bob))))
        assertEquals(shared, BridgeCrypto.hex(BridgeCrypto.x25519(bob, BridgeCrypto.x25519Public(alice))))
        // A point of small order agrees on zero, whatever the key (BridgeAccess refuses it).
        assertTrue(BridgeCrypto.x25519(bob, ByteArray(32)).all { it == 0.toByte() })
    }

    /** Lens is Alice, the app Bob: the app's side of the exchange gives Lens's numbers. */
    @Test fun theProtocolsNumbersAreLenss() {
        val lensKey = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val appPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val appKey = BridgeCrypto.x25519Public(appPriv)
        assertEquals("3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08", BridgeCrypto.b64(appKey))
        assertEquals("hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo", BridgeCrypto.b64(lensKey))
        val lensNonce = hex("000102030405060708090a0b0c0d0e0f")
        val appNonce = hex("f0e1d2c3b4a5968778695a4b3c2d1e0f")
        assertEquals("NEeFLkoRsuPswHb3YaLUaMF_9L8BmbPiPm3JGMS786s", BridgeCrypto.commitment(lensNonce))
        assertEquals("392602", BridgeCrypto.code(lensKey, appKey, lensNonce, appNonce))
        val token = BridgeCrypto.token(BridgeCrypto.x25519(appPriv, lensKey), lensKey, appKey, lensNonce, appNonce)
        assertEquals("hnb_vrLDd3PSfnZXLwuwgRZfml7XU2EkJBDBm51AldXQf_A", token)
        // What the app keeps of it, and signs with.
        val keyHex = BridgeAccess.sha256(token)
        assertEquals("ad0b3896686cf715ed395d1379592b6ef2d185dd883b8bf0543c2063705476a0", keyHex)
        val key = hex(keyHex)
        assertEquals("yn8jTX6SL2djsVIw", BridgeCrypto.kid(key))
        assertEquals("Kuju7EKcMwhaaNi54Uu-eyynpZ5jZNRCIPuXbc1dCl4", BridgeCrypto.pingProof(key, "listener-1", "challenge-nonce-0001"))
        assertEquals("0GkXoe0-7WbKDvOm-x652xp9Tkv-x4HCcqhckmy8uZ4", BridgeCrypto.confirmation(key, "req-0001"))
        assertEquals("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU", BridgeCrypto.EMPTY_BODY)
        assertEquals("QGLtr3UPuAdOfoPgyQKMlOMkaKi28WFHdDKO8EUVD5M", BridgeCrypto.bodyHash("{\"ok\":true}".toByteArray()))
        assertEquals("a=1&b=%20x", BridgeCrypto.canonicalQuery("a=1&&hnb=x.1.y.z&b=%20x"))
        val body = BridgeCrypto.bodyHash("{\"text\":\"hi\"}".toByteArray())
        assertEquals("57mV76dVxf87hNIYi1jLSukWpZRw6zdh34qBTxF2NQA", body)
        assertEquals(
            "8JgU02H7q1P_pM_K_pusw61MFUbuBvODnwGmr4V5FQU",
            BridgeCrypto.signature(key, "listener-1", "POST", "/v1/sessions/s1/prompt", BridgeCrypto.canonicalQuery("a=1&b=%20x&hnb=old"), "1800000000000", "nonce-0001", body),
        )
        // The key one call is sealed with (BridgeSealTest seals with it).
        assertEquals("b1e7d263d5e495f5b65eacc11d330ea1146cfc736bed7e984fe371dff56256c6", BridgeCrypto.hex(BridgeCrypto.callKey(key, "listener-1", "nonce-0001")))
    }

    /** Each message has its own label, so a MAC anyone can ask for (the ping's proof) is never one for another. */
    @Test fun aProofIsNeverASignatureOrAConfirmation() {
        val key = ByteArray(32) { it.toByte() }
        val proof = BridgeCrypto.pingProof(key, "L", "n")
        assertNotEquals(proof, BridgeCrypto.signature(key, "L", "GET", "/", "", "1", "n", BridgeCrypto.EMPTY_BODY))
        assertNotEquals(proof, BridgeCrypto.confirmation(key, "L\nn"))
        assertNotEquals(proof, BridgeCrypto.b64(BridgeCrypto.callKey(key, "L", "n")))
        // A query that differs only in the signature's own parameter signs the same.
        assertEquals(BridgeCrypto.canonicalQuery("a=1&hnb=1"), BridgeCrypto.canonicalQuery("hnb=2&a=1&h%6Eb=3"))
        assertEquals("", BridgeCrypto.canonicalQuery(null))
    }

    @Test fun base64urlAndNothingElse() {
        for (n in 0..40) {
            val b = ByteArray(n) { (it * 37 + 11).toByte() }
            assertEquals(BridgeCrypto.hex(b), BridgeCrypto.hex(BridgeCrypto.unb64(BridgeCrypto.b64(b))!!))
        }
        assertNull(BridgeCrypto.unb64("a+b/"))
        assertNull(BridgeCrypto.unb64("abc="))
        assertNull(BridgeCrypto.unb64("a"))
        assertNull(BridgeCrypto.unb64(null))
        assertTrue(BridgeCrypto.same("abc", "abc"))
        assertTrue(!BridgeCrypto.same("abc", "abd"))
    }
}
