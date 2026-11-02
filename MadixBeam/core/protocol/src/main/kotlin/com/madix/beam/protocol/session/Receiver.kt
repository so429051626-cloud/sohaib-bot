package com.madix.beam.protocol.session

import com.madix.beam.protocol.ProtocolException
import com.madix.beam.protocol.container.ContainerManifest
import com.madix.beam.protocol.container.ContainerReader
import com.madix.beam.protocol.container.ContainerSink
import com.madix.beam.protocol.container.ProgressListener
import com.madix.beam.protocol.crypto.AuthenticationException
import com.madix.beam.protocol.crypto.ChunkedAead
import com.madix.beam.protocol.crypto.EphemeralKeyPair
import com.madix.beam.protocol.crypto.KeySchedule
import com.madix.beam.protocol.fountain.BlockStore
import com.madix.beam.protocol.fountain.FountainDecoder
import com.madix.beam.protocol.frame.DataSymbol
import com.madix.beam.protocol.frame.Frame
import com.madix.beam.protocol.frame.FrameCodec
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.frame.TransferHeader
import java.io.Closeable
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Immutable view of the reception state, suitable for UI rendering. */
data class ReceptionSnapshot(
    val header: TransferHeader? = null,
    val recoveredBlocks: Int = 0,
    val pendingEquations: Int = 0,
    val totalBlocks: Int = 0,
    val framesDecoded: Long = 0,
    val framesUseful: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val elapsedMillis: Long = 0,
    val complete: Boolean = false,
    val wrongRecipientSeen: Boolean = false,
) {
    /** Information collected so far, in `[0, 1]`. Reaches 1 when decoding completes. */
    val progress: Float
        get() = when {
            complete -> 1f
            totalBlocks == 0 -> 0f
            else -> ((recoveredBlocks + pendingEquations).toFloat() / totalBlocks).coerceIn(0f, 0.999f)
        }

    /** Estimated remaining seconds, or null while unknown. */
    val etaSeconds: Long?
        get() {
            val h = header ?: return null
            if (complete) return 0
            if (bytesPerSecond < 1.0) return null
            val remaining = (totalBlocks - recoveredBlocks - pendingEquations).coerceAtLeast(0).toLong() * h.blockSize
            return (remaining / bytesPerSecond).toLong() + 1
        }
}

/**
 * Consumes QR texts and reconstructs the sealed payload of one transfer.
 *
 * The first acceptable header locks the engine onto its session; frames of other sessions are
 * ignored. Data frames seen before the header are buffered. In paired mode a header is only
 * accepted when it is addressed to [pairingKeys].
 *
 * Instances are not thread-safe; callers must serialise access.
 */
class ReceiverEngine(
    private val pairingKeys: EphemeralKeyPair?,
    private val storeFactory: (TransferHeader) -> BlockStore,
    private val clock: () -> Long = { System.nanoTime() },
) : Closeable {

    /** Outcome of feeding one QR text. */
    sealed interface Event {
        /** Duplicate or non-informative frame. */
        data object Ignored : Event

        /** Text that does not belong to Madix Beam. */
        data class Foreign(val text: String) : Event

        /** Text that carries the Madix prefix but failed validation. */
        data object Corrupted : Event

        /** A pairing code was scanned instead of a transfer. */
        data object PairingCode : Event

        /** A paired transfer addressed to another device was detected. */
        data object WrongRecipient : Event

        /** The engine locked onto a transfer. */
        data class Locked(val header: TransferHeader) : Event

        /** New information was added. */
        data object Progress : Event

        /** All blocks have been reconstructed. */
        data object Completed : Event
    }

    private val ownKeyId: ByteArray? = pairingKeys?.keyId
    private val buffered = ArrayDeque<DataSymbol>()

    var header: TransferHeader? = null
        private set

    private var decoder: FountainDecoder? = null
    private var store: BlockStore? = null
    private var framesDecoded = 0L
    private var framesUseful = 0L
    private var lockedAt = 0L
    private var wrongRecipientSeen = false

    val isComplete: Boolean get() = decoder?.isComplete == true

    fun ingest(text: String): Event {
        val frame = try {
            FrameCodec.parseQrText(text) ?: return Event.Foreign(text)
        } catch (e: ProtocolException) {
            return Event.Corrupted
        }
        framesDecoded++
        return when (frame) {
            is Frame.Pairing, is Frame.Invite -> Event.PairingCode
            is Frame.Header -> onHeader(frame.header)
            is Frame.Data -> onData(frame.symbol)
        }
    }

    private fun onHeader(candidate: TransferHeader): Event {
        val current = header
        if (current != null) return Event.Ignored
        if (candidate.mode == SecurityMode.PAIRED) {
            val keyId = ownKeyId
            if (keyId == null || !keyId.contentEquals(candidate.receiverKeyId)) {
                wrongRecipientSeen = true
                return Event.WrongRecipient
            }
        }
        val newStore = storeFactory(candidate)
        store = newStore
        decoder = FountainDecoder(
            totalBlocks = candidate.totalBlocks,
            blockSize = candidate.blockSize,
            maxSegmentBlocks = candidate.segmentBlocks,
            sessionSeed = candidate.sessionSeed,
            store = newStore,
        )
        header = candidate
        lockedAt = clock()
        val replay = buffered.filter { it.sessionId == candidate.sessionId }
        buffered.clear()
        for (symbol in replay) feed(symbol)
        return if (isComplete) Event.Completed else Event.Locked(candidate)
    }

    private fun onData(symbol: DataSymbol): Event {
        val current = header
        if (current == null) {
            if (buffered.size >= MAX_BUFFERED) buffered.removeFirst()
            buffered.addLast(symbol)
            return Event.Ignored
        }
        if (symbol.sessionId != current.sessionId) return Event.Ignored
        if (isComplete) return Event.Ignored
        return if (feed(symbol)) {
            if (isComplete) Event.Completed else Event.Progress
        } else {
            Event.Ignored
        }
    }

    private fun feed(symbol: DataSymbol): Boolean {
        val d = decoder ?: return false
        return try {
            val useful = d.accept(symbol.segment, symbol.symbolId, symbol.payload)
            if (useful) framesUseful++
            useful
        } catch (e: ProtocolException) {
            false
        }
    }

    fun snapshot(): ReceptionSnapshot {
        val h = header
        val d = decoder
        val elapsedNanos = if (h != null) clock() - lockedAt else 0L
        val collected = if (d != null) (d.recoveredBlocks + d.pendingEquations).toLong() else 0L
        val seconds = elapsedNanos / 1e9
        val rate = if (h != null && seconds > 0.5) collected * h.blockSize / seconds else 0.0
        return ReceptionSnapshot(
            header = h,
            recoveredBlocks = d?.recoveredBlocks ?: 0,
            pendingEquations = d?.pendingEquations ?: 0,
            totalBlocks = h?.totalBlocks ?: 0,
            framesDecoded = framesDecoded,
            framesUseful = framesUseful,
            bytesPerSecond = rate,
            elapsedMillis = elapsedNanos / 1_000_000,
            complete = isComplete,
            wrongRecipientSeen = wrongRecipientSeen,
        )
    }

    /** Short authentication string to compare with the sender (paired mode only). */
    fun shortAuthString(): String? {
        val h = header ?: return null
        val keys = pairingKeys ?: return null
        if (h.mode != SecurityMode.PAIRED) return null
        return KeySchedule.shortAuthString(keys.publicKey, h.senderPublicKey, h.salt)
    }

    /** Returns an opener for the completed payload. */
    fun opener(): PayloadOpener {
        check(isComplete) { "Transfer not complete" }
        return PayloadOpener(header!!, store!!, pairingKeys)
    }

    override fun close() {
        store?.close()
    }

    private companion object {
        const val MAX_BUFFERED = 512
    }
}

