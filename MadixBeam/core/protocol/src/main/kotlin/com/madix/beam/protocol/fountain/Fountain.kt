package com.madix.beam.protocol.fountain

import com.madix.beam.protocol.ProtocolException

/**
 * Systematic fountain encoder.
 *
 * Symbol identifiers `0 until segmentSize` are the source blocks themselves; identifiers
 * `segmentSize + r` are repair symbols (XOR of the blocks chosen by [RepairPlanner]).
 * The stream is rateless: any sufficiently large set of distinct symbols reconstructs a
 * segment, independently of order or loss pattern.
 */
class FountainEncoder(
    private val source: BlockSource,
    val layout: SegmentLayout,
    private val planner: RepairPlanner,
) {
    private val scratch = ByteArray(source.blockSize)

    val blockSize: Int get() = source.blockSize

    /** Writes source block [localIndex] of [segment] into [out]. */
    fun systematic(segment: Int, localIndex: Int, out: ByteArray) {
        source.readBlock(layout.start(segment) + localIndex, out)
    }

    /** Writes repair symbol [repairIndex] of [segment] into [out]. */
    fun repair(segment: Int, repairIndex: Int, out: ByteArray) {
        val start = layout.start(segment)
        val neighbors = planner.neighbors(segment, repairIndex, layout.size(segment))
        java.util.Arrays.fill(out, 0, blockSize, 0)
        synchronized(scratch) {
            for (local in neighbors) {
                source.readBlock(start + local, scratch)
                xorInto(out, scratch)
            }
        }
    }
}

/**
 * Incremental GF(2) decoder for one segment.
 *
 * Maintains the received equations in reduced row-echelon form over the still unknown
 * blocks. Every pivot column appears in exactly one row, so each new symbol costs at most
 * one pass over the pivot rows and the work is spread evenly over the transfer instead of
 * being concentrated at the end.
 */
internal class SegmentDecoder(
    private val size: Int,
    private val blockSize: Int,
    private val store: BlockStore,
    private val startBlock: Int,
) {
    private val words = (size + 63) ushr 6
    private val known = BooleanArray(size)
    private val pivotBits = arrayOfNulls<LongArray>(size)
    private val pivotData = arrayOfNulls<ByteArray>(size)
    private val pivotMask = LongArray(words)
    private val scratch = ByteArray(blockSize)

    var knownCount: Int = 0
        private set

    var pivotCount: Int = 0
        private set

    val isComplete: Boolean get() = knownCount == size

    fun isKnown(local: Int): Boolean = known[local]

    /** Adds source block [local]. Returns true when it contributed new information. */
    fun addSystematic(local: Int, value: ByteArray): Boolean {
        if (known[local]) return false
        val bits = pivotBits[local]
        if (bits != null) {
            val data = pivotData[local]!!
            removePivot(local)
            markKnown(local, value)
            clearBit(bits, local)
            xorInto(data, value)
            if (!isZero(bits)) insertReduced(bits, data)
        } else {
            markKnown(local, value)
            eliminateKnownColumn(local, value)
        }
        return true
    }

    /** Adds a repair equation. Returns true when it increased the rank. */
    fun addEquation(neighbors: IntArray, value: ByteArray): Boolean {
        if (isComplete) return false
        val bits = LongArray(words)
        val data = value.copyOf(blockSize)
        for (local in neighbors) {
            if (known[local]) {
                store.read(startBlock + local, scratch)
                xorInto(data, scratch)
            } else {
                flipBit(bits, local)
            }
        }
        if (isZero(bits)) return false
        return insertReduced(bits, data)
    }

    private fun insertReduced(bits: LongArray, data: ByteArray): Boolean {
        // Eliminate every pivot column. Pivot rows contain no other pivot column, so the
        // set of pivot columns present in [bits] only shrinks while we iterate.
        for (w in 0 until words) {
            var m = bits[w] and pivotMask[w]
            while (m != 0L) {
                val bit = java.lang.Long.numberOfTrailingZeros(m)
                m = m and (m - 1)
                val pivot = (w shl 6) + bit
                xorBits(bits, pivotBits[pivot]!!)
                xorInto(data, pivotData[pivot]!!)
            }
        }
        val pivot = lowestBit(bits)
        if (pivot < 0) return false

        if (isSingleton(bits, pivot)) {
            markKnown(pivot, data)
            eliminateKnownColumn(pivot, data)
            return true
        }

        val word = pivot ushr 6
        val mask = 1L shl (pivot and 63)
        for (row in 0 until size) {
            val rowBits = pivotBits[row] ?: continue
            if (rowBits[word] and mask != 0L) {
                xorBits(rowBits, bits)
                xorInto(pivotData[row]!!, data)
                if (isSingleton(rowBits, row)) solvePivot(row)
            }
        }
        pivotBits[pivot] = bits
        pivotData[pivot] = data
        pivotMask[word] = pivotMask[word] or mask
        pivotCount++
        return true
    }

    /** Removes the now known free column [column] from every pivot row. */
    private fun eliminateKnownColumn(column: Int, value: ByteArray) {
        val word = column ushr 6
        val mask = 1L shl (column and 63)
        for (row in 0 until size) {
            val rowBits = pivotBits[row] ?: continue
            if (rowBits[word] and mask != 0L) {
                rowBits[word] = rowBits[word] xor mask
                xorInto(pivotData[row]!!, value)
                if (isSingleton(rowBits, row)) solvePivot(row)
            }
        }
    }

    private fun solvePivot(row: Int) {
        val data = pivotData[row]!!
        removePivot(row)
        markKnown(row, data)
    }

    private fun removePivot(row: Int) {
        pivotBits[row] = null
        pivotData[row] = null
        val word = row ushr 6
        pivotMask[word] = pivotMask[word] and (1L shl (row and 63)).inv()
        pivotCount--
    }

    private fun markKnown(local: Int, value: ByteArray) {
        store.write(startBlock + local, value)
        known[local] = true
        knownCount++
    }

    private fun flipBit(bits: LongArray, index: Int) {
        bits[index ushr 6] = bits[index ushr 6] xor (1L shl (index and 63))
    }

    private fun clearBit(bits: LongArray, index: Int) {
        bits[index ushr 6] = bits[index ushr 6] and (1L shl (index and 63)).inv()
    }

    private fun xorBits(target: LongArray, source: LongArray) {
        for (i in target.indices) target[i] = target[i] xor source[i]
    }

    private fun isZero(bits: LongArray): Boolean {
        for (w in bits) if (w != 0L) return false
        return true
    }

    private fun lowestBit(bits: LongArray): Int {
        for (w in bits.indices) {
            if (bits[w] != 0L) return (w shl 6) + java.lang.Long.numberOfTrailingZeros(bits[w])
        }
        return -1
    }

    private fun isSingleton(bits: LongArray, index: Int): Boolean {
        val word = index ushr 6
        for (w in bits.indices) {
            val expected = if (w == word) 1L shl (index and 63) else 0L
            if (bits[w] != expected) return false
        }
        return true
    }
}

