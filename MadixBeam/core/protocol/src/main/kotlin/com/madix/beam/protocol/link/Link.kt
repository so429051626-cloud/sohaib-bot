package com.madix.beam.protocol.link

import com.madix.beam.protocol.Protocol
import com.madix.beam.protocol.ProtocolException
import com.madix.beam.protocol.codec.ByteReader
import com.madix.beam.protocol.codec.ByteWriter
import com.madix.beam.protocol.container.EntryInfo
import com.madix.beam.protocol.container.EntryKind
import com.madix.beam.protocol.container.FileNames
import com.madix.beam.protocol.container.TransferSource
import com.madix.beam.protocol.crypto.EphemeralKeyPair
import com.madix.beam.protocol.crypto.Hkdf
import com.madix.beam.protocol.crypto.KeySchedule
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Direct device-to-device transfer over a TCP connection (Wi-Fi Direct group or shared LAN).
 *
 * ```
 * client hello := "MDXL" | version | senderKey | clientNonce[16] | senderName
 * server hello := "MDXL" | version | status | serverNonce[16] | receiverName
 * keys         := HKDF(salt = clientNonce || serverNonce, ECDH(sender, receiver),
 *                      "madix-beam/v1/link" || SHA256(receiverKey) || SHA256(senderKey)) -> c2s, s2c
 * record       := length(u32) | AES-256-GCM(key, direction || counter, type || payload)
 * session      := OFFER -> ACCEPT, then per entry DATA* END, then DONE -> RESULT
 * ```
 *
 * The receiver's public key comes from the QR invitation scanned by the sender, so only the
 * genuine receiver can derive the keys. The sender transmits file content only after it has
 * received a valid encrypted ACCEPT, which proves it is talking to that receiver.
 */
object LinkProtocol {
    val MAGIC = byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte())
    const val VERSION = 1

    /** Plaintext bytes carried by one data record. */
    const val CHUNK_SIZE = 256 * 1024

    internal const val MAX_PLAINTEXT = 1 + CHUNK_SIZE
    internal const val TAG_BYTES = 16
    internal const val MAX_RECORD = MAX_PLAINTEXT + TAG_BYTES
    internal const val NONCE_BYTES = 16

    internal const val STATUS_OK = 0
    internal const val STATUS_BUSY = 1
    internal const val STATUS_VERSION = 2

    internal const val ACCEPT_OK = 0
    internal const val ACCEPT_NO_SPACE = 1
    internal const val ACCEPT_DECLINED = 2

    internal const val R_OFFER = 1
    internal const val R_ACCEPT = 2
    internal const val R_DATA = 3
    internal const val R_END = 4
    internal const val R_DONE = 5
    internal const val R_RESULT = 6

    internal const val DIRECTION_C2S = 0x43325300
    internal const val DIRECTION_S2C = 0x53324300
}

/** Failure of a direct transfer, with a machine-readable [reason]. */
class LinkException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason { HANDSHAKE, VERSION, BUSY, DECLINED, INSUFFICIENT_STORAGE, INTEGRITY, PROTOCOL }
}

/** An entry announced by the sender before any content is transmitted. */
class OfferedEntry(
    val kind: EntryKind,
    val name: String,
    val mimeType: String,
    /** Announced size, or -1 when the sender does not know it in advance. */
    val sizeHint: Long,
)

class LinkOffer(val senderName: String, val entries: List<OfferedEntry>) {
    /** Sum of the known entry sizes. */
    val knownBytes: Long get() = entries.sumOf { it.sizeHint.coerceAtLeast(0) }
}

/** Progress of a running transfer. [totalBytes] is -1 when unknown. */
data class LinkProgress(
    val bytesDone: Long,
    val totalBytes: Long,
    val entryIndex: Int,
    val entryCount: Int,
    val entryName: String,
)

fun interface LinkProgressListener {
    fun onProgress(progress: LinkProgress)
}

/** Decision returned by a receiver when an offer arrives. */
enum class OfferDecision { ACCEPT, INSUFFICIENT_STORAGE, DECLINE }

/** Destination of received entries. */
interface LinkSink {
    fun onOffer(offer: LinkOffer): OfferDecision
    fun openEntry(index: Int, entry: OfferedEntry): OutputStream
    fun onEntryFinished(index: Int, entry: EntryInfo) {}
}

class LinkSendResult(val receiverName: String, val entries: List<EntryInfo>, val totalBytes: Long, val verificationCode: String)

class LinkReceiveResult(val senderName: String, val entries: List<EntryInfo>, val totalBytes: Long, val verificationCode: String)

