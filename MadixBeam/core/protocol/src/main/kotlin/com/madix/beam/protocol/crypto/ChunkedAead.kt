package com.madix.beam.protocol.crypto

import com.madix.beam.protocol.Protocol
import com.madix.beam.protocol.ProtocolException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authentication failed: wrong key, wrong password or tampered payload. */
class AuthenticationException(val chunkIndex: Long, cause: Throwable? = null) :
    IOException("Payload authentication failed at chunk $chunkIndex", cause)

/**
 * Chunked AES-256-GCM in the STREAM construction.
 *
 * The plaintext is split into chunks of [chunkSize] bytes. Chunk `i` uses the nonce
 * `prefix(7) || be32(i) || last(1)`, where `last` is 1 only for the final chunk; this
 * prevents reordering, truncation and extension. Every chunk also authenticates the header
 * fields supplied as [associatedData].
 */
class ChunkedAead(
    key: ByteArray,
    noncePrefix: ByteArray,
    associatedData: ByteArray,
    val chunkSize: Int = Protocol.AEAD_CHUNK_SIZE,
) {
    private val keySpec = SecretKeySpec(key.copyOf(), "AES")
    private val prefix = noncePrefix.copyOf()
    private val aad = associatedData.copyOf()

    init {
        require(key.size == Protocol.KEY_BYTES) { "AES-256 key required" }
        require(prefix.size == Protocol.NONCE_PREFIX_BYTES) { "Invalid nonce prefix" }
        require(chunkSize > 0)
    }

    private fun nonce(index: Long, last: Boolean): ByteArray {
        require(index in 0..0xFFFFFFFFL)
        val nonce = ByteArray(12)
        System.arraycopy(prefix, 0, nonce, 0, prefix.size)
        nonce[7] = (index ushr 24).toByte()
        nonce[8] = (index ushr 16).toByte()
        nonce[9] = (index ushr 8).toByte()
        nonce[10] = index.toByte()
        nonce[11] = if (last) 1 else 0
        return nonce
    }

    private fun cipher(mode: Int, index: Long, last: Boolean): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, keySpec, GCMParameterSpec(Protocol.GCM_TAG_BYTES * 8, nonce(index, last)))
        cipher.updateAAD(aad)
        return cipher
    }

    /**
     * Encrypts exactly [plainLength] bytes from [input] into [output].
     * @return the number of ciphertext bytes written.
     */
    fun encrypt(input: InputStream, plainLength: Long, output: OutputStream, onProgress: (Long) -> Unit = {}): Long {
        require(plainLength > 0)
        val chunks = chunkCount(plainLength, chunkSize)
        val plain = ByteArray(chunkSize)
        var remaining = plainLength
        var written = 0L
        for (index in 0 until chunks) {
            val length = minOf(chunkSize.toLong(), remaining).toInt()
            readFully(input, plain, length)
            val sealed = cipher(Cipher.ENCRYPT_MODE, index, index == chunks - 1).doFinal(plain, 0, length)
            output.write(sealed)
            written += sealed.size
            remaining -= length
            onProgress(plainLength - remaining)
        }
        plain.fill(0)
        return written
    }

    /** Returns a stream yielding the authenticated plaintext of [cipherInput]. */
    fun decrypt(cipherInput: InputStream, cipherLength: Long): InputStream =
        DecryptingStream(cipherInput, cipherLength)

    private inner class DecryptingStream(
        private val source: InputStream,
        cipherLength: Long,
    ) : InputStream() {
        private val sealedChunk = chunkSize + Protocol.GCM_TAG_BYTES
        private val chunks: Long = plainChunkCountForCipher(cipherLength, chunkSize)
        private var remainingCipher = cipherLength
        private var index = 0L
        private var buffer = ByteArray(0)
        private var position = 0

        private fun fill(): Boolean {
            while (position >= buffer.size) {
                if (index >= chunks) return false
                val length = minOf(sealedChunk.toLong(), remainingCipher).toInt()
                val sealed = ByteArray(length)
                readFully(source, sealed, length)
                buffer = try {
                    cipher(Cipher.DECRYPT_MODE, index, index == chunks - 1).doFinal(sealed)
                } catch (e: AEADBadTagException) {
                    throw AuthenticationException(index, e)
                } catch (e: java.security.GeneralSecurityException) {
                    throw AuthenticationException(index, e)
                }
                position = 0
                remainingCipher -= length
                index++
            }
            return true
        }

        override fun read(): Int {
            if (!fill()) return -1
            return buffer[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val count = minOf(len, buffer.size - position)
            System.arraycopy(buffer, position, b, off, count)
            position += count
            return count
        }

        override fun close() = source.close()
    }

    companion object {
        fun chunkCount(plainLength: Long, chunkSize: Int): Long =
            maxOf(1L, (plainLength + chunkSize - 1) / chunkSize)

        fun cipherLength(plainLength: Long, chunkSize: Int = Protocol.AEAD_CHUNK_SIZE): Long =
            plainLength + chunkCount(plainLength, chunkSize) * Protocol.GCM_TAG_BYTES

        /** Inverse of [cipherLength]: number of chunks in a ciphertext of [cipherLength] bytes. */
        fun plainChunkCountForCipher(cipherLength: Long, chunkSize: Int): Long {
            val sealed = chunkSize.toLong() + Protocol.GCM_TAG_BYTES
            val chunks = (cipherLength + sealed - 1) / sealed
            val last = cipherLength - (chunks - 1) * sealed
            if (chunks < 1 || last <= Protocol.GCM_TAG_BYTES) throw ProtocolException("Invalid ciphertext length")
            return chunks
        }

        fun plainLength(cipherLength: Long, chunkSize: Int): Long =
            cipherLength - plainChunkCountForCipher(cipherLength, chunkSize) * Protocol.GCM_TAG_BYTES

        internal fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
            var offset = 0
            while (offset < length) {
                val read = input.read(buffer, offset, length - offset)
                if (read < 0) throw IOException("Unexpected end of stream")
                offset += read
            }
        }
    }
}
