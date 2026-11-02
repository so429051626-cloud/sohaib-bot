package com.madix.beam.protocol.frame

import com.madix.beam.protocol.Protocol
import com.madix.beam.protocol.ProtocolException
import com.madix.beam.protocol.codec.Base45
import com.madix.beam.protocol.codec.ByteReader
import com.madix.beam.protocol.codec.ByteWriter
import java.util.zip.CRC32

/**
 * Binary framing and QR text wrapping.
 *
 * ```
 * frame   := magic(u8=0xB1) type(u8) session(i32) body crc32(i32)
 * header  := mode flags cipherLength blockSize totalBlocks segmentBlocks chunkSize
 *            salt[16] noncePrefix[7] iterations keyLen(u8) senderKey[keyLen] receiverKeyId[8]
 *            zero padding up to the data frame length
 * data    := segment(u16) symbol(i32) payload[blockSize]
 * pairing := keyLen(u8) receiverKey[keyLen] nameLen(u8) name[nameLen]
 * qr text := "MDX1" Base45(frame)
 * ```
 */
object FrameCodec {

    fun dataFrameLength(blockSize: Int): Int =
        Protocol.DATA_FRAME_OVERHEAD + blockSize + Protocol.CRC_BYTES

    /** Encodes a header frame padded to [frameLength] so that every frame has the same QR version. */
    fun encodeHeader(header: TransferHeader, frameLength: Int): ByteArray {
        val writer = ByteWriter(frameLength)
            .u8(Protocol.WIRE_MAGIC)
            .u8(Protocol.FRAME_TYPE_HEADER)
            .i32(header.sessionId)
        header.writeBody(writer)
        val padding = frameLength - Protocol.CRC_BYTES - writer.size
        if (padding > 0) writer.zeros(padding)
        return seal(writer)
    }

    fun encodeData(
        sessionId: Int,
        segment: Int,
        symbolId: Int,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size - offset,
    ): ByteArray {
        val writer = ByteWriter(Protocol.DATA_FRAME_OVERHEAD + length + Protocol.CRC_BYTES)
            .u8(Protocol.WIRE_MAGIC)
            .u8(Protocol.FRAME_TYPE_DATA)
            .i32(sessionId)
            .u16(segment)
            .i32(symbolId)
            .bytes(payload, offset, length)
        return seal(writer)
    }

    fun encodePairing(request: PairingRequest): ByteArray {
        val key = request.publicKey
        val writer = ByteWriter(64 + key.size)
            .u8(Protocol.WIRE_MAGIC)
            .u8(Protocol.FRAME_TYPE_PAIRING)
            .i32(request.nonce)
            .u8(key.size)
            .bytes(key)
            .shortString(request.deviceName, Protocol.MAX_DEVICE_NAME_BYTES)
        return seal(writer)
    }

    fun encodeInvite(invitation: LinkInvitation): ByteArray {
        val key = invitation.publicKey
        val writer = ByteWriter(128 + key.size)
            .u8(Protocol.WIRE_MAGIC)
            .u8(Protocol.FRAME_TYPE_INVITE)
            .i32(invitation.nonce)
            .u8(key.size)
            .bytes(key)
            .shortString(invitation.deviceName, Protocol.MAX_DEVICE_NAME_BYTES)
            .shortString(invitation.networkName, Protocol.MAX_NETWORK_NAME_BYTES)
            .shortString(invitation.passphrase, 63)
            .u16(invitation.port)
        val addresses = invitation.addresses
        writer.u8(addresses.size)
        addresses.forEach { writer.bytes(it) }
        return seal(writer)
    }

