package com.madix.beam.protocol.frame

import com.madix.beam.protocol.Protocol
import com.madix.beam.protocol.ProtocolException
import com.madix.beam.protocol.codec.ByteReader
import com.madix.beam.protocol.codec.ByteWriter
import java.nio.ByteBuffer

/** Confidentiality mode negotiated for a transfer. */
enum class SecurityMode(val code: Int) {
    /** Payload is sealed with a key derivable from the header; integrity only. */
    OPEN(0),

    /** Key derived from a shared password with PBKDF2-HMAC-SHA256. */
    PASSWORD(1),

    /** Key agreed with ephemeral ECDH P-256 after the sender scanned the receiver's pairing code. */
    PAIRED(2);

    companion object {
        fun fromCode(code: Int): SecurityMode =
            entries.firstOrNull { it.code == code } ?: throw ProtocolException("Unknown security mode")
    }
}

/**
 * Transfer parameters broadcast periodically by the sender. Everything a receiver needs to
 * reconstruct and open the sealed payload, except the secret itself.
 */
class TransferHeader(
    val sessionId: Int,
    val mode: SecurityMode,
    val cipherLength: Long,
    val blockSize: Int,
    val totalBlocks: Int,
    val segmentBlocks: Int,
    val chunkSize: Int,
    salt: ByteArray,
    noncePrefix: ByteArray,
    val kdfIterations: Int,
    senderPublicKey: ByteArray,
    receiverKeyId: ByteArray,
) {
    private val saltBytes = salt.copyOf()
    private val noncePrefixBytes = noncePrefix.copyOf()
    private val senderPublicKeyBytes = senderPublicKey.copyOf()
    private val receiverKeyIdBytes = receiverKeyId.copyOf()

    val salt: ByteArray get() = saltBytes.copyOf()
    val noncePrefix: ByteArray get() = noncePrefixBytes.copyOf()
    val senderPublicKey: ByteArray get() = senderPublicKeyBytes.copyOf()
    val receiverKeyId: ByteArray get() = receiverKeyIdBytes.copyOf()

    init {
        validate()
    }

    /** Seed shared by encoder and decoder for repair-symbol neighbour selection. */
    val sessionSeed: Long
        get() = ByteBuffer.wrap(saltBytes, 0, 8).long xor (sessionId.toLong() * -0x61c8864680b583ebL)

    /** Additional authenticated data binding the sealed payload to this header. */
    fun associatedData(): ByteArray = ByteWriter(64)
        .bytes(Protocol.QR_PREFIX.toByteArray(Charsets.US_ASCII))
        .u8(Protocol.VERSION)
        .i32(sessionId)
        .u8(mode.code)
        .i64(cipherLength)
        .i32(chunkSize)
        .bytes(saltBytes)
        .bytes(noncePrefixBytes)
        .bytes(receiverKeyIdBytes)
        .toByteArray()

    internal fun writeBody(writer: ByteWriter) {
        writer.u8(mode.code)
            .u8(0) // reserved flags
            .i64(cipherLength)
            .u16(blockSize)
            .i32(totalBlocks)
            .u16(segmentBlocks)
            .i32(chunkSize)
            .bytes(saltBytes)
            .bytes(noncePrefixBytes)
            .i32(kdfIterations)
            .u8(senderPublicKeyBytes.size)
            .bytes(senderPublicKeyBytes)
            .bytes(receiverKeyIdBytes)
    }

    /** Canonical encoding used to compare repeated header frames. */
    fun canonicalBytes(): ByteArray {
        val writer = ByteWriter(192).i32(sessionId)
        writeBody(writer)
        return writer.toByteArray()
    }

    private fun validate() {
        fun fail(reason: String): Nothing = throw ProtocolException("Invalid header: $reason")
        if (blockSize !in Protocol.MIN_BLOCK_SIZE..Protocol.MAX_BLOCK_SIZE) fail("block size")
        if (cipherLength < Protocol.GCM_TAG_BYTES + 1 ||
            cipherLength > Protocol.MAX_PAYLOAD_BYTES + MAX_TAG_OVERHEAD
        ) fail("payload length")
        val expectedBlocks = (cipherLength + blockSize - 1) / blockSize
        if (expectedBlocks != totalBlocks.toLong()) fail("block count")
        if (segmentBlocks !in 1..Protocol.MAX_SEGMENT_BLOCKS) fail("segment size")
        if (chunkSize !in MIN_CHUNK..MAX_CHUNK) fail("chunk size")
        if (saltBytes.size != Protocol.SALT_BYTES) fail("salt")
        if (noncePrefixBytes.size != Protocol.NONCE_PREFIX_BYTES) fail("nonce")
        if (receiverKeyIdBytes.size != Protocol.KEY_ID_BYTES) fail("key id")
        if (senderPublicKeyBytes.size > Protocol.MAX_PUBLIC_KEY_BYTES) fail("public key")
        when (mode) {
            SecurityMode.OPEN -> if (senderPublicKeyBytes.isNotEmpty()) fail("unexpected key")
            SecurityMode.PASSWORD -> {
                if (kdfIterations !in Protocol.PBKDF2_MIN_ITERATIONS..Protocol.PBKDF2_MAX_ITERATIONS) {
                    fail("iterations")
                }
                if (senderPublicKeyBytes.isNotEmpty()) fail("unexpected key")
            }
            SecurityMode.PAIRED -> if (senderPublicKeyBytes.isEmpty()) fail("missing key")
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TransferHeader && canonicalBytes().contentEquals(other.canonicalBytes())

    override fun hashCode(): Int = canonicalBytes().contentHashCode()

    companion object {
        const val MIN_CHUNK = 1024
        const val MAX_CHUNK = 1024 * 1024

        /** Upper bound of GCM tag overhead for the largest payload and smallest chunk. */
        private val MAX_TAG_OVERHEAD: Long =
            (Protocol.MAX_PAYLOAD_BYTES / MIN_CHUNK + 1) * Protocol.GCM_TAG_BYTES

        /** Encoded size of a header frame body for a given public-key length (without padding). */
        fun encodedFrameSize(publicKeyLength: Int): Int =
            1 + 1 + 4 + // magic, type, session
                1 + 1 + 8 + 2 + 4 + 2 + 4 + Protocol.SALT_BYTES + Protocol.NONCE_PREFIX_BYTES + 4 +
                1 + publicKeyLength + Protocol.KEY_ID_BYTES + Protocol.CRC_BYTES

        internal fun readBody(sessionId: Int, reader: ByteReader): TransferHeader {
            val mode = SecurityMode.fromCode(reader.u8())
            reader.u8() // reserved flags
            val cipherLength = reader.i64()
            val blockSize = reader.u16()
            val totalBlocks = reader.i32()
            val segmentBlocks = reader.u16()
            val chunkSize = reader.i32()
            val salt = reader.bytes(Protocol.SALT_BYTES)
            val noncePrefix = reader.bytes(Protocol.NONCE_PREFIX_BYTES)
            val iterations = reader.i32()
            val keyLength = reader.u8()
            if (keyLength > Protocol.MAX_PUBLIC_KEY_BYTES) throw ProtocolException("Public key too long")
            val publicKey = reader.bytes(keyLength)
            val keyId = reader.bytes(Protocol.KEY_ID_BYTES)
            return TransferHeader(
                sessionId = sessionId,
                mode = mode,
                cipherLength = cipherLength,
                blockSize = blockSize,
                totalBlocks = totalBlocks,
                segmentBlocks = segmentBlocks,
                chunkSize = chunkSize,
                salt = salt,
                noncePrefix = noncePrefix,
                kdfIterations = iterations,
                senderPublicKey = publicKey,
                receiverKeyId = keyId,
            )
        }
    }
}

/** One encoded fountain symbol. */
class DataSymbol(
    val sessionId: Int,
    val segment: Int,
    val symbolId: Int,
    val payload: ByteArray,
)

/** Pairing request displayed by a receiver so that a sender can agree a key with it. */
class PairingRequest(
    val nonce: Int,
    publicKey: ByteArray,
    val deviceName: String,
) {
    private val keyBytes = publicKey.copyOf()
    val publicKey: ByteArray get() = keyBytes.copyOf()

    init {
        if (keyBytes.isEmpty() || keyBytes.size > Protocol.MAX_PUBLIC_KEY_BYTES) {
            throw ProtocolException("Invalid pairing key")
        }
    }
}

/** Decoded frame. */
sealed interface Frame {
    class Header(val header: TransferHeader) : Frame
    class Data(val symbol: DataSymbol) : Frame
    class Pairing(val request: PairingRequest) : Frame
}
