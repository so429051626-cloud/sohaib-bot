package com.madix.beam.protocol

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.encoder.Encoder
import com.madix.beam.protocol.container.ContainerManifest
import com.madix.beam.protocol.container.ContainerReader
import com.madix.beam.protocol.container.ContainerSink
import com.madix.beam.protocol.container.EntryInfo
import com.madix.beam.protocol.container.FileNames
import com.madix.beam.protocol.container.FileSource
import com.madix.beam.protocol.container.TextSource
import com.madix.beam.protocol.container.TransferSource
import com.madix.beam.protocol.crypto.EphemeralKeyPair
import com.madix.beam.protocol.fountain.FileBlockStore
import com.madix.beam.protocol.fountain.MemoryBlockStore
import com.madix.beam.protocol.frame.FrameCodec
import com.madix.beam.protocol.frame.PairingRequest
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.session.ErrorCorrection
import com.madix.beam.protocol.session.PreparedTransfer
import com.madix.beam.protocol.session.ReceiverEngine
import com.madix.beam.protocol.session.SecurityParams
import com.madix.beam.protocol.session.TransferPreparer
import com.madix.beam.protocol.session.TransferProfile
import com.madix.beam.protocol.session.WrongKeyException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.random.Random

class EndToEndTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** In-memory sink collecting unpacked entries. */
    private class MemorySink : ContainerSink {
        val files = LinkedHashMap<String, ByteArray>()
        var manifest: ContainerManifest? = null
        override fun onManifest(manifest: ContainerManifest) {
            this.manifest = manifest
        }

        override fun openEntry(index: Int, entry: EntryInfo): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                files[entry.name] = toByteArray()
            }
        }
    }

    private fun sampleSources(dir: File, random: Random): Pair<List<TransferSource>, Map<String, ByteArray>> {
        val photo = random.nextBytes(23_456) // incompressible, like a JPEG
        val document = buildString { repeat(400) { append("Madix Beam — نقل آمن عبر الضوء $it\n") } }.toByteArray()
        val photoFile = File(dir, "photo.jpg").apply { writeBytes(photo) }
        val docFile = File(dir, "notes.txt").apply { writeBytes(document) }
        val text = "رسالة سرية: الاجتماع الساعة 9"
        val sources = listOf(
            FileSource(photoFile, "image/jpeg"),
            FileSource(docFile, "text/plain"),
            TextSource(text),
        )
        val expected = mapOf("photo.jpg" to photo, "notes.txt" to document, "message.txt" to text.toByteArray())
        return sources to expected
    }

    private fun ecLevel(profile: TransferProfile) = when (profile.errorCorrection) {
        ErrorCorrection.L -> ErrorCorrectionLevel.L
        ErrorCorrection.M -> ErrorCorrectionLevel.M
        ErrorCorrection.Q -> ErrorCorrectionLevel.Q
        ErrorCorrection.H -> ErrorCorrectionLevel.H
    }

    /** Renders [text] as a QR image and decodes it again, as a camera would. */
    private fun throughQr(text: String, level: ErrorCorrectionLevel): String {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to level, EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val scale = 3
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x / scale, y / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val result = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true))
        return result.text
    }

    private fun broadcastUntilComplete(
        prepared: PreparedTransfer,
        engine: ReceiverEngine,
        loss: Double,
        viaQr: Boolean,
        random: Random,
    ): Int {
        val scheduler = prepared.openScheduler()
        var frames = 0
        val level = ecLevel(prepared.profile)
        scheduler.use {
            while (!engine.isComplete) {
                val text = scheduler.nextQrText()
                frames++
                check(frames < prepared.header.totalBlocks * 8 + 200) { "Transfer did not converge" }
                if (random.nextDouble() < loss) continue
                val received = if (viaQr) throughQr(text, level) else text
                assertEquals(text, received)
                engine.ingest(received)
                // Cameras routinely decode the same frame several times.
                if (random.nextDouble() < 0.3) engine.ingest(received)
            }
        }
        return frames
    }

    @Test
    fun openModeThroughRealQrCodes() {
        val random = Random(11)
        val (sources, expected) = sampleSources(temp.root, random)
        val preparer = TransferPreparer(temp.newFolder("work"))
        val prepared = preparer.prepare(sources, "Pixel 9", SecurityParams.Open, TransferProfile.BALANCED)
        prepared.use {
            assertTrue(prepared.compressed)
            val engine = ReceiverEngine(null, { h -> MemoryBlockStore(h.totalBlocks, h.blockSize) })
            val frames = broadcastUntilComplete(prepared, engine, loss = 0.2, viaQr = true, random = random)
            println("open mode: ${prepared.header.totalBlocks} blocks, $frames frames")
            val opener = engine.opener()
            val sink = MemorySink()
            val manifest = opener.open(opener.deriveKey(), sink)
            assertEquals("Pixel 9", manifest.senderName)
            assertEquals(expected.keys, sink.files.keys)
            expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, sink.files[name]) }
        }
    }

    @Test
    fun everyProfileFitsInQrAndDecodes() {
        for (profile in TransferProfile.entries) {
            val text = FrameCodec.toQrText(FrameCodec.encodeData(1, 0, 0, Random(2).nextBytes(profile.blockSize)))
            val qr = Encoder.encode(text, ecLevel(profile))
            assertEquals(Mode.ALPHANUMERIC, qr.mode)
            println("${profile.id}: ${text.length} chars -> QR version ${qr.version.versionNumber}")
            assertEquals(text, throughQr(text, ecLevel(profile)))
        }
    }

    @Test
    fun passwordModeAndWrongPassword() {
        val random = Random(12)
        val (sources, expected) = sampleSources(temp.root, random)
        val prepared = TransferPreparer(temp.newFolder("work")).prepare(
            sources, "Galaxy", SecurityParams.Password("كلمة-سر-2026".toCharArray()), TransferProfile.FAST,
        )
        prepared.use {
            assertEquals(SecurityMode.PASSWORD, prepared.header.mode)
            val storeFile = temp.newFile("store.bin")
            val engine = ReceiverEngine(null, { h -> FileBlockStore(storeFile, h.totalBlocks, h.blockSize) })
            broadcastUntilComplete(prepared, engine, loss = 0.35, viaQr = false, random = random)
            val opener = engine.opener()
            assertTrue(opener.requiresPassword)

            val wrong = opener.deriveKey("wrong".toCharArray())
            assertFalse(opener.verifyKey(wrong))
            try {
                opener.open(wrong, MemorySink())
                fail("Wrong password accepted")
            } catch (e: WrongKeyException) {
                // expected
            }

            val right = opener.deriveKey("كلمة-سر-2026".toCharArray())
            assertTrue(opener.verifyKey(right))
            val sink = MemorySink()
            opener.open(right, sink)
            expected.forEach { (name, bytes) -> assertArrayEquals(bytes, sink.files[name]) }
            engine.close()
        }
    }

    @Test
    fun pairedModeEndToEnd() {
        val random = Random(13)
        val (sources, expected) = sampleSources(temp.root, random)
        val receiverKeys = EphemeralKeyPair.generate()
        val pairingText = FrameCodec.toQrText(FrameCodec.encodePairing(PairingRequest(7, receiverKeys.publicKey, "Receiver")))
        val pairing = FrameCodec.parseQrText(throughQr(pairingText, ErrorCorrectionLevel.M))
            as com.madix.beam.protocol.frame.Frame.Pairing

        val prepared = TransferPreparer(temp.newFolder("work")).prepare(
            sources, "Sender", SecurityParams.Paired(pairing.request), TransferProfile.TURBO,
        )
        prepared.use {
            assertNotNull(prepared.shortAuthString)
            assertEquals("Receiver", prepared.peerName)

            // A third device must not lock onto a transfer addressed to someone else.
            val outsider = ReceiverEngine(EphemeralKeyPair.generate(), { h -> MemoryBlockStore(h.totalBlocks, h.blockSize) })
            val stream = prepared.openScheduler()
            repeat(30) { outsider.ingest(stream.nextQrText()) }
            stream.close()
            assertNull(outsider.header)
            assertTrue(outsider.snapshot().wrongRecipientSeen)

            val engine = ReceiverEngine(receiverKeys, { h -> MemoryBlockStore(h.totalBlocks, h.blockSize) })
            broadcastUntilComplete(prepared, engine, loss = 0.1, viaQr = true, random = random)
            assertEquals(prepared.shortAuthString, engine.shortAuthString())
            val opener = engine.opener()
            val sink = MemorySink()
            opener.open(opener.deriveKey(), sink)
            expected.forEach { (name, bytes) -> assertArrayEquals(bytes, sink.files[name]) }
        }
    }

    @Test
    fun largeMultiSegmentTransfer() {
        val random = Random(14)
        val big = random.nextBytes(3 * 1024 * 1024 + 123)
        val file = File(temp.root, "video.mp4").apply { writeBytes(big) }
        val prepared = TransferPreparer(temp.newFolder("work")).prepare(
            listOf(FileSource(file, "video/mp4")), "Tablet", SecurityParams.Open, TransferProfile.TURBO,
        )
        prepared.use {
            assertFalse(prepared.compressed)
            assertTrue(prepared.header.totalBlocks > 2048)
            val engine = ReceiverEngine(null, { h -> MemoryBlockStore(h.totalBlocks, h.blockSize) })
            val start = System.nanoTime()
            val frames = broadcastUntilComplete(prepared, engine, loss = 0.25, viaQr = false, random = random)
            println("large: ${prepared.header.totalBlocks} blocks, $frames frames, ${(System.nanoTime() - start) / 1_000_000} ms")
            val sink = MemorySink()
            val opener = engine.opener()
            opener.open(opener.deriveKey(), sink)
            assertArrayEquals(big, sink.files["video.mp4"])
        }
    }

    @Test
    fun manifestSanitisesHostileNames() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil_name.txt", FileNames.sanitize("evil:name.txt"))
        assertEquals("photoexe.jpg", FileNames.sanitize("photo\u202Eexe.jpg"))
        assertEquals("file", FileNames.sanitize("..."))
        assertEquals("hidden", FileNames.sanitize(".hidden"))
        assertEquals("x", FileNames.sanitize("C:\\Windows\\x"))
        assertEquals("a_b.txt", FileNames.sanitize("a|b.txt"))
        val long = "a".repeat(300) + ".pdf"
        val sanitized = FileNames.sanitize(long)
        assertTrue(sanitized.length <= 120)
        assertTrue(sanitized.endsWith(".pdf"))
        val dir = temp.newFolder("unique")
        File(dir, "a.txt").writeText("1")
        assertEquals("a (1).txt", FileNames.unique(dir, "a.txt").name)
    }

    @Test
    fun containerRejectsTrailingData() {
        val sink = MemorySink()
        val dir = temp.newFolder("c")
        val dest = File(dir, "c.bin")
        com.madix.beam.protocol.container.ContainerWriter.write(listOf(TextSource("hi")), "x", 0, dir, dest)
        val bytes = dest.readBytes() + byteArrayOf(1)
        try {
            ContainerReader.read(bytes.inputStream(), sink)
            fail("Trailing data accepted")
        } catch (e: ProtocolException) {
            // expected
        }
    }
}
