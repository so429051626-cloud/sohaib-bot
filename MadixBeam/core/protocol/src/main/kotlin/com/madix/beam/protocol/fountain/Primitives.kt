package com.madix.beam.protocol.fountain

/**
 * SplitMix64 generator. Deterministic across platforms, which is essential because the
 * sender and receiver must derive identical repair-symbol neighbourhoods.
 */
internal class SplitMix64(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 1) % bound).toInt()
    }

    companion object {
        const val GOLDEN_GAMMA: Long = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val MIX_1: Long = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        const val MIX_2: Long = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
    }
}

/**
 * Deterministic partition of the source blocks into near-equal segments of at most
 * `maxSegmentBlocks` blocks. Each segment is an independent fountain code, which bounds the
 * cost of Gaussian elimination for large payloads.
 */
class SegmentLayout(val totalBlocks: Int, maxSegmentBlocks: Int) {
    val segmentCount: Int
    private val base: Int
    private val extra: Int

    init {
        require(totalBlocks > 0) { "totalBlocks must be positive" }
        require(maxSegmentBlocks > 0) { "maxSegmentBlocks must be positive" }
        segmentCount = ((totalBlocks.toLong() + maxSegmentBlocks - 1) / maxSegmentBlocks).toInt()
        base = totalBlocks / segmentCount
        extra = totalBlocks % segmentCount
    }

    fun size(segment: Int): Int = base + if (segment < extra) 1 else 0

    fun start(segment: Int): Int = segment * base + minOf(segment, extra)

    /** Returns the segment containing [globalBlock]. */
    fun segmentOf(globalBlock: Int): Int {
        val boundary = extra * (base + 1)
        return if (globalBlock < boundary) globalBlock / (base + 1) else extra + (globalBlock - boundary) / base
    }
}

/**
 * Chooses the source blocks combined by a repair symbol.
 *
 * Small segments use dense random combinations (every block with probability 1/2), which
 * makes any `k + 2` received symbols sufficient with probability above 75% and `k + 10`
 * above 99.9%. Large segments use sparse combinations of 48 to 80 blocks with a varying
 * degree so that the code never collapses into the even-weight subspace.
 */
class RepairPlanner(private val sessionSeed: Long) {

    fun neighbors(segment: Int, repairIndex: Int, segmentSize: Int): IntArray {
        require(segmentSize > 0)
        val rng = SplitMix64(
            sessionSeed +
                segment.toLong() * SplitMix64.GOLDEN_GAMMA +
                (repairIndex.toLong() + 1L) * SplitMix64.MIX_1,
        )
        if (segmentSize == 1) return intArrayOf(0)
        return if (segmentSize <= DENSE_LIMIT) dense(rng, segmentSize) else sparse(rng, segmentSize)
    }

    private fun dense(rng: SplitMix64, size: Int): IntArray {
        val picked = IntArray(size)
        var count = 0
        var bits = 0L
        var available = 0
        for (i in 0 until size) {
            if (available == 0) {
                bits = rng.nextLong()
                available = 64
            }
            if (bits and 1L == 1L) picked[count++] = i
            bits = bits ushr 1
            available--
        }
        if (count == 0) return intArrayOf(rng.nextInt(size))
        return picked.copyOf(count)
    }

    private fun sparse(rng: SplitMix64, size: Int): IntArray {
        val degree = minOf(size, MIN_DEGREE + rng.nextInt(MAX_DEGREE - MIN_DEGREE + 1))
        val taken = LongArray((size + 63) ushr 6)
        val result = IntArray(degree)
        var count = 0
        while (count < degree) {
            val candidate = rng.nextInt(size)
            val word = candidate ushr 6
            val mask = 1L shl (candidate and 63)
            if (taken[word] and mask == 0L) {
                taken[word] = taken[word] or mask
                result[count++] = candidate
            }
        }
        result.sort()
        return result
    }

    companion object {
        const val DENSE_LIMIT = 128
        const val MIN_DEGREE = 48
        const val MAX_DEGREE = 80
    }
}

internal fun xorInto(target: ByteArray, source: ByteArray) {
    val n = minOf(target.size, source.size)
    for (i in 0 until n) {
        target[i] = (target[i].toInt() xor source[i].toInt()).toByte()
    }
}