/** Reassembles a payload from fountain symbols of every segment. */
class FountainDecoder(
    val totalBlocks: Int,
    val blockSize: Int,
    maxSegmentBlocks: Int,
    sessionSeed: Long,
    private val store: BlockStore,
) {
    val layout = SegmentLayout(totalBlocks, maxSegmentBlocks)
    private val planner = RepairPlanner(sessionSeed)
    private val segments = arrayOfNulls<SegmentDecoder>(layout.segmentCount)
    private val seenRepairs = HashSet<Long>()
    private var completedSegments = 0

    var recoveredBlocks: Int = 0
        private set

    val isComplete: Boolean get() = completedSegments == layout.segmentCount

    /** Fraction of source blocks already reconstructed, in `[0, 1]`. */
    val progress: Float get() = recoveredBlocks.toFloat() / totalBlocks

    /**
     * Number of independent equations held but not yet resolved. Together with
     * [recoveredBlocks] it measures how much information has been collected.
     */
    val pendingEquations: Int get() = segments.sumOf { it?.pivotCount ?: 0 }

    /** Accepts one symbol. Returns true when it contributed new information. */
    fun accept(segment: Int, symbolId: Int, payload: ByteArray): Boolean {
        if (segment !in 0 until layout.segmentCount) throw ProtocolException("Segment out of range")
        if (payload.size != blockSize) throw ProtocolException("Unexpected payload size")
        val segmentSize = layout.size(segment)
        val decoder = segments[segment] ?: SegmentDecoder(segmentSize, blockSize, store, layout.start(segment))
            .also { segments[segment] = it }
        if (decoder.isComplete) return false

        val before = decoder.knownCount
        val useful = if (symbolId < segmentSize) {
            decoder.addSystematic(symbolId, payload)
        } else {
            val key = (segment.toLong() shl 32) or (symbolId.toLong() and 0xFFFFFFFFL)
            if (!seenRepairs.add(key)) return false
            decoder.addEquation(planner.neighbors(segment, symbolId - segmentSize, segmentSize), payload)
        }
        recoveredBlocks += decoder.knownCount - before
        if (decoder.isComplete) {
            completedSegments++
            segments[segment] = COMPLETED
        }
        return useful
    }

    private companion object {
        /** Placeholder releasing the memory of finished segments. */
        val COMPLETED = SegmentDecoder(1, 1, NullStore, 0).also { it.addSystematic(0, ByteArray(1)) }
    }

    private object NullStore : BlockStore {
        override val blockSize: Int = 1
        override val totalBlocks: Int = 1
        override fun write(index: Int, data: ByteArray) = Unit
        override fun read(index: Int, destination: ByteArray) = Unit
        override fun openStream(length: Long): java.io.InputStream = throw UnsupportedOperationException()
        override fun close() = Unit
    }
}
