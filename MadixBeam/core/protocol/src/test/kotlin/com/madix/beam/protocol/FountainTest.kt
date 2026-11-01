package com.madix.beam.protocol

import com.madix.beam.protocol.fountain.ByteArrayBlockSource
import com.madix.beam.protocol.fountain.FountainDecoder
import com.madix.beam.protocol.fountain.MemoryBlockStore
import com.madix.beam.protocol.fountain.SegmentLayout
import com.madix.beam.protocol.frame.Frame
import com.madix.beam.protocol.frame.FrameCodec
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.frame.TransferHeader
import com.madix.beam.protocol.session.BroadcastScheduler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SegmentLayoutTest {
    @Test
    fun partitionsExactly() {
        for (total in listOf(1, 2, 3, 100, 2047, 2048, 2049, 4097, 10_000, 123_457)) {
            for (max in listOf(1, 7, 128, 2048)) {
                val layout = SegmentLayout(total, max)
                var expectedStart = 0
                for (s in 0 until layout.segmentCount) {
                    assertEquals(expectedStart, layout.start(s))
                    assertTrue(layout.size(s) in 1..max)
                    for (g in listOf(layout.start(s), layout.start(s) + layout.size(s) - 1)) {
                        assertEquals(s, layout.segmentOf(g))
                    }
                    expectedStart += layout.size(s)
                }
                assertEquals(total, expectedStart)
            }
        }
    }
}

class FountainTest {

    private fun headerFor(size: Int, blockSize: Int, seed: Int): TransferHeader {
        val random = Random(seed)
        return TransferHeader(
            sessionId = random.nextInt(),
            mode = SecurityMode.OPEN,
            cipherLength = size.toLong(),
            blockSize = blockSize,
            totalBlocks = (size + blockSize - 1) / blockSize,
            segmentBlocks = Protocol.MAX_SEGMENT_BLOCKS,
            chunkSize = Protocol.AEAD_CHUNK_SIZE,
            salt = random.nextBytes(16),
            noncePrefix = random.nextBytes(7),
            kdfIterations = 0,
            senderPublicKey = ByteArray(0),
            receiverKeyId = ByteArray(8),
        )
    }

    /**
     * Streams frames with random loss starting at [startFrame] and returns the number of
     * frames that had to be broadcast until the receiver completed.
     */
    private fun simulate(size: Int, blockSize: Int, loss: Double, startFrame: Int, seed: Int): Int {
        val random = Random(seed)
        val data = random.nextBytes(size)
        val header = headerFor(size, blockSize, seed)
        val scheduler = BroadcastScheduler(header, ByteArrayBlockSource(data, blockSize))
        repeat(startFrame) { scheduler.nextFrame() }
        var decoder: FountainDecoder? = null
        var store: MemoryBlockStore? = null
        var sent = 0
        val limit = header.totalBlocks * 6 + 200
        while (sent < limit) {
            val frame = scheduler.nextFrame()
            sent++
            if (random.nextDouble() < loss) continue
            when (val parsed = FrameCodec.decode(frame)) {
                is Frame.Header -> if (decoder == null) {
                    store = MemoryBlockStore(parsed.header.totalBlocks, parsed.header.blockSize)
                    decoder = FountainDecoder(
                        parsed.header.totalBlocks, parsed.header.blockSize,
                        parsed.header.segmentBlocks, parsed.header.sessionSeed, store,
                    )
                }
                is Frame.Data -> decoder?.accept(parsed.symbol.segment, parsed.symbol.symbolId, parsed.symbol.payload)
                is Frame.Pairing -> error("unexpected")
            }
            if (decoder?.isComplete == true) break
        }
        val d = checkNotNull(decoder) { "No header received" }
        assertTrue("Decoder did not complete (size=$size loss=$loss)", d.isComplete)
        val recovered = store!!.openStream(size.toLong()).readBytes()
        assertArrayEquals("Payload mismatch (size=$size loss=$loss)", data, recovered)
        return sent
    }

    @Test
    fun recoversAcrossSizesAndLossRates() {
        val blockSize = 256
        val sizes = listOf(17, 256, 257, 1_000, 10_000, 32_768, 33_000, 100_000, 524_288, 600_000)
        val losses = listOf(0.0, 0.1, 0.3, 0.5)
        val report = StringBuilder("\nsize      blocks loss  frames  overhead\n")
        var seed = 1
        for (size in sizes) {
            val blocks = (size + blockSize - 1) / blockSize
            for (loss in losses) {
                val sent = simulate(size, blockSize, loss, startFrame = 0, seed = seed++)
                val ideal = blocks / (1 - loss) / (1 - 1.0 / Protocol.HEADER_INTERVAL)
                val overhead = sent / ideal
                report.append("%-9d %-6d %-5.2f %-7d %.3f\n".format(size, blocks, loss, sent, overhead))
                // Generous bound; typical overhead is reported for tuning.
                assertTrue("Overhead too high: $overhead for size=$size loss=$loss", overhead < 1.6 || sent < blocks + 40)
            }
        }
        println(report)
    }

    @Test
    fun lateJoinerRecovers() {
        val size = 300_000
        val blockSize = 512
        val blocks = (size + blockSize - 1) / blockSize
        for ((i, start) in listOf(1, blocks / 3, blocks, blocks * 2 + 17).withIndex()) {
            val sent = simulate(size, blockSize, 0.2, startFrame = start, seed = 100 + i)
            println("late joiner start=$start blocks=$blocks frames=$sent")
        }
    }

    @Test
    fun multiSegmentPayloadRecovers() {
        // 2 segments+ : 3000 blocks with 2048 max per segment.
        val sent = simulate(3000 * 200 - 50, 200, 0.25, startFrame = 500, seed = 77)
        println("multi-segment frames=$sent")
    }

    @Test
    fun tinyPayloadsRecoverQuickly() {
        for (size in 17..60) {
            val sent = simulate(size, 192, 0.3, 0, seed = 1000 + size)
            assertTrue("tiny payload took $sent frames", sent <= 50)
        }
    }
}
