package com.madix.beam.protocol

import com.madix.beam.protocol.container.ContainerSink
import com.madix.beam.protocol.container.EntryInfo
import com.madix.beam.protocol.container.TextSource
import com.madix.beam.protocol.fountain.MemoryBlockStore
import com.madix.beam.protocol.frame.FrameCodec
import com.madix.beam.protocol.session.ReceiverEngine
import com.madix.beam.protocol.session.SecurityParams
import com.madix.beam.protocol.session.TransferPreparer
import com.madix.beam.protocol.session.TransferProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class ReceiverEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun openText(engine: ReceiverEngine): String {
        val out = ByteArrayOutputStream()
        val opener = engine.opener()
        opener.open(opener.deriveKey(), object : ContainerSink {
            override fun openEntry(index: Int, entry: EntryInfo): OutputStream = out
        })
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun framesBeforeHeaderAreReplayed() {
        val text = "x".repeat(20_000) + "end"
        val prepared = TransferPreparer(temp.newFolder()).prepare(
            listOf(TextSource(text)), "A", SecurityParams.Open, TransferProfile.RELIABLE,
        )
        prepared.use {
            val scheduler = prepared.openScheduler()
            val frames = List(200) { scheduler.nextQrText() }
            scheduler.close()
            val engine = ReceiverEngine(null, { h -> MemoryBlockStore(h.totalBlocks, h.blockSize) })
            // Skip the first header so that data frames arrive before any header.
            for (frame in frames.drop(1)) {
                engine.ingest(frame)
                if (engine.isComplete) break
            }
            assertTrue(engine.isComplete)
            assertEquals(text, openText(engine))
        }
    }

    @Test
    fun interleavedTransmissionsDoNotMix() {
        val first = TransferPreparer(temp.newFolder()).prepare(
            listOf(TextSource("first transfer ".repeat(500))), "A", SecurityParams.Open, TransferProfile.BALANCED,
        )
        val second = TransferPreparer(temp.newFolder()).prepare(
            listOf(TextSource("second transfer ".repeat(500))), "B", SecurityParams.Open, TransferProfile.BALANCED,
        )
        first.use {
            second.use {
                val a = first.openScheduler()
                val b = second.openScheduler()
                val engine = ReceiverEngine(null, { h -> MemoryBlockStore(h.totalBlocks, h.blockSize) })
                var guard = 0
                while (!engine.isComplete && guard++ < 2_000) {
                    engine.ingest(a.nextQrText())
                    engine.ingest(b.nextQrText())
                }
                a.close()
                b.close()
                assertTrue(engine.isComplete)
                assertEquals(first.header.sessionId, engine.header!!.sessionId)
                assertEquals("first transfer ".repeat(500), openText(engine))
            }
        }
    }

    @Test
    fun foreignAndCorruptedTextsAreReported() {
        val engine = ReceiverEngine(null, { h -> MemoryBlockStore(h.totalBlocks, h.blockSize) })
        assertTrue(engine.ingest("https://example.com") is ReceiverEngine.Event.Foreign)
        assertTrue(engine.ingest("MDX1ABC") is ReceiverEngine.Event.Corrupted)
        val valid = FrameCodec.toQrText(FrameCodec.encodeData(1, 0, 0, ByteArray(320)))
        val damaged = valid.substring(0, valid.length - 3) + "000"
        assertTrue(engine.ingest(damaged) is ReceiverEngine.Event.Corrupted)
    }
}
