package com.madix.beam.protocol.codec

import com.madix.beam.protocol.ProtocolException

/** Growable big-endian byte writer. */
class ByteWriter(initialCapacity: Int = 256) {
    private var buffer = ByteArray(maxOf(16, initialCapacity))

    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed > buffer.size) {
            var capacity = buffer.size * 2
            while (capacity < needed) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
    }

    fun u8(value: Int): ByteWriter {
        ensure(1)
        buffer[size++] = value.toByte()
        return this
    }

    fun u16(value: Int): ByteWriter {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        ensure(2)
        buffer[size++] = (value ushr 8).toByte()
        buffer[size++] = value.toByte()
        return this
    }

    fun i32(value: Int): ByteWriter {
        ensure(4)
        buffer[size++] = (value ushr 24).toByte()
        buffer[size++] = (value ushr 16).toByte()
        buffer[size++] = (value ushr 8).toByte()
        buffer[size++] = value.toByte()
        return this
    }

    fun i64(value: Long): ByteWriter {
        ensure(8)
        for (shift in 56 downTo 0 step 8) {
            buffer[size++] = (value ushr shift).toByte()
        }
        return this
    }

    fun bytes(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteWriter {
        ensure(length)
        System.arraycopy(data, offset, buffer, size, length)
        size += length
        return this
    }

    fun zeros(count: Int): ByteWriter {
        require(count >= 0)
        ensure(count)
        java.util.Arrays.fill(buffer, size, size + count, 0)
        size += count
        return this
    }

    /** Writes a UTF-8 string prefixed by its byte length as u8. */
    fun shortString(value: String, maxBytes: Int): ByteWriter {
        val encoded = Utf8.truncate(value, minOf(maxBytes, 0xFF))
        u8(encoded.size)
        return bytes(encoded)
    }

    /** Writes a UTF-8 string prefixed by its byte length as u16. */
    fun string16(value: String, maxBytes: Int): ByteWriter {
        val encoded = Utf8.truncate(value, minOf(maxBytes, 0xFFFF))
        u16(encoded.size)
        return bytes(encoded)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** Bounds-checked big-endian byte reader. */
class ByteReader(
    private val buffer: ByteArray,
    private var position: Int = 0,
    private val limit: Int = buffer.size,
) {
    init {
        if (position < 0 || limit > buffer.size || position > limit) {
            throw ProtocolException("Invalid reader bounds")
        }
    }

    val remaining: Int get() = limit - position
    val offset: Int get() = position

    private fun require(count: Int) {
        if (count < 0 || remaining < count) throw ProtocolException("Unexpected end of frame")
    }

    fun u8(): Int {
        require(1)
        return buffer[position++].toInt() and 0xFF
    }

    fun u16(): Int {
        require(2)
        val v = ((buffer[position].toInt() and 0xFF) shl 8) or (buffer[position + 1].toInt() and 0xFF)
        position += 2
        return v
    }

    fun i32(): Int {
        require(4)
        val v = ((buffer[position].toInt() and 0xFF) shl 24) or
            ((buffer[position + 1].toInt() and 0xFF) shl 16) or
            ((buffer[position + 2].toInt() and 0xFF) shl 8) or
            (buffer[position + 3].toInt() and 0xFF)
        position += 4
        return v
    }

    fun i64(): Long {
        require(8)
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (buffer[position + i].toLong() and 0xFF)
        }
        position += 8
        return v
    }

    fun bytes(count: Int): ByteArray {
        require(count)
        val out = buffer.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun skip(count: Int) {
        require(count)
        position += count
    }

    fun shortString(maxBytes: Int): String {
        val length = u8()
        if (length > maxBytes) throw ProtocolException("String too long")
        return Utf8.decode(bytes(length))
    }

    fun string16(maxBytes: Int): String {
        val length = u16()
        if (length > maxBytes) throw ProtocolException("String too long")
        return Utf8.decode(bytes(length))
    }
}

internal object Utf8 {
    /** Encodes [value] as UTF-8, truncated on a code point boundary to at most [maxBytes]. */
    fun truncate(value: String, maxBytes: Int): ByteArray {
        val full = value.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full
        var end = maxBytes
        // Step back over continuation bytes (10xxxxxx) so that no code point is split.
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--
        return full.copyOf(end)
    }

    fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
}