/** Wrong password or wrong key: the very first chunk failed authentication. */
class WrongKeyException(cause: Throwable) : Exception("The key does not match this transfer", cause)

/** Decrypts, decompresses and unpacks a fully reconstructed payload. */
class PayloadOpener(
    val header: TransferHeader,
    private val store: BlockStore,
    private val pairingKeys: EphemeralKeyPair?,
) {
    val requiresPassword: Boolean get() = header.mode == SecurityMode.PASSWORD

    /** Derives the payload key. [password] is required in password mode only. */
    fun deriveKey(password: CharArray? = null): ByteArray = when (header.mode) {
        SecurityMode.OPEN -> KeySchedule.openKey(header.salt)
        SecurityMode.PASSWORD -> {
            requireNotNull(password) { "Password required" }
            KeySchedule.passwordKey(password, header.salt, header.kdfIterations)
        }
        SecurityMode.PAIRED -> {
            val keys = requireNotNull(pairingKeys) { "Pairing keys required" }
            val shared = keys.agree(header.senderPublicKey)
            try {
                KeySchedule.pairedKey(shared, header.salt, keys.publicKey, header.senderPublicKey)
            } finally {
                shared.fill(0)
            }
        }
    }

    /** Quickly checks [key] by authenticating the first chunk only. */
    fun verifyKey(key: ByteArray): Boolean {
        val aead = ChunkedAead(key, header.noncePrefix, header.associatedData(), header.chunkSize)
        return try {
            aead.decrypt(store.openStream(header.cipherLength), header.cipherLength).use { it.read() >= 0 }
        } catch (e: AuthenticationException) {
            false
        }
    }

    /**
     * Opens the payload with [key] and writes every entry to [sink].
     * @throws WrongKeyException when [key] does not match.
     * @throws AuthenticationException when a later chunk was tampered with.
     */
    fun open(key: ByteArray, sink: ContainerSink, progress: ProgressListener = ProgressListener { _, _ -> }): ContainerManifest {
        val aead = ChunkedAead(key, header.noncePrefix, header.associatedData(), header.chunkSize)
        val plain = aead.decrypt(store.openStream(header.cipherLength), header.cipherLength)
        plain.use { input ->
            val method = try {
                input.read()
            } catch (e: AuthenticationException) {
                if (e.chunkIndex == 0L) throw WrongKeyException(e)
                throw e
            }
            val body: InputStream
            var inflater: Inflater? = null
            when (method) {
                TransferPreparer.METHOD_STORE -> body = input
                TransferPreparer.METHOD_DEFLATE -> {
                    inflater = Inflater()
                    body = InflaterInputStream(NonClosingStream(input), inflater, 64 * 1024)
                }
                else -> throw ProtocolException("Unknown compression method")
            }
            try {
                val manifest = ContainerReader.read(body, sink, progress)
                // Authenticate every remaining chunk, including the final one.
                val drain = ByteArray(16 * 1024)
                while (input.read(drain) >= 0) Unit
                return manifest
            } finally {
                inflater?.end()
            }
        }
    }

    private class NonClosingStream(private val source: InputStream) : InputStream() {
        override fun read(): Int = source.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len)
        override fun close() = Unit
    }
}
