package com.madix.beam.protocol

import com.madix.beam.protocol.codec.Base45
import com.madix.beam.protocol.frame.Frame
import com.madix.beam.protocol.frame.FrameCodec
import com.madix.beam.protocol.frame.PairingRequest
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.frame.TransferHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class Base45Test {

    @Test
    fun rfc9285Vectors() {
        assertEquals("BB8", Base45.encode("AB".toByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".toByteArray()))
        assertEquals("UJCLQE7W581", Base45.encode("base-45".toByteArray()))
        assertEquals("QED8WEX0", Base45.encode("ietf!".toByteArray()))
        assertEquals("ietf!", String(Base45.decode("QED8WEX0")))
        assertEquals("", Base45.encode(ByteArray(0)))
        assertEquals(0, Base45.decode("").size)
    }

    @Test
    fun roundTripsRandomData() {
        val random = Random(7)
        repeat(500) {
            val data = random.nextBytes(random.nextInt(0, 3000))
            val text = Base45.encode(data)
            assertEquals(Base45.encodedLength(data.size), text.length)
            assertTrue(text.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:" })
            assertArrayEquals(data, Base45.decode(text))
        }
    }

    @Test
    fun rejectsInvalidInput() {
        for (bad in listOf("GGW", "A", "ABCD", "ab", "ABé", ":::")) {
            try {
                Base45.decode(bad)
                fail("Expected failure for '$bad'")
            } catch (e: ProtocolException) {
                // expected
            }
        }
    }
}

class FrameCodecTest {

    private fun header(mode: SecurityMode = SecurityMode.OPEN, key: ByteArray = ByteArray(0)) = TransferHeader(
        sessionId = 0x12345678,
        mode = mode,
        cipherLength = 100_000,
        blockSize = 560,
        totalBlocks = (100_000 + 559) / 560,
        segmentBlocks = Protocol.MAX_SEGMENT_BLOCKS,
        chunkSize = Protocol.AEAD_CHUNK_SIZE,
        salt = ByteArray(16) { it.toByte() },
        noncePrefix = ByteArray(7) { (it * 3).toByte() },
        kdfIterations = if (mode == SecurityMode.PASSWORD) Protocol.PBKDF2_ITERATIONS else 0,
        senderPublicKey = key,
        receiverKeyId = ByteArray(8) { 9 },
    )

    @Test
    fun headerRoundTripAndPadding() {
        for (mode in SecurityMode.entries) {
            val key = if (mode == SecurityMode.PAIRED) ByteArray(91) { (it + 1).toByte() } else ByteArray(0)
            val h = header(mode, key)
            val length = FrameCodec.dataFrameLength(h.blockSize)
            val bytes = FrameCodec.encodeHeader(h, length)
            assertEquals(length, bytes.size)
            val decoded = (FrameCodec.decode(bytes) as Frame.Header).header
            assertEquals(h, decoded)
            assertEquals(h.sessionSeed, decoded.sessionSeed)
            assertArrayEquals(h.associatedData(), decoded.associatedData())
        }
    }

    @Test
    fun dataRoundTrip() {
        val payload = Random(1).nextBytes(560)
        val bytes = FrameCodec.encodeData(42, 3, 777, payload)
        assertEquals(FrameCodec.dataFrameLength(560), bytes.size)
        val text = FrameCodec.toQrText(bytes)
        assertTrue(text.startsWith(Protocol.QR_PREFIX))
        val symbol = (FrameCodec.parseQrText(text) as Frame.Data).symbol
        assertEquals(42, symbol.sessionId)
        assertEquals(3, symbol.segment)
        assertEquals(777, symbol.symbolId)
        assertArrayEquals(payload, symbol.payload)
    }

    @Test
    fun pairingRoundTripWithLongName() {
        val request = PairingRequest(99, ByteArray(91) { 5 }, "جهاز سامسونج الخاص بصهيب الطويل جداً جداً")
        val decoded = (FrameCodec.decode(FrameCodec.encodePairing(request)) as Frame.Pairing).request
        assertEquals(99, decoded.nonce)
        assertArrayEquals(request.publicKey, decoded.publicKey)
        assertTrue(decoded.deviceName.toByteArray().size <= Protocol.MAX_DEVICE_NAME_BYTES)
        assertTrue(request.deviceName.startsWith(decoded.deviceName))
    }

    @Test
    fun detectsEveryCorruptedByte() {
        val bytes = FrameCodec.encodeData(1, 0, 5, Random(3).nextBytes(320))
        for (i in bytes.indices) {
            val copy = bytes.copyOf()
            copy[i] = (copy[i].toInt() xor 0x10).toByte()
            try {
                FrameCodec.decode(copy)
                fail("Corruption at $i not detected")
            } catch (e: ProtocolException) {
                // expected
            }
        }
    }

    @Test
    fun foreignTextIsNotParsed() {
        assertNull(FrameCodec.parseQrText("https://example.com"))
        assertNull(FrameCodec.parseQrText("WIFI:S:home;T:WPA;P:secret;;"))
    }

    @Test
    fun rejectsInconsistentHeader() {
        try {
            TransferHeader(
                sessionId = 1, mode = SecurityMode.OPEN, cipherLength = 1000, blockSize = 560,
                totalBlocks = 5, segmentBlocks = 2048, chunkSize = 65536, salt = ByteArray(16),
                noncePrefix = ByteArray(7), kdfIterations = 0, senderPublicKey = ByteArray(0), receiverKeyId = ByteArray(8),
            )
            fail("Inconsistent block count accepted")
        } catch (e: ProtocolException) {
            // expected
        }
    }
}
