package com.madix.beam.protocol.session

import com.madix.beam.protocol.Protocol
import com.madix.beam.protocol.ProtocolException
import com.madix.beam.protocol.container.ContainerManifest
import com.madix.beam.protocol.container.ContainerWriter
import com.madix.beam.protocol.container.EntryKind
import com.madix.beam.protocol.container.PayloadTooLargeException
import com.madix.beam.protocol.container.TransferSource
import com.madix.beam.protocol.crypto.ChunkedAead
import com.madix.beam.protocol.crypto.EphemeralKeyPair
import com.madix.beam.protocol.crypto.KeySchedule
import com.madix.beam.protocol.fountain.BlockSource
import com.madix.beam.protocol.fountain.ByteArrayBlockSource
import com.madix.beam.protocol.fountain.FileBlockSource
import com.madix.beam.protocol.fountain.FountainEncoder
import com.madix.beam.protocol.fountain.RepairPlanner
import com.madix.beam.protocol.fountain.SegmentLayout
import com.madix.beam.protocol.frame.FrameCodec
import com.madix.beam.protocol.frame.PairingRequest
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.frame.TransferHeader
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.SequenceInputStream
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Security choice made by the sender. */
sealed interface SecurityParams {
    val mode: SecurityMode

    data object Open : SecurityParams {
        override val mode = SecurityMode.OPEN
    }

    class Password(password: CharArray) : SecurityParams {
        override val mode = SecurityMode.PASSWORD
        internal val chars: CharArray = password.copyOf()
        fun wipe() = chars.fill('\u0000')
    }

    class Paired(val request: PairingRequest) : SecurityParams {
        override val mode = SecurityMode.PAIRED
    }
}

enum class PrepareStage { PACKAGING, COMPRESSING, SEALING }

fun interface PrepareListener {
    fun onStage(stage: PrepareStage, fraction: Float)
}

/** A sealed transfer ready to be broadcast. Call [close] to wipe its temporary files. */
class PreparedTransfer internal constructor(
    val header: TransferHeader,
    val sealedFile: File,
    val manifest: ContainerManifest,
    val profile: TransferProfile,
    val plainLength: Long,
    val compressed: Boolean,
    /** Six-digit code to compare with the receiver; present in paired mode only. */
    val shortAuthString: String?,
    val peerName: String?,
    private val workDir: File,
) : Closeable {

    val sealedLength: Long get() = header.cipherLength

    /** Creates a new frame scheduler. Small payloads are served from memory. */
    fun openScheduler(): BroadcastScheduler {
        val source: BlockSource = if (sealedLength <= IN_MEMORY_LIMIT) {
            ByteArrayBlockSource(sealedFile.readBytes(), header.blockSize)
        } else {
            FileBlockSource(sealedFile, sealedLength, header.blockSize)
        }
        return BroadcastScheduler(header, source)
    }

    override fun close() {
        workDir.deleteRecursively()
    }

    private companion object {
        const val IN_MEMORY_LIMIT = 48L * 1024 * 1024
    }
}