    /**
     * Parses and validates a binary frame.
     * @throws ProtocolException on any malformed or corrupted input.
     */
    fun decode(frame: ByteArray): Frame {
        if (frame.size < 1 + 1 + 4 + Protocol.CRC_BYTES) throw ProtocolException("Frame too short")
        val bodyEnd = frame.size - Protocol.CRC_BYTES
        val expected = crc(frame, 0, bodyEnd)
        val actual = ByteReader(frame, bodyEnd).i32()
        if (expected != actual) throw ProtocolException("Checksum mismatch")

        val reader = ByteReader(frame, 0, bodyEnd)
        if (reader.u8() != Protocol.WIRE_MAGIC) throw ProtocolException("Unsupported protocol revision")
        val type = reader.u8()
        val session = reader.i32()
        return when (type) {
            Protocol.FRAME_TYPE_HEADER -> Frame.Header(TransferHeader.readBody(session, reader))
            Protocol.FRAME_TYPE_DATA -> {
                val segment = reader.u16()
                val symbol = reader.i32()
                if (symbol < 0) throw ProtocolException("Invalid symbol identifier")
                val payload = reader.bytes(reader.remaining)
                if (payload.size !in Protocol.MIN_BLOCK_SIZE..Protocol.MAX_BLOCK_SIZE) {
                    throw ProtocolException("Invalid payload size")
                }
                Frame.Data(DataSymbol(session, segment, symbol, payload))
            }
            Protocol.FRAME_TYPE_PAIRING -> {
                val keyLength = reader.u8()
                if (keyLength == 0 || keyLength > Protocol.MAX_PUBLIC_KEY_BYTES) {
                    throw ProtocolException("Invalid pairing key")
                }
                val key = reader.bytes(keyLength)
                val name = reader.shortString(Protocol.MAX_DEVICE_NAME_BYTES)
                Frame.Pairing(PairingRequest(session, key, name))
            }
            Protocol.FRAME_TYPE_INVITE -> {
                val keyLength = reader.u8()
                if (keyLength == 0 || keyLength > Protocol.MAX_PUBLIC_KEY_BYTES) {
                    throw ProtocolException("Invalid invitation key")
                }
                val key = reader.bytes(keyLength)
                val name = reader.shortString(Protocol.MAX_DEVICE_NAME_BYTES)
                val network = reader.shortString(Protocol.MAX_NETWORK_NAME_BYTES)
                val passphrase = reader.shortString(63)
                val port = reader.u16()
                val count = reader.u8()
                if (count > Protocol.MAX_LINK_ADDRESSES) throw ProtocolException("Too many addresses")
                val addresses = List(count) { reader.bytes(4) }
                Frame.Invite(LinkInvitation(session, key, name, network, passphrase, port, addresses))
            }
            else -> throw ProtocolException("Unknown frame type")
        }
    }

    /** Wraps a binary frame into the QR text representation. */
    fun toQrText(frame: ByteArray): String {
        val out = StringBuilder(Protocol.QR_PREFIX.length + Base45.encodedLength(frame.size))
        out.append(Protocol.QR_PREFIX)
        Base45.appendEncoded(out, frame)
        return out.toString()
    }

    /** True when [text] carries the Madix Beam prefix. */
    fun isMadixText(text: String): Boolean = text.startsWith(Protocol.QR_PREFIX)

    /**
     * Unwraps QR text into binary frame bytes.
     * @return null when [text] does not belong to this protocol.
     * @throws ProtocolException when the text carries the prefix but is malformed.
     */
    fun fromQrText(text: String): ByteArray? {
        if (!isMadixText(text)) return null
        return Base45.decode(text, Protocol.QR_PREFIX.length)
    }

    /** Decodes QR text to a frame, or returns null for foreign content. */
    fun parseQrText(text: String): Frame? = fromQrText(text)?.let { decode(it) }

    private fun seal(writer: ByteWriter): ByteArray {
        val body = writer.toByteArray()
        val checksum = crc(body, 0, body.size)
        return ByteWriter(body.size + Protocol.CRC_BYTES).bytes(body).i32(checksum).toByteArray()
    }

    private fun crc(data: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }
}
