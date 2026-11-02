package com.madix.beam.protocol

import com.madix.beam.protocol.container.EntryInfo
import com.madix.beam.protocol.container.EntryKind
import com.madix.beam.protocol.container.FileSource
import com.madix.beam.protocol.container.TextSource
import com.madix.beam.protocol.container.TransferSource
import com.madix.beam.protocol.crypto.EphemeralKeyPair
import com.madix.beam.protocol.frame.Frame
import com.madix.beam.protocol.frame.FrameCodec
import com.madix.beam.protocol.frame.LinkInvitation
import com.madix.beam.protocol.link.LinkException
import com.madix.beam.protocol.link.LinkOffer
import com.madix.beam.protocol.link.LinkReceiveResult
import com.madix.beam.protocol.link.LinkReceiver
import com.madix.beam.protocol.link.LinkSender
import com.madix.beam.protocol.link.LinkSink
import com.madix.beam.protocol.link.OfferDecision
import com.madix.beam.protocol.link.OfferedEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class LinkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class MemorySink(private val decision: OfferDecision = OfferDecision.ACCEPT) : LinkSink {
        val files = LinkedHashMap<String, ByteArray>()
        var offer: LinkOffer? = null
        override fun onOffer(offer: LinkOffer): OfferDecision {
            this.offer = offer
            return decision
        }

        override fun openEntry(index: Int, entry: OfferedEntry): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                files[entry.name] = toByteArray()
            }
        }
    }

    /** Source whose size is unknown in advance, like some content providers. */
    private class UnknownSizeSource(private val data: ByteArray, override val displayName: String) : TransferSource {
        override val mimeType = "application/octet-stream"
        override val kind = EntryKind.FILE
        override fun openStream(): InputStream = data.inputStream()
    }

    private val executor = Executors.newCachedThreadPool()

    /** Runs a receiver and a sender over a loopback TCP connection, optionally through [relay]. */
    private fun runTransfer(
        sources: List<TransferSource>,
        receiverKeys: EphemeralKeyPair = EphemeralKeyPair.generate(),
        senderTargetKey: ByteArray = receiverKeys.publicKey,
        sink: MemorySink = MemorySink(),
        relay: ((InputStream, OutputStream) -> Unit)? = null,
    ): Pair<Future<LinkReceiveResult>, Future<*>> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val receiver = executor.submit(Callable {
            server.accept().use { socket ->
                server.close()
                socket.soTimeout = 10_000
                LinkReceiver(receiverKeys, "Receiver").receive(socket.getInputStream().buffered(), socket.getOutputStream().buffered(), sink)
            }
        })
        val targetPort = if (relay == null) {
            server.localPort
        } else {
            val proxy = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            executor.submit {
                proxy.accept().use { client ->
                    proxy.close()
                    Socket().use { upstream ->
                        upstream.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
                        val back = executor.submit { runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) } }
                        runCatching { relay(client.getInputStream(), upstream.getOutputStream()) }
                        runCatching { upstream.shutdownOutput() }
                        runCatching { back.get(10, TimeUnit.SECONDS) }
                    }
                }
            }
            proxy.localPort
        }
        val sender = executor.submit(Callable {
            Socket().use { socket ->
                socket.soTimeout = 10_000
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort))
                LinkSender(senderTargetKey, "Sender").send(socket.getInputStream().buffered(), socket.getOutputStream().buffered(256 * 1024), sources)
            }
        })
        return receiver to sender
    }

    private fun rootCause(e: Throwable): Throwable = if (e is ExecutionException && e.cause != null) e.cause!! else e

    @Test
    fun transfersMixedItemsIntact() {
        val random = Random(21)
        val big = random.nextBytes(5 * 1024 * 1024 + 77)
        val bigFile = File(temp.root, "clip.mp4").apply { writeBytes(big) }
        val empty = File(temp.root, "empty.bin").apply { writeBytes(ByteArray(0)) }
        val odd = random.nextBytes(1_234_567)
        val sources = listOf(
            FileSource(bigFile, "video/mp4"),
            TextSource("مرحبا Madix Beam"),
            FileSource(empty),
            UnknownSizeSource(odd, "unknown.dat"),
        )
        val sink = MemorySink()
        val (receiver, sender) = runTransfer(sources, sink = sink)
        val sendResult = sender.get(30, TimeUnit.SECONDS) as com.madix.beam.protocol.link.LinkSendResult
        val receiveResult = receiver.get(30, TimeUnit.SECONDS)
        assertEquals("Sender", receiveResult.senderName)
        assertEquals("Receiver", sendResult.receiverName)
        assertEquals(sendResult.verificationCode, receiveResult.verificationCode)
        assertArrayEquals(big, sink.files["clip.mp4"])
        assertArrayEquals("مرحبا Madix Beam".toByteArray(), sink.files["message.txt"])
        assertArrayEquals(ByteArray(0), sink.files["empty.bin"])
        assertArrayEquals(odd, sink.files["unknown.dat"])
        assertEquals(-1L, sink.offer!!.entries[3].sizeHint)
        assertEquals(big.size + odd.size + "مرحبا Madix Beam".toByteArray().size.toLong(), receiveResult.totalBytes)
        receiveResult.entries.zip(sendResult.entries).forEach { (r: EntryInfo, s: EntryInfo) -> assertArrayEquals(s.sha256, r.sha256) }
    }

    @Test
    fun wrongReceiverKeyLeaksNothing() {
        val sink = MemorySink()
        val (receiver, sender) = runTransfer(
            sources = listOf(TextSource("secret")),
            senderTargetKey = EphemeralKeyPair.generate().publicKey,
            sink = sink,
        )
        try {
            receiver.get(20, TimeUnit.SECONDS)
            fail("Receiver accepted a transfer sealed for another key")
        } catch (e: ExecutionException) {
            val cause = rootCause(e)
            assertTrue(cause is LinkException && cause.reason == LinkException.Reason.INTEGRITY)
        }
        runCatching { sender.get(20, TimeUnit.SECONDS) }
        assertTrue(sink.offer == null)
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun tamperingIsDetected() {
        val data = Random(5).nextBytes(600_000)
        val file = File(temp.root, "doc.pdf").apply { writeBytes(data) }
        var position = 0L
        val (receiver, sender) = runTransfer(listOf(FileSource(file, "application/pdf")), relay = { input, output ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for (i in 0 until read) {
                    if (position + i == 300_000L) buffer[i] = (buffer[i].toInt() xor 0x01).toByte()
                }
                position += read
                output.write(buffer, 0, read)
                output.flush()
            }
        })
        try {
            receiver.get(20, TimeUnit.SECONDS)
            fail("Tampered stream accepted")
        } catch (e: ExecutionException) {
            val cause = rootCause(e)
            assertTrue("Unexpected $cause", cause is LinkException && cause.reason == LinkException.Reason.INTEGRITY)
        }
        runCatching { sender.get(20, TimeUnit.SECONDS) }
    }

    @Test
    fun truncatedStreamIsRejected() {
        val data = Random(6).nextBytes(900_000)
        val file = File(temp.root, "a.bin").apply { writeBytes(data) }
        var forwarded = 0
        val (receiver, sender) = runTransfer(listOf(FileSource(file)), relay = { input, output ->
            val buffer = ByteArray(8192)
            while (forwarded < 400_000) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                forwarded += read
            }
            output.flush()
        })
        try {
            receiver.get(20, TimeUnit.SECONDS)
            fail("Truncated stream accepted")
        } catch (e: ExecutionException) {
            assertTrue(rootCause(e) is java.io.IOException)
        }
        runCatching { sender.get(20, TimeUnit.SECONDS) }
    }

    @Test
    fun declinedOfferSendsNoContent() {
        val sink = MemorySink(OfferDecision.INSUFFICIENT_STORAGE)
        val (receiver, sender) = runTransfer(listOf(TextSource("x".repeat(1000))), sink = sink)
        try {
            sender.get(20, TimeUnit.SECONDS)
            fail("Sender ignored the refusal")
        } catch (e: ExecutionException) {
            val cause = rootCause(e)
            assertTrue(cause is LinkException && cause.reason == LinkException.Reason.INSUFFICIENT_STORAGE)
        }
        runCatching { receiver.get(20, TimeUnit.SECONDS) }
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun loopbackThroughput() {
        val data = Random(8).nextBytes(64 * 1024 * 1024)
        val file = File(temp.root, "large.bin").apply { writeBytes(data) }
        val sink = object : LinkSink {
            override fun onOffer(offer: LinkOffer) = OfferDecision.ACCEPT
            override fun openEntry(index: Int, entry: OfferedEntry): OutputStream = OutputStream.nullOutputStream()
        }
        val start = System.nanoTime()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val keys = EphemeralKeyPair.generate()
        val receiver = executor.submit(Callable {
            server.accept().use { LinkReceiver(keys, "R").receive(it.getInputStream().buffered(), it.getOutputStream().buffered(), sink) }
        })
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
            LinkSender(keys.publicKey, "S").send(it.getInputStream().buffered(), it.getOutputStream().buffered(256 * 1024), listOf(FileSource(file)))
        }
        receiver.get(60, TimeUnit.SECONDS)
        val seconds = (System.nanoTime() - start) / 1e9
        val rate = data.size / seconds / (1024 * 1024)
        println("link loopback throughput: %.1f MB/s".format(rate))
        // Bounded here by the test machine's software AES-GCM; Android uses hardware AES.
        assertTrue("Throughput too low: $rate MB/s", rate > 15)
    }

    @Test
    fun invitationRoundTrip() {
        val keys = EphemeralKeyPair.generate()
        val invitation = LinkInvitation(
            nonce = 77,
            publicKey = keys.publicKey,
            deviceName = "Galaxy S24",
            networkName = "DIRECT-Mx-MadixBeam-4F2A",
            passphrase = "k9Qz2LmP8xYw4RtB",
            port = 41234,
            addresses = listOf(byteArrayOf(192.toByte(), 168.toByte(), 1, 23)),
        )
        val text = FrameCodec.toQrText(FrameCodec.encodeInvite(invitation))
        val decoded = (FrameCodec.parseQrText(text) as Frame.Invite).invitation
        assertArrayEquals(keys.publicKey, decoded.publicKey)
        assertEquals("Galaxy S24", decoded.deviceName)
        assertEquals("DIRECT-Mx-MadixBeam-4F2A", decoded.networkName)
        assertEquals("k9Qz2LmP8xYw4RtB", decoded.passphrase)
        assertEquals(41234, decoded.port)
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 23), decoded.addresses.single())
        assertTrue(decoded.hasDirectGroup)
        assertArrayEquals(keys.publicKey, decoded.toPairingRequest().publicKey)

        val lanOnly = LinkInvitation(1, keys.publicKey, "Tab", "", "", 5000, emptyList())
        val lanDecoded = (FrameCodec.parseQrText(FrameCodec.toQrText(FrameCodec.encodeInvite(lanOnly))) as Frame.Invite).invitation
        assertTrue(!lanDecoded.hasDirectGroup)
    }
}