/** Packages, compresses and seals a set of sources. */
class TransferPreparer(
    private val workRoot: File,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun prepare(
        sources: List<TransferSource>,
        senderName: String,
        security: SecurityParams,
        profile: TransferProfile,
        isCancelled: () -> Boolean = { false },
        listener: PrepareListener = PrepareListener { _, _ -> },
    ): PreparedTransfer {
        val workDir = File(workRoot, "send-" + randomHex(8)).apply { mkdirs() }
        try {
            // 1. Package.
            // A negative fraction means that the amount of work is not known in advance.
            listener.onStage(PrepareStage.PACKAGING, -1f)
            val container = File(workDir, "container.bin")
            val manifest = ContainerWriter.write(
                sources = sources,
                senderName = senderName,
                createdAtMillis = clock(),
                workDir = workDir,
                destination = container,
                isCancelled = isCancelled,
            )
            checkCancelled(isCancelled)

            // 2. Compress when it pays off.
            var body = container
            var method = METHOD_STORE
            if (worthCompressing(manifest)) {
                listener.onStage(PrepareStage.COMPRESSING, 0f)
                val deflated = File(workDir, "container.z")
                deflate(container, deflated, isCancelled) { fraction ->
                    listener.onStage(PrepareStage.COMPRESSING, fraction)
                }
                if (deflated.length() < container.length() * 97 / 100) {
                    body = deflated
                    method = METHOD_DEFLATE
                    container.delete()
                } else {
                    deflated.delete()
                }
            }
            checkCancelled(isCancelled)

            val plainLength = 1L + body.length()
            if (plainLength > Protocol.MAX_PAYLOAD_BYTES) throw PayloadTooLargeException(Protocol.MAX_PAYLOAD_BYTES)

            // 3. Keys and header.
            listener.onStage(PrepareStage.SEALING, 0f)
            val salt = ByteArray(Protocol.SALT_BYTES).also(random::nextBytes)
            val noncePrefix = ByteArray(Protocol.NONCE_PREFIX_BYTES).also(random::nextBytes)
            val sessionId = random.nextInt().let { if (it == 0) 1 else it }
            val chunkSize = Protocol.AEAD_CHUNK_SIZE
            val cipherLength = ChunkedAead.cipherLength(plainLength, chunkSize)
            val totalBlocksLong = (cipherLength + profile.blockSize - 1) / profile.blockSize
            if (totalBlocksLong > Int.MAX_VALUE) throw PayloadTooLargeException(Protocol.MAX_PAYLOAD_BYTES)
            val totalBlocks = totalBlocksLong.toInt()

            var senderPublicKey = ByteArray(0)
            var receiverKeyId = ByteArray(Protocol.KEY_ID_BYTES)
            var iterations = 0
            var sas: String? = null
            var peerName: String? = null
            val key: ByteArray = when (security) {
                is SecurityParams.Open -> KeySchedule.openKey(salt)
                is SecurityParams.Password -> {
                    iterations = Protocol.PBKDF2_ITERATIONS
                    KeySchedule.passwordKey(security.chars, salt, iterations)
                }
                is SecurityParams.Paired -> {
                    val receiverKey = security.request.publicKey
                    if (!EphemeralKeyPair.isValidPublicKey(receiverKey)) {
                        throw ProtocolException("Invalid pairing code")
                    }
                    val own = EphemeralKeyPair.generate(random)
                    senderPublicKey = own.publicKey
                    receiverKeyId = EphemeralKeyPair.keyIdOf(receiverKey)
                    sas = KeySchedule.shortAuthString(receiverKey, senderPublicKey, salt)
                    peerName = security.request.deviceName
                    val shared = own.agree(receiverKey)
                    try {
                        KeySchedule.pairedKey(shared, salt, receiverKey, senderPublicKey)
                    } finally {
                        shared.fill(0)
                    }
                }
            }

            val header = TransferHeader(
                sessionId = sessionId,
                mode = security.mode,
                cipherLength = cipherLength,
                blockSize = profile.blockSize,
                totalBlocks = totalBlocks,
                segmentBlocks = Protocol.MAX_SEGMENT_BLOCKS,
                chunkSize = chunkSize,
                salt = salt,
                noncePrefix = noncePrefix,
                kdfIterations = iterations,
                senderPublicKey = senderPublicKey,
                receiverKeyId = receiverKeyId,
            )

            // 4. Seal.
            val sealed = File(workDir, "payload.sealed")
            try {
                val aead = ChunkedAead(key, noncePrefix, header.associatedData(), chunkSize)
                val plain = SequenceInputStream(ByteArrayInputStream(byteArrayOf(method.toByte())), body.inputStream().buffered(64 * 1024))
                plain.use { input ->
                    BufferedOutputStream(sealed.outputStream(), 64 * 1024).use { output ->
                        val written = aead.encrypt(input, plainLength, output) { done ->
                            listener.onStage(PrepareStage.SEALING, done.toFloat() / plainLength)
                        }
                        check(written == cipherLength) { "Sealed length mismatch" }
                    }
                }
            } finally {
                key.fill(0)
            }
            body.delete()

            return PreparedTransfer(
                header = header,
                sealedFile = sealed,
                manifest = manifest,
                profile = profile,
                plainLength = plainLength,
                compressed = method == METHOD_DEFLATE,
                shortAuthString = sas,
                peerName = peerName,
                workDir = workDir,
            )
        } catch (t: Throwable) {
            workDir.deleteRecursively()
            throw t
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw InterruptedException("Cancelled")
    }

    private fun deflate(input: File, output: File, isCancelled: () -> Boolean, onProgress: (Float) -> Unit) {
        val total = input.length().coerceAtLeast(1)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            input.inputStream().use { source ->
                DeflaterOutputStream(BufferedOutputStream(output.outputStream(), 64 * 1024), deflater, 64 * 1024).use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        checkCancelled(isCancelled)
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        done += read
                        onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            deflater.end()
        }
    }

    private fun randomHex(bytes: Int): String {
        val data = ByteArray(bytes).also(random::nextBytes)
        return data.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        const val METHOD_STORE = 0
        const val METHOD_DEFLATE = 1

        private val INCOMPRESSIBLE_PREFIXES = listOf("image/", "video/", "audio/")
        private val COMPRESSIBLE_MEDIA = setOf("image/svg+xml", "image/bmp", "image/x-ms-bmp", "audio/wav", "audio/x-wav", "image/tiff")
        private val INCOMPRESSIBLE_TYPES = setOf(
            "application/zip",
            "application/gzip",
            "application/x-gzip",
            "application/x-7z-compressed",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-xz",
            "application/x-bzip2",
            "application/zstd",
            "application/vnd.android.package-archive",
            "application/epub+zip",
            "application/java-archive",
        )

        /** True when at least one entry is likely to shrink under DEFLATE. */
        fun worthCompressing(manifest: ContainerManifest): Boolean = manifest.entries.any { entry ->
            if (entry.kind == EntryKind.TEXT) return@any true
            val mime = entry.mimeType.lowercase()
            when {
                mime in COMPRESSIBLE_MEDIA -> true
                INCOMPRESSIBLE_PREFIXES.any { mime.startsWith(it) } -> false
                mime in INCOMPRESSIBLE_TYPES -> false
                mime.startsWith("application/vnd.openxmlformats") -> false
                else -> true
            }
        }
    }
}

/**
 * Produces the infinite, rateless frame stream of a prepared transfer.
 *
 * Every [Protocol.HEADER_INTERVAL]-th frame is a header. The remaining slots cycle through
 * the source blocks, interleaved across segments so that every segment progresses at the same
 * rate, with one repair symbol (round-robin across segments, each with a fresh identifier)
 * every [Protocol.REPAIR_PERIOD] slots. A receiver can therefore start at any moment and
 * finish after collecting slightly more frames than there are blocks.
 */
class BroadcastScheduler(val header: TransferHeader, private val source: BlockSource) : Closeable {
    private val layout = SegmentLayout(header.totalBlocks, header.segmentBlocks)
    private val encoder = FountainEncoder(source, layout, RepairPlanner(header.sessionSeed))
    private val frameLength = FrameCodec.dataFrameLength(header.blockSize)
    private val headerFrame = FrameCodec.encodeHeader(header, frameLength)
    private val buffer = ByteArray(header.blockSize)
    private val largestSegment = layout.size(0)

    private var frameCounter = 0L
    private var dataSlot = 0L
    private var sysLocal = 0
    private var sysSegment = 0
    private var sysEmittedInPass = 0
    private var repairCounter = 0L

    /** Number of complete passes over the source blocks. */
    @Volatile
    var passes: Int = 0
        private set

    val framesEmitted: Long @Synchronized get() = frameCounter

    /** Position inside the current pass, in `[0, 1)`. */
    val passFraction: Float @Synchronized get() = sysEmittedInPass.toFloat() / header.totalBlocks

    @Synchronized
    fun nextFrame(): ByteArray {
        val n = frameCounter++
        if (n % Protocol.HEADER_INTERVAL == 0L) return headerFrame
        val slot = dataSlot++
        return if (slot % Protocol.REPAIR_PERIOD == (Protocol.REPAIR_PERIOD - 1).toLong()) {
            val r = repairCounter++
            val segment = (r % layout.segmentCount).toInt()
            val repairIndex = (r / layout.segmentCount).toInt()
            encoder.repair(segment, repairIndex, buffer)
            FrameCodec.encodeData(header.sessionId, segment, layout.size(segment) + repairIndex, buffer)
        } else {
            // Segment sizes differ by at most one, so at most one position per round is skipped.
            while (sysLocal >= layout.size(sysSegment)) advanceSystematic()
            val segment = sysSegment
            val local = sysLocal
            advanceSystematic()
            sysEmittedInPass++
            if (sysEmittedInPass >= header.totalBlocks) {
                sysEmittedInPass = 0
                passes++
            }
            encoder.systematic(segment, local, buffer)
            FrameCodec.encodeData(header.sessionId, segment, local, buffer)
        }
    }

    private fun advanceSystematic() {
        sysSegment++
        if (sysSegment >= layout.segmentCount) {
            sysSegment = 0
            sysLocal++
            if (sysLocal >= largestSegment) sysLocal = 0
        }
    }

    fun nextQrText(): String = FrameCodec.toQrText(nextFrame())

    override fun close() = source.close()
}
