package com.madix.beam.protocol

/**
 * Wire-level constants of the Madix Beam optical transfer protocol (version 1).
 *
 * All multi-byte integers on the wire are big-endian. Every frame is protected by a CRC-32
 * trailer, encoded with Base45 (RFC 9285) and rendered as an alphanumeric-mode QR code.
 */
object Protocol {
    /** Protocol revision implemented by this library. */
    const val VERSION: Int = 1

    /** First byte of every binary frame; identifies the wire revision. */
    const val WIRE_MAGIC: Int = 0xB1

    /** Text prefix of every QR code produced by Madix Beam. Only QR alphanumeric characters. */
    const val QR_PREFIX: String = "MDX1"

    const val FRAME_TYPE_HEADER: Int = 0x01
    const val FRAME_TYPE_DATA: Int = 0x02
    const val FRAME_TYPE_PAIRING: Int = 0x10

    /** Bytes preceding the payload of a data frame: magic, type, session, segment, symbol. */
    const val DATA_FRAME_OVERHEAD: Int = 1 + 1 + 4 + 2 + 4

    /** CRC-32 trailer length. */
    const val CRC_BYTES: Int = 4

    /** Upper bound for the number of source blocks inside one fountain segment. */
    const val MAX_SEGMENT_BLOCKS: Int = 2048

    /** Plaintext bytes per authenticated-encryption chunk. */
    const val AEAD_CHUNK_SIZE: Int = 64 * 1024

    const val GCM_TAG_BYTES: Int = 16
    const val SALT_BYTES: Int = 16
    const val NONCE_PREFIX_BYTES: Int = 7
    const val KEY_ID_BYTES: Int = 8
    const val KEY_BYTES: Int = 32

    /** A header frame is emitted every [HEADER_INTERVAL] frames. */
    const val HEADER_INTERVAL: Int = 10

    /** One repair symbol is emitted for every [REPAIR_PERIOD] data slots. */
    const val REPAIR_PERIOD: Int = 4

    /** Largest sealed payload accepted by both sides. */
    const val MAX_PAYLOAD_BYTES: Long = 256L * 1024L * 1024L

    /** Largest total size of the unpacked files described by a container. */
    const val MAX_UNPACKED_BYTES: Long = 1024L * 1024L * 1024L

    const val MAX_ENTRIES: Int = 2_000

    const val PBKDF2_ITERATIONS: Int = 200_000
    const val PBKDF2_MIN_ITERATIONS: Int = 10_000
    const val PBKDF2_MAX_ITERATIONS: Int = 5_000_000

    const val MIN_BLOCK_SIZE: Int = 192
    const val MAX_BLOCK_SIZE: Int = 2048

    const val MAX_PUBLIC_KEY_BYTES: Int = 160
    const val MAX_DEVICE_NAME_BYTES: Int = 48
}

/** Raised when received bytes violate the protocol. */
open class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
