package com.madix.beam.protocol.codec

import com.madix.beam.protocol.ProtocolException

/**
 * Base45 codec as specified by RFC 9285.
 *
 * Base45 output consists solely of characters of the QR alphanumeric set, which lets a QR
 * encoder use alphanumeric mode (5.5 bits per character). Two input bytes become three
 * characters, giving roughly 97% of the efficiency of raw byte mode while remaining
 * immune to the character-set guessing performed by barcode decoders.
 */
object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encodedLength(byteCount: Int): Int = (byteCount / 2) * 3 + (byteCount % 2) * 2

    fun encode(data: ByteArray): String {
        val out = StringBuilder(encodedLength(data.size))
        appendEncoded(out, data)
        return out.toString()
    }

    fun appendEncoded(out: StringBuilder, data: ByteArray) {
        var i = 0
        val size = data.size
        while (i + 1 < size) {
            val n = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            out.append(ALPHABET[n % 45])
            out.append(ALPHABET[(n / 45) % 45])
            out.append(ALPHABET[n / 2025])
            i += 2
        }
        if (i < size) {
            val n = data[i].toInt() and 0xFF
            out.append(ALPHABET[n % 45])
            out.append(ALPHABET[n / 45])
        }
    }

    /**
     * Decodes [text] starting at [start].
     * @throws ProtocolException when the input is not valid Base45.
     */
    fun decode(text: CharSequence, start: Int = 0): ByteArray {
        val length = text.length - start
        if (length < 0) throw ProtocolException("Invalid Base45 offset")
        val remainder = length % 3
        if (remainder == 1) throw ProtocolException("Invalid Base45 length")
        val out = ByteArray((length / 3) * 2 + if (remainder == 2) 1 else 0)
        var o = 0
        var i = start
        val fullEnd = start + (length - remainder)
        while (i < fullEnd) {
            val n = value(text[i]) + value(text[i + 1]) * 45 + value(text[i + 2]) * 2025
            if (n > 0xFFFF) throw ProtocolException("Invalid Base45 triplet")
            out[o++] = (n ushr 8).toByte()
            out[o++] = n.toByte()
            i += 3
        }
        if (remainder == 2) {
            val n = value(text[i]) + value(text[i + 1]) * 45
            if (n > 0xFF) throw ProtocolException("Invalid Base45 tail")
            out[o] = n.toByte()
        }
        return out
    }

    private fun value(c: Char): Int {
        val code = c.code
        val v = if (code < 128) DECODE_TABLE[code] else -1
        if (v < 0) throw ProtocolException("Invalid Base45 character")
        return v
    }
}