/** AES-256-GCM record layer with independent keys and counters per direction. */
internal class RecordChannel(
    input: InputStream,
    private val output: OutputStream,
    sendKey: ByteArray,
    receiveKey: ByteArray,
    private val sendDirection: Int,
    private val receiveDirection: Int,
) {
    private val data = DataInputStream(input)
    private val sendSpec = SecretKeySpec(sendKey, "AES")
    private val receiveSpec = SecretKeySpec(receiveKey, "AES")
    private val sendCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val receiveCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var sendCounter = 0L
    private var receiveCounter = 0L

    /** Plaintext staging buffer; payload bytes start at offset 1. */
    val plain = ByteArray(LinkProtocol.MAX_PLAINTEXT)
    private val sealed = ByteArray(4 + LinkProtocol.MAX_RECORD)
    private val incoming = ByteArray(LinkProtocol.MAX_RECORD)
    private val opened = ByteArray(LinkProtocol.MAX_PLAINTEXT)

    private fun nonce(direction: Int, counter: Long): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = (direction ushr 24).toByte()
        nonce[1] = (direction ushr 16).toByte()
        nonce[2] = (direction ushr 8).toByte()
        nonce[3] = direction.toByte()
        for (i in 0 until 8) nonce[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        return nonce
    }

    /** Seals `plain[1 until 1 + length]` as a record of [type]. */
    fun sendStaged(type: Int, length: Int) {
        require(length in 0..LinkProtocol.CHUNK_SIZE)
        plain[0] = type.toByte()
        sendCipher.init(Cipher.ENCRYPT_MODE, sendSpec, GCMParameterSpec(128, nonce(sendDirection, sendCounter++)))
        val sealedLength = sendCipher.doFinal(plain, 0, length + 1, sealed, 4)
        sealed[0] = (sealedLength ushr 24).toByte()
        sealed[1] = (sealedLength ushr 16).toByte()
        sealed[2] = (sealedLength ushr 8).toByte()
        sealed[3] = sealedLength.toByte()
        output.write(sealed, 0, 4 + sealedLength)
    }

    fun send(type: Int, payload: ByteArray) {
        System.arraycopy(payload, 0, plain, 1, payload.size)
        sendStaged(type, payload.size)
    }

    fun flush() = output.flush()

    /** Receives one record. The returned payload view is valid until the next call. */
    fun receive(): Record {
        val length = try {
            data.readInt()
        } catch (e: EOFException) {
            throw LinkException(LinkException.Reason.PROTOCOL, "Connection closed", e)
        }
        if (length < LinkProtocol.TAG_BYTES + 1 || length > LinkProtocol.MAX_RECORD) {
            throw LinkException(LinkException.Reason.PROTOCOL, "Invalid record length")
        }
        data.readFully(incoming, 0, length)
        receiveCipher.init(Cipher.DECRYPT_MODE, receiveSpec, GCMParameterSpec(128, nonce(receiveDirection, receiveCounter++)))
        val openedLength = try {
            receiveCipher.doFinal(incoming, 0, length, opened, 0)
        } catch (e: AEADBadTagException) {
            throw LinkException(LinkException.Reason.INTEGRITY, "Record authentication failed", e)
        } catch (e: GeneralSecurityException) {
            throw LinkException(LinkException.Reason.INTEGRITY, "Record authentication failed", e)
        }
        return Record(opened[0].toInt() and 0xFF, opened, 1, openedLength - 1)
    }

    class Record(val type: Int, val buffer: ByteArray, val offset: Int, val length: Int) {
        fun copyPayload(): ByteArray = buffer.copyOfRange(offset, offset + length)
    }
}

internal object LinkHandshake {

    class ClientHello(val version: Int, val senderKey: ByteArray, val nonce: ByteArray, val senderName: String)

    class ServerHello(val version: Int, val status: Int, val nonce: ByteArray, val receiverName: String)

    fun writeClientHello(output: OutputStream, senderKey: ByteArray, nonce: ByteArray, senderName: String) {
        val body = ByteWriter(160)
            .bytes(LinkProtocol.MAGIC)
            .u8(LinkProtocol.VERSION)
            .u8(senderKey.size)
            .bytes(senderKey)
            .bytes(nonce)
            .shortString(senderName, Protocol.MAX_DEVICE_NAME_BYTES)
            .toByteArray()
        writeFramed(output, body)
    }

