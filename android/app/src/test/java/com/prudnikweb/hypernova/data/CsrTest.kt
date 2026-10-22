package com.prudnikweb.hypernova.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** The device certificate request (docs/PROTOCOL.md, "Local network (direct HTTPS)"): PKCS#10, built by hand. */
class CsrTest {
    /** Reads one DER element at [at]: its tag, and where its body starts and ends. */
    private fun tlv(b: ByteArray, at: Int): Triple<Int, Int, Int> {
        val tag = b[at].toInt() and 0xff
        var i = at + 1
        var len = b[i].toInt() and 0xff
        i++
        if (len and 0x80 != 0) {
            val n = len and 0x7f
            len = 0
            repeat(n) { len = (len shl 8) or (b[i].toInt() and 0xff); i++ }
        }
        return Triple(tag, i, i + len)
    }

    @Test fun derPrimitives() {
        assertArrayEquals(byteArrayOf(0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02), Der.oid("1.2.840.10045.4.3.2"))
        assertArrayEquals(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), Der.oid("2.5.4.3"))
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x00), Der.int(0))
        assertArrayEquals(byteArrayOf(0x0c, 0x02, 0x68, 0x69), Der.utf8("hi"))
        assertArrayEquals(byteArrayOf(0xa0.toByte(), 0x00), Der.ctx(0, ByteArray(0)))
        // Long form lengths.
        val long = Der.tlv(0x04, ByteArray(300))
        assertEquals(0x82, long[1].toInt() and 0xff)
        assertEquals(1, long[2].toInt())
        assertEquals(44, long[3].toInt())
        assertEquals(304, long.size)
    }

    @Test fun buildsASignedRequestForTheKey() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = gen.generateKeyPair()
        val csr = Csr.build("dev12345", kp.public, kp.private)
        // CertificationRequest ::= SEQUENCE { info, algorithm, signature }
        val (tag, start, end) = tlv(csr, 0)
        assertEquals(0x30, tag)
        assertEquals(csr.size, end)
        val (infoTag, _, infoEnd) = tlv(csr, start)
        assertEquals(0x30, infoTag)
        val info = csr.copyOfRange(start, infoEnd)
        assertArrayEquals(Csr.info("dev12345", kp.public.encoded), info)
        val (algTag, algStart, algEnd) = tlv(csr, infoEnd)
        assertEquals(0x30, algTag)
        assertArrayEquals(Der.oid("1.2.840.10045.4.3.2"), csr.copyOfRange(algStart, algEnd))
        val (sigTag, sigStart, sigEnd) = tlv(csr, algEnd)
        assertEquals(0x03, sigTag)
        assertEquals(0, csr[sigStart].toInt())
        val sig = csr.copyOfRange(sigStart + 1, sigEnd)
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(kp.public); update(info) }.verify(sig))
        // The info: version 0, then the subject with CN = the device id, then the key as given.
        val (_, vStart, vEnd) = tlv(info, 0).let { (_, s, _) -> tlv(info, s) }
        assertEquals(0, info[vStart].toInt())
        val subjectAt = vEnd
        val (subjTag, _, subjEnd) = tlv(info, subjectAt)
        assertEquals(0x30, subjTag)
        assertTrue(String(info.copyOfRange(subjectAt, subjEnd), Charsets.ISO_8859_1).contains("dev12345"))
        val (spkiTag, _, spkiEnd) = tlv(info, subjEnd)
        assertEquals(0x30, spkiTag)
        assertArrayEquals(kp.public.encoded, info.copyOfRange(subjEnd, spkiEnd))
        assertEquals(0xa0, info[spkiEnd].toInt() and 0xff)
    }
}
