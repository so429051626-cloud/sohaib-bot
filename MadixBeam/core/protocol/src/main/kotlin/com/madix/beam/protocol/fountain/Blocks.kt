package com.madix.beam.protocol.fountain

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** Read access to the sealed payload, split into fixed-size blocks (last block zero padded). */
interface BlockSource : Closeable {
    val blockSize: Int
    val totalBlocks: Int
    fun readBlock(index: Int, destination: ByteArray)
}

class ByteArrayBlockSource(private val data: ByteArray, override val blockSize: Int) : BlockSource {
    override val totalBlocks: Int = ((data.size.toLong() + blockSize - 1) / blockSize).toInt()

    override fun readBlock(index: Int, destination: ByteArray) {
        require(index in 0 until totalBlocks)
        val start = index.toLong() * blockSize
        val available = minOf(blockSize.toLong(), data.size - start).toInt()
        System.arraycopy(data, start.toInt(), destination, 0, available)
        if (available < blockSize) java.util.Arrays.fill(destination, available, blockSize, 0)
    }

    override fun close() = Unit
}

class FileBlockSource(file: File, private val length: Long, override val blockSize: Int) : BlockSource {
    private val raf = RandomAccessFile(file, "r")
    override val totalBlocks: Int = ((length + blockSize - 1) / blockSize).toInt()

    @Synchronized
    override fun readBlock(index: Int, destination: ByteArray) {
        require(index in 0 until totalBlocks)
        val start = index.toLong() * blockSize
        val available = minOf(blockSize.toLong(), length - start).toInt()
        raf.seek(start)
        raf.readFully(destination, 0, available)
        if (available < blockSize) java.util.Arrays.fill(destination, available, blockSize, 0)
    }

    @Synchronized
    override fun close() = raf.close()
}

/** Write-once storage of recovered blocks on the receiving side. */
interface BlockStore : Closeable {
    val blockSize: Int
    val totalBlocks: Int
    fun write(index: Int, data: ByteArray)
    fun read(index: Int, destination: ByteArray)

    /** Streams the first [length] bytes of the reconstructed payload. */
    fun openStream(length: Long): InputStream
}

class MemoryBlockStore(override val totalBlocks: Int, override val blockSize: Int) : BlockStore {
    private val data: ByteArray

    init {
        val bytes = totalBlocks.toLong() * blockSize
        require(bytes <= Int.MAX_VALUE - 64) { "Payload too large for memory store" }
        data = ByteArray(bytes.toInt())
    }

    override fun write(index: Int, data: ByteArray) {
        System.arraycopy(data, 0, this.data, index * blockSize, blockSize)
    }

    override fun read(index: Int, destination: ByteArray) {
        System.arraycopy(data, index * blockSize, destination, 0, blockSize)
    }

    override fun openStream(length: Long): InputStream {
        require(length <= data.size)
        return data.inputStream(0, length.toInt())
    }

    override fun close() = Unit
}

class FileBlockStore(
    private val file: File,
    override val totalBlocks: Int,
    override val blockSize: Int,
) : BlockStore {
    private val raf = RandomAccessFile(file, "rw").also {
        it.setLength(totalBlocks.toLong() * blockSize)
    }

    @Synchronized
    override fun write(index: Int, data: ByteArray) {
        raf.seek(index.toLong() * blockSize)
        raf.write(data, 0, blockSize)
    }

    @Synchronized
    override fun read(index: Int, destination: ByteArray) {
        raf.seek(index.toLong() * blockSize)
        raf.readFully(destination, 0, blockSize)
    }

    override fun openStream(length: Long): InputStream {
        if (length > file.length()) throw IOException("Store shorter than payload")
        return BoundedInputStream(file.inputStream().buffered(64 * 1024), length)
    }

    @Synchronized
    override fun close() = raf.close()
}

/** Input stream limited to the first [limit] bytes of [source]. */
internal class BoundedInputStream(private val source: InputStream, limit: Long) : InputStream() {
    private var remaining = limit

    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = source.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining <= 0) return -1
        val count = source.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }

    override fun close() = source.close()
}