    fun readClientHello(input: InputStream): ClientHello {
        val reader = ByteReader(readFramed(input))
        checkMagic(reader)
        val version = reader.u8()
        val keyLength = reader.u8()
        if (keyLength == 0 || keyLength > Protocol.MAX_PUBLIC_KEY_BYTES) throw handshake("Invalid sender key")
        val key = reader.bytes(keyLength)
        val nonce = reader.bytes(LinkProtocol.NONCE_BYTES)
        val name = reader.shortString(Protocol.MAX_DEVICE_NAME_BYTES)
        return ClientHello(version, key, nonce, name)
    }

    fun writeServerHello(output: OutputStream, status: Int, nonce: ByteArray, receiverName: String) {
        val body = ByteWriter(64)
            .bytes(LinkProtocol.MAGIC)
            .u8(LinkProtocol.VERSION)
            .u8(status)
            .bytes(nonce)
            .shortString(receiverName, Protocol.MAX_DEVICE_NAME_BYTES)
            .toByteArray()
        writeFramed(output, body)
    }

    fun readServerHello(input: InputStream): ServerHello {
        val reader = ByteReader(readFramed(input))
        checkMagic(reader)
        val version = reader.u8()
        val status = reader.u8()
        val nonce = reader.bytes(LinkProtocol.NONCE_BYTES)
        val name = reader.shortString(Protocol.MAX_DEVICE_NAME_BYTES)
        return ServerHello(version, status, nonce, name)
    }

    /** Derives the client-to-server and server-to-client keys. */
    fun deriveKeys(
        sharedSecret: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        receiverKey: ByteArray,
        senderKey: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256")
        val receiverHash = digest.digest(receiverKey)
        val senderHash = digest.digest(senderKey)
        val info = "madix-beam/v1/link".toByteArray(Charsets.US_ASCII) + receiverHash + senderHash
        val okm = Hkdf.derive(clientNonce + serverNonce, sharedSecret, info, 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64).also { okm.fill(0) }
    }

    private fun writeFramed(output: OutputStream, body: ByteArray) {
        val framed = ByteWriter(body.size + 2).u16(body.size).bytes(body).toByteArray()
        output.write(framed)
        output.flush()
    }

    private fun readFramed(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val length = try {
            data.readUnsignedShort()
        } catch (e: EOFException) {
            throw handshake("Connection closed during handshake", e)
        }
        if (length !in 8..1024) throw handshake("Invalid handshake length")
        val body = ByteArray(length)
        data.readFully(body)
        return body
    }

    private fun checkMagic(reader: ByteReader) {
        try {
            if (!reader.bytes(4).contentEquals(LinkProtocol.MAGIC)) throw handshake("Not a Madix Beam peer")
        } catch (e: ProtocolException) {
            throw handshake("Malformed handshake", e)
        }
    }

    fun handshake(message: String, cause: Throwable? = null) =
        LinkException(LinkException.Reason.HANDSHAKE, message, cause)
}

