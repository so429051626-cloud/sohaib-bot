package com.madix.beam.protocol.crypto

import com.madix.beam.protocol.Protocol
import com.madix.beam.protocol.ProtocolException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.Normalizer
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Thrown when key material is invalid or a key agreement fails. */
class KeyAgreementException(message: String, cause: Throwable? = null) : ProtocolException(message, cause)

internal object Digests {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }
}

/** HKDF with HMAC-SHA-256 (RFC 5869). */
object Hkdf {
    private const val ALGORITHM = "HmacSHA256"

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, ALGORITHM))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, ALGORITHM))
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - produced)
            System.arraycopy(previous, 0, output, produced, take)
            produced += take
            counter++
        }
        prk.fill(0)
        return output
    }
}

/**
 * PBKDF2-HMAC-SHA-256 (RFC 8018) implemented over the platform HMAC so that the result is
 * identical on every JCA provider. Passwords are NFC-normalised and encoded as UTF-8.
 */
object PasswordKdf {
    fun derive(password: CharArray, salt: ByteArray, iterations: Int, length: Int = Protocol.KEY_BYTES): ByteArray {
        require(iterations > 0)
        require(length in 1..32)
        val passwordBytes = Normalizer.normalize(String(password), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        if (passwordBytes.isEmpty()) throw KeyAgreementException("Empty password")
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            val u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            return t.copyOf(length).also { t.fill(0); u.fill(0) }
        } finally {
            passwordBytes.fill(0)
        }
    }
}

/** Ephemeral ECDH key pair on NIST P-256. Public keys travel as X.509 SubjectPublicKeyInfo. */
class EphemeralKeyPair private constructor(private val keyPair: KeyPair) {

    val publicKey: ByteArray get() = keyPair.public.encoded

    /** Short identifier of the public key: the first 8 bytes of its SHA-256 digest. */
    val keyId: ByteArray get() = keyIdOf(publicKey)

    fun agree(peerPublicKey: ByteArray): ByteArray {
        try {
            val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
            if (peer !is ECPublicKey) throw KeyAgreementException("Peer key is not an EC key")
            val own = keyPair.public as ECPublicKey
            if (peer.params.curve != own.params.curve || peer.params.order != own.params.order) {
                throw KeyAgreementException("Peer key uses an unexpected curve")
            }
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(keyPair.private)
            agreement.doPhase(peer, true)
            return agreement.generateSecret()
        } catch (e: KeyAgreementException) {
            throw e
        } catch (e: Exception) {
            throw KeyAgreementException("Key agreement failed", e)
        }
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): EphemeralKeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"), random)
            return EphemeralKeyPair(generator.generateKeyPair())
        }

        fun keyIdOf(publicKey: ByteArray): ByteArray =
            Digests.sha256(publicKey).copyOf(Protocol.KEY_ID_BYTES)

        /** Validates that [publicKey] is a well-formed P-256 public key. */
        fun isValidPublicKey(publicKey: ByteArray): Boolean = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            key is ECPublicKey && key.params.curve.field.fieldSize == 256
        } catch (e: Exception) {
            false
        }
    }
}

/** Key derivation for each security mode, plus the short authentication string. */
object KeySchedule {
    private val OPEN_IKM = "madix-beam/v1/open-key".toByteArray(Charsets.US_ASCII)

    private fun info(label: String, vararg parts: ByteArray): ByteArray {
        val prefix = "madix-beam/v1/$label".toByteArray(Charsets.US_ASCII)
        return parts.fold(prefix) { acc, part -> acc + part }
    }

    fun openKey(salt: ByteArray): ByteArray =
        Hkdf.derive(salt, OPEN_IKM, info("open"), Protocol.KEY_BYTES)

    fun passwordKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val stretched = PasswordKdf.derive(password, salt, iterations)
        return try {
            Hkdf.derive(salt, stretched, info("password"), Protocol.KEY_BYTES)
        } finally {
            stretched.fill(0)
        }
    }

    fun pairedKey(sharedSecret: ByteArray, salt: ByteArray, receiverPublicKey: ByteArray, senderPublicKey: ByteArray): ByteArray =
        Hkdf.derive(
            salt,
            sharedSecret,
            info("paired", Digests.sha256(receiverPublicKey), Digests.sha256(senderPublicKey)),
            Protocol.KEY_BYTES,
        )

    /**
     * Six-digit short authentication string shown on both devices after pairing. Matching
     * codes prove that no third party substituted its own key.
     */
    fun shortAuthString(receiverPublicKey: ByteArray, senderPublicKey: ByteArray, salt: ByteArray): String {
        val digest = Digests.sha256(
            "madix-beam/v1/sas".toByteArray(Charsets.US_ASCII),
            receiverPublicKey,
            senderPublicKey,
            salt,
        )
        val value = ((digest[0].toLong() and 0xFF) shl 24) or
            ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or
            (digest[3].toLong() and 0xFF)
        val code = (value % 1_000_000L).toString().padStart(6, '0')
        return code.substring(0, 3) + " " + code.substring(3)
    }
}
