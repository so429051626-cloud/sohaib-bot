package com.madix.beam.protocol.container

import com.madix.beam.protocol.Protocol
import com.madix.beam.protocol.ProtocolException
import com.madix.beam.protocol.codec.ByteWriter
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Kind of an entry inside a transfer. */
enum class EntryKind(val code: Int) {
    FILE(0),
    TEXT(1);

    companion object {
        fun fromCode(code: Int): EntryKind =
            entries.firstOrNull { it.code == code } ?: throw ProtocolException("Unknown entry kind")
    }
}

/** Something the user wants to send. Implementations must allow [openStream] to be called once. */
interface TransferSource {
    val displayName: String
    val mimeType: String
    val kind: EntryKind
    fun openStream(): InputStream
}

class TextSource(private val text: String, override val displayName: String = "message.txt") : TransferSource {
    override val mimeType: String = "text/plain"
    override val kind: EntryKind = EntryKind.TEXT
    override fun openStream(): InputStream = text.toByteArray(Charsets.UTF_8).inputStream()
}

class FileSource(
    private val file: File,
    override val mimeType: String = "application/octet-stream",
    override val displayName: String = file.name,
) : TransferSource {
    override val kind: EntryKind = EntryKind.FILE
    override fun openStream(): InputStream = file.inputStream()
}

/** Metadata of one entry, as described in the container manifest. */
class EntryInfo(
    val kind: EntryKind,
    val name: String,
    val mimeType: String,
    val size: Long,
    sha256: ByteArray,
) {
    private val digest = sha256.copyOf()
    val sha256: ByteArray get() = digest.copyOf()
    val sha256Hex: String get() = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Manifest of a container. */
class ContainerManifest(
    val senderName: String,
    val createdAtMillis: Long,
    val entries: List<EntryInfo>,
) {
    val totalBytes: Long get() = entries.sumOf { it.size }
}

/**
 * Container layout (all integers big-endian):
 * ```
 * magic "MDXC" | version u8 | senderName (u16 length + UTF-8) | createdAt i64 | count i32
 * count × { kind u8 | name (u16 + UTF-8) | mime (u16 + UTF-8) | size i64 | sha256[32] }
 * entry data, concatenated in manifest order
 * ```
 */
object ContainerFormat {
    val MAGIC = byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'X'.code.toByte(), 'C'.code.toByte())
    const val VERSION = 1
    const val MAX_NAME_BYTES = 1024
    const val MAX_MIME_BYTES = 255
    const val MAX_SENDER_BYTES = 256
}

/** Callback used to report progress in bytes. */
fun interface ProgressListener {
    fun onProgress(done: Long, total: Long)
}

object ContainerWriter {

    /**
     * Reads every source exactly once, computing digests, and writes the container to
     * [destination]. [workDir] receives a temporary blob file which is removed afterwards.
     */
    fun write(
        sources: List<TransferSource>,
        senderName: String,
        createdAtMillis: Long,
        workDir: File,
        destination: File,
        maxBytes: Long = Protocol.MAX_PAYLOAD_BYTES,
        isCancelled: () -> Boolean = { false },
        progress: ProgressListener = ProgressListener { _, _ -> },
    ): ContainerManifest {
        require(sources.isNotEmpty()) { "Nothing to send" }
        require(sources.size <= Protocol.MAX_ENTRIES) { "Too many items" }
        workDir.mkdirs()
        val blob = File.createTempFile("blob", ".tmp", workDir)
        try {
            val entries = ArrayList<EntryInfo>(sources.size)
            var total = 0L
            BufferedOutputStream(blob.outputStream(), 64 * 1024).use { out ->
                val buffer = ByteArray(64 * 1024)
                for (source in sources) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    source.openStream().use { input ->
                        while (true) {
                            if (isCancelled()) throw InterruptedException("Cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            size += read
                            total += read
                            if (total > maxBytes) throw PayloadTooLargeException(maxBytes)
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                            progress.onProgress(total, -1)
                        }
                    }
                    entries += EntryInfo(
                        kind = source.kind,
                        name = FileNames.sanitize(source.displayName),
                        mimeType = source.mimeType.take(ContainerFormat.MAX_MIME_BYTES),
                        size = size,
                        sha256 = digest.digest(),
                    )
                }
            }
            val manifest = ContainerManifest(senderName, createdAtMillis, entries)
            BufferedOutputStream(destination.outputStream(), 64 * 1024).use { out ->
                out.write(encodeManifest(manifest))
                blob.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
            return manifest
        } finally {
            blob.delete()
        }
    }

    fun encodeManifest(manifest: ContainerManifest): ByteArray {
        val writer = ByteWriter(256 + manifest.entries.size * 96)
            .bytes(ContainerFormat.MAGIC)
            .u8(ContainerFormat.VERSION)
            .string16(manifest.senderName, ContainerFormat.MAX_SENDER_BYTES)
            .i64(manifest.createdAtMillis)
            .i32(manifest.entries.size)
        for (entry in manifest.entries) {
            writer.u8(entry.kind.code)
                .string16(entry.name, ContainerFormat.MAX_NAME_BYTES)
                .string16(entry.mimeType, ContainerFormat.MAX_MIME_BYTES)
                .i64(entry.size)
                .bytes(entry.sha256)
        }
        return writer.toByteArray()
    }
}

class PayloadTooLargeException(val limit: Long) : IOException("Payload exceeds $limit bytes")

/** Integrity violation detected while unpacking. */
class ContainerIntegrityException(message: String) : ProtocolException(message)

/** Destination for unpacked entries. */
interface ContainerSink {
    /** Called once the manifest is known, before any entry data. May reject the transfer. */
    fun onManifest(manifest: ContainerManifest) {}