/** Sends a set of sources to the receiver that issued the scanned invitation. */
class LinkSender(
    private val receiverPublicKey: ByteArray,
    private val senderName: String,
    private val random: SecureRandom = SecureRandom(),
) {
    fun send(
        input: InputStream,
        output: OutputStream,
        sources: List<TransferSource>,
        listener: LinkProgressListener = LinkProgressListener { },
        onHandshake: (code: String, receiverName: String) -> Unit = { _, _ -> },
    ): LinkSendResult {
        require(sources.isNotEmpty()) { "Nothing to send" }
        require(sources.size <= Protocol.MAX_ENTRIES) { "Too many items" }
        val ownKeys = EphemeralKeyPair.generate(random)
        val clientNonce = ByteArray(LinkProtocol.NONCE_BYTES).also(random::nextBytes)
        LinkHandshake.writeClientHello(output, ownKeys.publicKey, clientNonce, senderName)

        val hello = try {
            LinkHandshake.readServerHello(input)
        } catch (e: ProtocolException) {
            throw LinkHandshake.handshake("Malformed server hello", e)
        }
        when (hello.status) {
            LinkProtocol.STATUS_OK -> Unit
            LinkProtocol.STATUS_BUSY -> throw LinkException(LinkException.Reason.BUSY, "Receiver is busy")
            else -> throw LinkException(LinkException.Reason.VERSION, "Incompatible receiver")
        }

        val shared = ownKeys.agree(receiverPublicKey)
        val (c2s, s2c) = try {
            LinkHandshake.deriveKeys(shared, clientNonce, hello.nonce, receiverPublicKey, ownKeys.publicKey)
        } finally {
            shared.fill(0)
        }
        val channel = RecordChannel(input, output, c2s, s2c, LinkProtocol.DIRECTION_C2S, LinkProtocol.DIRECTION_S2C)
        c2s.fill(0)
        s2c.fill(0)
        val code = KeySchedule.shortAuthString(receiverPublicKey, ownKeys.publicKey, clientNonce)
        onHandshake(code, hello.receiverName)

        // Offer.
        val offered = sources.map { OfferedEntry(it.kind, FileNames.sanitize(it.displayName), it.mimeType.take(255), it.sizeHint) }
        val offer = ByteWriter(256 + offered.size * 96).string16(senderName, Protocol.MAX_DEVICE_NAME_BYTES).i32(offered.size)
        offered.forEach { entry ->
            offer.u8(entry.kind.code).string16(entry.name, 1024).string16(entry.mimeType, 255).i64(entry.sizeHint)
        }
        channel.send(LinkProtocol.R_OFFER, offer.toByteArray())
        channel.flush()

        // A valid ACCEPT proves that the peer holds the receiver's private key.
        val accept = channel.receive()
        if (accept.type != LinkProtocol.R_ACCEPT || accept.length < 1) {
            throw LinkException(LinkException.Reason.PROTOCOL, "Unexpected response to offer")
        }
        when (accept.buffer[accept.offset].toInt() and 0xFF) {
            LinkProtocol.ACCEPT_OK -> Unit
            LinkProtocol.ACCEPT_NO_SPACE -> throw LinkException(LinkException.Reason.INSUFFICIENT_STORAGE, "Receiver lacks storage")
            else -> throw LinkException(LinkException.Reason.DECLINED, "Receiver declined the transfer")
        }

        // Content.
        val totalHint = if (offered.all { it.sizeHint >= 0 }) offered.sumOf { it.sizeHint } else -1L
        var done = 0L
        val results = ArrayList<EntryInfo>(sources.size)
        sources.forEachIndexed { index, source ->
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source.openStream().use { stream ->
                while (true) {
                    val read = fill(stream, channel.plain, 1, LinkProtocol.CHUNK_SIZE)
                    if (read <= 0) break
                    digest.update(channel.plain, 1, read)
                    channel.sendStaged(LinkProtocol.R_DATA, read)
                    size += read
                    done += read
                    listener.onProgress(LinkProgress(done, totalHint, index, sources.size, offered[index].name))
                }
            }
            val sha = digest.digest()
            channel.send(LinkProtocol.R_END, ByteWriter(40).i64(size).bytes(sha).toByteArray())
            results += EntryInfo(source.kind, offered[index].name, offered[index].mimeType, size, sha)
        }
        channel.send(LinkProtocol.R_DONE, ByteArray(0))
        channel.flush()

        val result = channel.receive()
        if (result.type != LinkProtocol.R_RESULT || result.length < 1 || result.buffer[result.offset].toInt() != 0) {
            throw LinkException(LinkException.Reason.INTEGRITY, "Receiver reported a verification failure")
        }
        listener.onProgress(LinkProgress(done, done, sources.size - 1, sources.size, offered.last().name))
        return LinkSendResult(hello.receiverName, results, done, code)
    }

    /** Reads until [length] bytes are available or the stream ends; returns the count read. */
    private fun fill(stream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = stream.read(buffer, offset + total, length - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}

/** Receives a transfer on behalf of the device whose invitation was scanned. */
class LinkReceiver(
    private val keys: EphemeralKeyPair,
    private val receiverName: String,
    private val random: SecureRandom = SecureRandom(),
) {
    fun receive(
        input: InputStream,
        output: OutputStream,
        sink: LinkSink,
        listener: LinkProgressListener = LinkProgressListener { },
        onVerificationCode: (code: String, senderName: String) -> Unit = { _, _ -> },
    ): LinkReceiveResult {
        val hello = try {
            LinkHandshake.readClientHello(input)
        } catch (e: ProtocolException) {
            throw LinkHandshake.handshake("Malformed client hello", e)
        }
        val serverNonce = ByteArray(LinkProtocol.NONCE_BYTES).also(random::nextBytes)
        if (hello.version != LinkProtocol.VERSION) {
            LinkHandshake.writeServerHello(output, LinkProtocol.STATUS_VERSION, serverNonce, receiverName)
            throw LinkException(LinkException.Reason.VERSION, "Unsupported sender version")
        }
        LinkHandshake.writeServerHello(output, LinkProtocol.STATUS_OK, serverNonce, receiverName)

        val shared = try {
            keys.agree(hello.senderKey)
        } catch (e: ProtocolException) {
            throw LinkHandshake.handshake("Invalid sender key", e)
        }
        val (c2s, s2c) = try {
            LinkHandshake.deriveKeys(shared, hello.nonce, serverNonce, keys.publicKey, hello.senderKey)
        } finally {
            shared.fill(0)
        }
        val channel = RecordChannel(input, output, s2c, c2s, LinkProtocol.DIRECTION_S2C, LinkProtocol.DIRECTION_C2S)
        c2s.fill(0)
        s2c.fill(0)
        val code = KeySchedule.shortAuthString(keys.publicKey, hello.senderKey, hello.nonce)
        onVerificationCode(code, hello.senderName)

        // Offer.
        val offerRecord = channel.receive()
        if (offerRecord.type != LinkProtocol.R_OFFER) throw LinkException(LinkException.Reason.PROTOCOL, "Expected an offer")
        val offer = try {
            parseOffer(offerRecord.copyPayload())
        } catch (e: ProtocolException) {
            throw LinkException(LinkException.Reason.PROTOCOL, "Malformed offer", e)
        }
        val decision = sink.onOffer(offer)
        val status = when (decision) {
            OfferDecision.ACCEPT -> LinkProtocol.ACCEPT_OK
            OfferDecision.INSUFFICIENT_STORAGE -> LinkProtocol.ACCEPT_NO_SPACE
            OfferDecision.DECLINE -> LinkProtocol.ACCEPT_DECLINED
        }
        channel.send(LinkProtocol.R_ACCEPT, byteArrayOf(status.toByte()))
        channel.flush()
        when (decision) {
            OfferDecision.ACCEPT -> Unit
            OfferDecision.INSUFFICIENT_STORAGE -> throw LinkException(LinkException.Reason.INSUFFICIENT_STORAGE, "Insufficient storage")
            OfferDecision.DECLINE -> throw LinkException(LinkException.Reason.DECLINED, "Transfer declined")
        }

        // Content.
        val entries = offer.entries
        val totalHint = if (entries.all { it.sizeHint >= 0 }) offer.knownBytes else -1L
        var done = 0L
        val results = ArrayList<EntryInfo>(entries.size)
        entries.forEachIndexed { index, entry ->
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            var endSize = -1L
            var endSha: ByteArray? = null
            sink.openEntry(index, entry).use { out ->
                while (endSha == null) {
                    val record = channel.receive()
                    when (record.type) {
                        LinkProtocol.R_DATA -> {
                            out.write(record.buffer, record.offset, record.length)
                            digest.update(record.buffer, record.offset, record.length)
                            size += record.length
                            done += record.length
                            listener.onProgress(LinkProgress(done, totalHint, index, entries.size, entry.name))
                        }
                        LinkProtocol.R_END -> {
                            if (record.length != 8 + 32) throw LinkException(LinkException.Reason.PROTOCOL, "Malformed entry trailer")
                            val reader = ByteReader(record.buffer, record.offset, record.offset + record.length)
                            endSize = reader.i64()
                            endSha = reader.bytes(32)
                        }
                        else -> throw LinkException(LinkException.Reason.PROTOCOL, "Unexpected record ${record.type}")
                    }
                }
            }
            val sha = digest.digest()
            if (endSize != size || !sha.contentEquals(endSha)) {
                throw LinkException(LinkException.Reason.INTEGRITY, "Verification failed for ${entry.name}")
            }
            val info = EntryInfo(entry.kind, entry.name, entry.mimeType, size, sha)
            results += info
            sink.onEntryFinished(index, info)
        }

        val doneRecord = channel.receive()
        if (doneRecord.type != LinkProtocol.R_DONE) throw LinkException(LinkException.Reason.PROTOCOL, "Expected completion")
        channel.send(LinkProtocol.R_RESULT, ByteWriter(8).u8(0).i32(results.size).toByteArray())
        channel.flush()
        return LinkReceiveResult(offer.senderName, results, done, code)
    }

    private fun parseOffer(payload: ByteArray): LinkOffer {
        val reader = ByteReader(payload)
        val sender = reader.string16(Protocol.MAX_DEVICE_NAME_BYTES)
        val count = reader.i32()
        if (count !in 1..Protocol.MAX_ENTRIES) throw ProtocolException("Invalid entry count")
        val entries = List(count) {
            val kind = EntryKind.fromCode(reader.u8())
            val name = FileNames.sanitize(reader.string16(1024))
            val mime = reader.string16(255)
            val sizeHint = reader.i64()
            OfferedEntry(kind, name, mime, if (sizeHint < 0) -1 else sizeHint)
        }
        return LinkOffer(sender, entries)
    }
}
