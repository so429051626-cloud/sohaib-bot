package com.madix.beam.protocol

import com.madix.beam.protocol.crypto.AuthenticationException
import com.madix.beam.protocol.crypto.ChunkedAead
import com.madix.beam.protocol.crypto.EphemeralKeyPair
import com.madix.beam.protocol.crypto.Hkdf
import com.madix.beam.protocol.crypto.KeySchedule
import com.madix.beam.protocol.crypto.PasswordKdf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random

class CryptoTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hkdfRfc5869Case1() {
        val okm = Hkdf.derive(
            salt = hex("000102030405060708090a0b0c"),
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun pbkdf2MatchesRfc7914AndPlatform() {
        val vector = PasswordKdf.derive("passwd".toCharArray(), "salt".toByteArray(), 1, 32)
        assertArrayEquals(hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"), vector)
        for (password in listOf("correct horse", "كلمة مرور قوية", "p@ss✓")) {
            val salt = Random(password.length).nextBytes(16)
            val ours = PasswordKdf.derive(password.toCharArray(), salt, 2_000)
            val platform = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, 2_000, 256)).encoded
            assertArrayEquals(platform, ours)
        }
    }

    @Test
    fun ecdhAgreementAndShortAuthString() {
        val receiver = EphemeralKeyPair.generate()
        val sender = EphemeralKeyPair.generate()
        assertEquals(91, receiver.publicKey.size)
        assertTrue(EphemeralKeyPair.isValidPublicKey(receiver.publicKey))
        assertFalse(EphemeralKeyPair.isValidPublicKey(ByteArray(91) { 1 }))
        val a = sender.agree(receiver.publicKey)
        val b = receiver.agree(sender.publicKey)
        assertArrayEquals(a, b)
        val salt = Random(5).nextBytes(16)
        val k1 = KeySchedule.pairedKey(a, salt, receiver.publicKey, sender.publicKey)
        val k2 = KeySchedule.pairedKey(b, salt, receiver.publicKey, sender.publicKey)
        assertArrayEquals(k1, k2)
        val sas1 = KeySchedule.shortAuthString(receiver.publicKey, sender.publicKey, salt)
        val sas2 = KeySchedule.shortAuthString(receiver.publicKey, sender.publicKey, salt)
        assertEquals(sas1, sas2)
        assertTrue(sas1.matches(Regex("\\d{3} \\d{3}")))
        val intruder = EphemeralKeyPair.generate()
        assertNotEquals(sas1, KeySchedule.shortAuthString(receiver.publicKey, intruder.publicKey, salt))
    }

    private fun aead(key: ByteArray = ByteArray(32) { 1 }, aad: ByteArray = byteArrayOf(1, 2, 3)) =
        ChunkedAead(key, ByteArray(7) { 4 }, aad, 4096)

    private fun seal(plain: ByteArray, cipher: ChunkedAead = aead()): ByteArray {
        val out = ByteArrayOutputStream()
        val written = cipher.encrypt(plain.inputStream(), plain.size.toLong(), out)
        assertEquals(ChunkedAead.cipherLength(plain.size.toLong(), 4096), written)
        return out.toByteArray()
    }

    @Test
    fun chunkedAeadRoundTrips() {
        for (size in listOf(1, 15, 4095, 4096, 4097, 8192, 50_001)) {
            val plain = Random(size).nextBytes(size)
            val sealed = seal(plain)
            assertEquals(size.toLong(), ChunkedAead.plainLength(sealed.size.toLong(), 4096))
            val opened = aead().decrypt(sealed.inputStream(), sealed.size.toLong()).readBytes()
            assertArrayEquals(plain, opened)
        }
    }

    @Test
    fun chunkedAeadDetectsTamperingTruncationAndWrongKey() {
        val plain = Random(9).nextBytes(10_000)
        val sealed = seal(plain)

        val tampered = sealed.copyOf().also { it[5000] = (it[5000].toInt() xor 1).toByte() }
        expectAuthFailure { aead().decrypt(tampered.inputStream(), tampered.size.toLong()).readBytes() }

        // Drop the last chunk entirely: the new last chunk was sealed with last=0.
        val truncated = sealed.copyOf(2 * (4096 + 16))
        expectAuthFailure { aead().decrypt(truncated.inputStream(), truncated.size.toLong()).readBytes() }

        val wrongKey = aead(key = ByteArray(32) { 2 })
        val failure = expectAuthFailure { wrongKey.decrypt(sealed.inputStream(), sealed.size.toLong()).readBytes() }
        assertEquals(0L, failure.chunkIndex)

        val wrongAad = aead(aad = byteArrayOf(9))
        expectAuthFailure { wrongAad.decrypt(sealed.inputStream(), sealed.size.toLong()).readBytes() }
    }

    private fun expectAuthFailure(block: () -> Unit): AuthenticationException {
        try {
            block()
        } catch (e: AuthenticationException) {
            return e
        }
        fail("Authentication failure expected")
        throw IllegalStateException()
    }
}