    fun openEntry(index: Int, entry: EntryInfo): OutputStream

    fun onEntryFinished(index: Int, entry: EntryInfo) {}
}

object ContainerReader {

    fun readManifest(input: DataInputStream): ContainerManifest {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (!magic.contentEquals(ContainerFormat.MAGIC)) throw ProtocolException("Not a Madix container")
        val version = input.readUnsignedByte()
        if (version != ContainerFormat.VERSION) throw ProtocolException("Unsupported container version")
        val sender = readString(input, ContainerFormat.MAX_SENDER_BYTES)
        val createdAt = input.readLong()
        val count = input.readInt()
        if (count !in 1..Protocol.MAX_ENTRIES) throw ProtocolException("Invalid entry count")
        val entries = ArrayList<EntryInfo>(count)
        var total = 0L
        repeat(count) {
            val kind = EntryKind.fromCode(input.readUnsignedByte())
            val name = FileNames.sanitize(readString(input, ContainerFormat.MAX_NAME_BYTES))
            val mime = readString(input, ContainerFormat.MAX_MIME_BYTES)
            val size = input.readLong()
            if (size < 0) throw ProtocolException("Invalid entry size")
            total += size
            if (total > Protocol.MAX_UNPACKED_BYTES) throw ProtocolException("Container too large")
            val sha = ByteArray(32)
            input.readFully(sha)
            entries += EntryInfo(kind, name, mime, size, sha)
        }
        return ContainerManifest(sender, createdAt, entries)
    }

    /**
     * Unpacks [input] into [sink], verifying the SHA-256 digest of every entry and that no
     * trailing data follows the last entry.
     */
    fun read(input: InputStream, sink: ContainerSink, progress: ProgressListener = ProgressListener { _, _ -> }): ContainerManifest {
        val data = DataInputStream(input)
        val manifest = try {
            readManifest(data)
        } catch (e: EOFException) {
            throw ProtocolException("Truncated manifest", e)
        }
        sink.onManifest(manifest)
        val total = manifest.totalBytes
        var done = 0L
        val buffer = ByteArray(64 * 1024)
        manifest.entries.forEachIndexed { index, entry ->
            val digest = MessageDigest.getInstance("SHA-256")
            sink.openEntry(index, entry).use { out ->
                var remaining = entry.size
                while (remaining > 0) {
                    val read = data.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw ContainerIntegrityException("Entry ${entry.name} is truncated")
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    remaining -= read
                    done += read
                    progress.onProgress(done, total)
                }
            }
            if (!digest.digest().contentEquals(entry.sha256)) {
                throw ContainerIntegrityException("Checksum mismatch for ${entry.name}")
            }
            sink.onEntryFinished(index, entry)
        }
        if (data.read() != -1) throw ContainerIntegrityException("Unexpected trailing data")
        return manifest
    }

    private fun readString(input: DataInputStream, maxBytes: Int): String {
        val length = input.readUnsignedShort()
        if (length > maxBytes) throw ProtocolException("String too long")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}

/** Defensive handling of names received from another device. */
object FileNames {
    private const val MAX_LENGTH = 120
    private val FORBIDDEN = charArrayOf('<', '>', ':', '"', '|', '?', '*', '/', '\\')

    /** Characters that can visually disguise an extension (bidi controls, zero-width marks). */
    private fun isInvisibleControl(c: Char): Boolean =
        c in '​'..'‏' || c in '‪'..'‮' || c in '⁦'..'⁩' || c == '﻿'

    fun sanitize(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (c in base) {
                when {
                    c.code < 0x20 || c.code == 0x7F -> Unit
                    isInvisibleControl(c) -> Unit
                    c in FORBIDDEN -> append('_')
                    else -> append(c)
                }
            }
        }.trim().trimStart('.').trimEnd('.', ' ')
        val name = if (cleaned.isEmpty()) "file" else cleaned
        if (name.length <= MAX_LENGTH) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        return name.take(MAX_LENGTH - extension.length).trimEnd() + extension
    }

    /** Returns a file in [directory] named [name] that does not exist yet. */
    fun unique(directory: File, name: String): File {
        val safe = sanitize(name)
        var candidate = File(directory, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val extension = if (dot > 0) safe.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$stem ($counter)$extension")
            counter++
        }
        return candidate
    }
}
