package com.madix.beam.ui.receive

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.madix.beam.core.AppContainer
import com.madix.beam.data.history.HistoryItem
import com.madix.beam.data.history.TransferDirection
import com.madix.beam.data.history.TransferRecord
import com.madix.beam.protocol.container.ContainerManifest
import com.madix.beam.protocol.container.ContainerSink
import com.madix.beam.protocol.container.EntryInfo
import com.madix.beam.protocol.container.EntryKind
import com.madix.beam.protocol.container.FileNames
import com.madix.beam.protocol.container.ProgressListener
import com.madix.beam.protocol.crypto.AuthenticationException
import com.madix.beam.protocol.crypto.EphemeralKeyPair
import com.madix.beam.protocol.fountain.BlockStore
import com.madix.beam.protocol.fountain.FileBlockStore
import com.madix.beam.protocol.fountain.MemoryBlockStore
import com.madix.beam.protocol.frame.FrameCodec
import com.madix.beam.protocol.frame.PairingRequest
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.frame.TransferHeader
import com.madix.beam.protocol.session.ErrorCorrection
import com.madix.beam.protocol.session.ReceiverEngine
import com.madix.beam.protocol.session.ReceptionSnapshot
import com.madix.beam.protocol.session.WrongKeyException
import com.madix.beam.qr.QrRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build
import android.os.SystemClock
import com.madix.beam.protocol.frame.LinkInvitation
import com.madix.beam.protocol.link.LinkException
import com.madix.beam.protocol.link.LinkOffer
import com.madix.beam.protocol.link.LinkReceiver
import com.madix.beam.protocol.link.LinkSink
import com.madix.beam.protocol.link.OfferDecision
import com.madix.beam.protocol.link.OfferedEntry
import android.net.wifi.WifiManager
import com.madix.beam.wifi.GroupCredentials
import com.madix.beam.wifi.HotspotException
import com.madix.beam.wifi.LocalHotspot
import kotlinx.coroutines.delay
import com.madix.beam.wifi.LinkNetwork
import com.madix.beam.wifi.WifiDirectController
import com.madix.beam.wifi.WifiReadiness
import kotlinx.coroutines.isActive
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

/** One unpacked item, ready to be opened, shared or exported. */
data class ReceivedEntry(
    val file: File,
    val name: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val isText: Boolean,
    val textPreview: String?,
)

data class ReceiveResult(
    val recordId: String,
    val entries: List<ReceivedEntry>,
    val senderName: String,
    val totalBytes: Long,
    val durationMillis: Long,
    val mode: SecurityMode,
    val verificationCode: String?,
    val viaWifi: Boolean = false,
    val bytesPerSecond: Double = 0.0,
)

/** Live state of a direct Wi-Fi reception. */
data class LinkUi(
    val senderName: String = "",
    val verificationCode: String? = null,
    val entryName: String = "",
    val entryIndex: Int = 0,
    val entryCount: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = -1,
    val bytesPerSecond: Double = 0.0,
) {
    val fraction: Float
        get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

    val etaSeconds: Long?
        get() = if (bytesTotal > 0 && bytesPerSecond > 1.0) ((bytesTotal - bytesDone).coerceAtLeast(0) / bytesPerSecond).toLong() + 1 else null
}

/** Availability of direct Wi-Fi reception on this device. */
enum class WifiHostStatus { CHECKING, STARTING, READY, LAN_ONLY, PERMISSION_REQUIRED, WIFI_OFF, LOCATION_OFF, UNSUPPORTED, FAILED }

/** Why the receiver could not create its Wi-Fi network. */
enum class HostProblem { TETHERING_ACTIVE, HOTSPOT_DISALLOWED, NO_CHANNEL, GENERIC }

/** The Wi-Fi network created by the receiver, shown so that it can also be joined manually. */
data class HostedNetworkUi(val name: String, val password: String, val isHotspot: Boolean)

private class HostedNetwork(val name: String, val password: String, val kind: Int, val security: Int)

sealed interface ReceivePhase {
    /** Camera active, collecting frames. */
    data object Scanning : ReceivePhase

    /** All frames collected; waiting for the password. */
    data class NeedsPassword(val wrongAttempt: Boolean) : ReceivePhase

    /** Decrypting, verifying and writing files. */
    data class Opening(val fraction: Float) : ReceivePhase

    /** Receiving directly over Wi-Fi. */
    data class Linking(val link: LinkUi) : ReceivePhase

    data class Done(val result: ReceiveResult) : ReceivePhase

    data class Failed(val reason: ReceiveFailure) : ReceivePhase
}

enum class ReceiveFailure { INTEGRITY, STORAGE, CONNECTION, UNKNOWN }

data class ReceiveUiState(
    val phase: ReceivePhase = ReceivePhase.Scanning,
    val snapshot: ReceptionSnapshot = ReceptionSnapshot(),
    val verificationCode: String? = null,
    val pairingCodeSeen: Boolean = false,
    val foreignText: String? = null,
    val wifi: WifiHostStatus = WifiHostStatus.CHECKING,
    val network: HostedNetworkUi? = null,
    val wifiProblem: HostProblem? = null,
)

class ReceiveViewModel(private val container: AppContainer) : ViewModel() {

    /** Ephemeral key pair announced through the pairing code; never leaves memory. */
    private val keys: EphemeralKeyPair = EphemeralKeyPair.generate()

    val deviceName: String = container.settings.effectiveDeviceName(container.initialSettings)

    private val pairingImage = MutableStateFlow<ImageBitmap?>(null)
    val pairingCode: StateFlow<ImageBitmap?> = pairingImage.asStateFlow()

    private val uiState = MutableStateFlow(ReceiveUiState())
    val state: StateFlow<ReceiveUiState> = uiState.asStateFlow()

    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "madix-receiver").apply { isDaemon = true }
    }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val codes = Channel<String>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val dismissedForeign = HashSet<String>()

    private var engine = newEngine()
    private var openJob: Job? = null
    private var completedAt = 0L

    // Direct Wi-Fi reception.
    private val wifi = WifiDirectController(container.appContext)
    private val credentials = GroupCredentials.generate()
    private val invitationNonce = SecureRandom().nextInt()
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeLink: Socket? = null

    private val hotspot = LocalHotspot(container.appContext)

    @Volatile
    private var hosted: HostedNetwork? = null

    @Volatile
    private var hostingStarted = false

    init {
        startServer()
        hotspot.onStopped = {
            // The system stopped the hotspot (for example when tethering was enabled).
            hosted = null
            hostingStarted = false
            viewModelScope.launch { refreshWifi() }
        }
        renderInvitation()
        refreshWifi()
        viewModelScope.launch(engineDispatcher) { consumeCodes() }
    }

    /**
     * Re-evaluates Wi-Fi availability; called when the screen resumes or a permission changes.
     * The receiver first creates a local Wi-Fi hotspot (a real network the sender joins after
     * scanning the code) and falls back to a Wi-Fi Direct group when the hotspot is refused.
     */
    fun refreshWifi() {
        viewModelScope.launch {
            if (hostingStarted) {
                renderInvitation()
                return@launch
            }
            when (wifi.hostReadiness()) {
                WifiReadiness.READY -> Unit
                WifiReadiness.PERMISSION_REQUIRED -> return@launch reportHostUnavailable(WifiHostStatus.PERMISSION_REQUIRED)
                WifiReadiness.LOCATION_OFF -> return@launch reportHostUnavailable(WifiHostStatus.LOCATION_OFF)
                else -> return@launch reportHostUnavailable(WifiHostStatus.UNSUPPORTED)
            }
            hostingStarted = true
            uiState.update { it.copy(wifi = WifiHostStatus.STARTING, wifiProblem = null) }

            // 1. Local hotspot: a real Wi-Fi network created by this device.
            var hotspotProblem = HostProblem.GENERIC
            try {
                val info = hotspot.start()
                hosted = HostedNetwork(info.ssid, info.passphrase, LinkInvitation.KIND_HOTSPOT, info.security)
                onNetworkHosted()
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: HotspotException) {
                hotspotProblem = when (e.reason) {
                    WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> HostProblem.TETHERING_ACTIVE
                    WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> HostProblem.HOTSPOT_DISALLOWED
                    WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> HostProblem.NO_CHANNEL
                    else -> HostProblem.GENERIC
                }
            } catch (e: Exception) {
                hotspotProblem = HostProblem.GENERIC
            }

            // 2. Wi-Fi Direct group, when the platform supports explicit credentials.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && wifi.readiness() == WifiReadiness.READY) {
                try {
                    wifi.createGroup(credentials)
                    hosted = HostedNetwork(credentials.networkName, credentials.passphrase, LinkInvitation.KIND_WIFI_DIRECT, LinkInvitation.SECURITY_WPA2)
                    onNetworkHosted()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Reported below together with the hotspot problem.
                }
            }

            hostingStarted = false
            val hasLan = serverSocket != null && LinkNetwork.lanAddresses().isNotEmpty()
            uiState.update {
                it.copy(
                    wifi = if (hasLan) WifiHostStatus.LAN_ONLY else WifiHostStatus.FAILED,
                    wifiProblem = hotspotProblem,
                    network = null,
                )
            }
            renderInvitation()
        }
    }

    private fun reportHostUnavailable(status: WifiHostStatus) {
        val hasLan = serverSocket != null && LinkNetwork.lanAddresses().isNotEmpty()
        val effective = if (status == WifiHostStatus.UNSUPPORTED && hasLan) WifiHostStatus.LAN_ONLY else status
        uiState.update { it.copy(wifi = effective, network = null) }
        renderInvitation()
    }

    private suspend fun onNetworkHosted() {
        val network = hosted ?: return
        uiState.update {
            it.copy(
                wifi = WifiHostStatus.READY,
                wifiProblem = null,
                network = HostedNetworkUi(network.name, network.password, network.kind == LinkInvitation.KIND_HOTSPOT),
            )
        }
        // The new interface receives its address shortly after the network comes up.
        renderInvitation()
        delay(800)
        renderInvitation()
        delay(1500)
        renderInvitation()
    }

    /** Renders the code shown to senders: a Wi-Fi invitation, or a plain pairing code. */
    private fun renderInvitation() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val server = serverSocket
                val network = hosted
                val frame = if (server != null) {
                    val invitation = LinkInvitation(
                        nonce = invitationNonce,
                        publicKey = keys.publicKey,
                        deviceName = deviceName,
                        networkName = network?.name ?: "",
                        passphrase = network?.password ?: "",
                        port = server.localPort,
                        addresses = LinkNetwork.lanAddresses(includeDirect = true).map { it.address },
                        linkKind = network?.kind ?: LinkInvitation.KIND_NONE,
                        security = network?.security ?: LinkInvitation.SECURITY_WPA2,
                    )
                    FrameCodec.encodeInvite(invitation)
                } else {
                    FrameCodec.encodePairing(PairingRequest(invitationNonce, keys.publicKey, deviceName))
                }
                pairingImage.value = QrRenderer.render(FrameCodec.toQrText(frame), ErrorCorrection.M).asImageBitmap()
            }
        }
    }

    private fun startServer() {
        val server = runCatching { LinkNetwork.openServer() }.getOrNull() ?: return
        serverSocket = server
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                val phase = uiState.value.phase
                if (phase is ReceivePhase.Scanning || phase is ReceivePhase.Failed) {
                    handleLink(socket)
                } else {
                    runCatching { socket.close() }
                }
            }
        }
    }

    /** Receives one direct transfer; blocks the calling IO thread until it ends. */
    private fun handleLink(socket: Socket) {
        activeLink = socket
        val directory = container.files.createTransferDirectory()
        val files = HashMap<Int, File>()
        val previousPhase = uiState.value.phase
        var handshakeDone = false
        var link = LinkUi()
        var started = SystemClock.elapsedRealtime()
        var lastPublish = 0L
        try {
            LinkNetwork.tune(socket)
            socket.soTimeout = LINK_TIMEOUT_MILLIS
            val sink = object : LinkSink {
                override fun onOffer(offer: LinkOffer): OfferDecision {
                    val required = offer.knownBytes + STORAGE_MARGIN
                    if (directory.usableSpace in 0 until required) return OfferDecision.INSUFFICIENT_STORAGE
                    val total = if (offer.entries.all { it.sizeHint >= 0 }) offer.knownBytes else -1L
                    link = link.copy(senderName = offer.senderName, entryCount = offer.entries.size, bytesTotal = total)
                    started = SystemClock.elapsedRealtime()
                    uiState.update { it.copy(phase = ReceivePhase.Linking(link)) }
                    return OfferDecision.ACCEPT
                }

                override fun openEntry(index: Int, entry: OfferedEntry): OutputStream {
                    val name = if (entry.kind == EntryKind.TEXT && !entry.name.contains('.')) "${entry.name}.txt" else entry.name
                    val file = FileNames.unique(directory, name)
                    files[index] = file
                    return file.outputStream().buffered(256 * 1024)
                }
            }
            val result = LinkReceiver(keys, deviceName).receive(
                input = socket.getInputStream().buffered(256 * 1024),
                output = socket.getOutputStream().buffered(16 * 1024),
                sink = sink,
                listener = { progress ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPublish >= PROGRESS_INTERVAL_MILLIS) {
                        lastPublish = now
                        val seconds = (now - started).coerceAtLeast(1) / 1000.0
                        link = link.copy(
                            entryName = progress.entryName,
                            entryIndex = progress.entryIndex,
                            bytesDone = progress.bytesDone,
                            bytesPerSecond = progress.bytesDone / seconds,
                        )
                        uiState.update { it.copy(phase = ReceivePhase.Linking(link)) }
                    }
                },
                onVerificationCode = { code, sender ->
                    handshakeDone = true
                    link = link.copy(senderName = sender, verificationCode = code)
                    uiState.update { it.copy(phase = ReceivePhase.Linking(link)) }
                },
            )
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
            val rate = result.totalBytes / (elapsed / 1000.0)
            val entries = result.entries.mapIndexed { index, entry ->
                val file = files.getValue(index)
                val isText = entry.kind == EntryKind.TEXT
                ReceivedEntry(
                    file = file,
                    name = if (isText) entry.name else file.name,
                    mimeType = entry.mimeType,
                    size = entry.size,
                    sha256 = entry.sha256Hex,
                    isText = isText,
                    textPreview = if (isText && entry.size <= MAX_TEXT_PREVIEW) runCatching { file.readText() }.getOrNull() else null,
                )
            }
            val id = UUID.randomUUID().toString()
            val received = ReceiveResult(
                recordId = id,
                entries = entries,
                senderName = result.senderName,
                totalBytes = result.totalBytes,
                durationMillis = elapsed,
                mode = SecurityMode.PAIRED,
                verificationCode = result.verificationCode,
                viaWifi = true,
                bytesPerSecond = rate,
            )
            val record = TransferRecord(
                id = id,
                direction = TransferDirection.RECEIVED,
                timestamp = System.currentTimeMillis(),
                items = entries.map {
                    HistoryItem(
                        name = it.name,
                        mimeType = it.mimeType,
                        size = it.size,
                        relativePath = container.files.relativePath(it.file),
                        sha256 = it.sha256,
                        isText = it.isText,
                    )
                },
                totalBytes = result.totalBytes,
                sealedBytes = result.totalBytes,
                durationMillis = elapsed,
                security = SecurityMode.PAIRED.name,
                peerName = result.senderName.ifBlank { null },
                verificationCode = result.verificationCode,
                method = METHOD_WIFI,
            )
            container.applicationScope.launch { runCatching { container.history.add(record) } }
            uiState.update { it.copy(phase = ReceivePhase.Done(received)) }
        } catch (e: Exception) {
            directory.deleteRecursively()
            if (!handshakeDone) {
                // Not a Madix Beam peer or an aborted attempt: keep the previous state.
                uiState.update { it.copy(phase = previousPhase) }
            } else {
                val reason = when (e) {
                    is LinkException -> when (e.reason) {
                        LinkException.Reason.INSUFFICIENT_STORAGE -> ReceiveFailure.STORAGE
                        LinkException.Reason.INTEGRITY -> ReceiveFailure.INTEGRITY
                        else -> ReceiveFailure.CONNECTION
                    }
                    is IOException -> ReceiveFailure.CONNECTION
                    else -> ReceiveFailure.UNKNOWN
                }
                uiState.update { it.copy(phase = ReceivePhase.Failed(reason)) }
            }
        } finally {
            runCatching { socket.close() }
            activeLink = null
        }
    }

    private fun newEngine() = ReceiverEngine(keys, ::createStore)

    private fun createStore(header: TransferHeader): BlockStore =
        if (header.cipherLength <= IN_MEMORY_LIMIT) {
            MemoryBlockStore(header.totalBlocks, header.blockSize)
        } else {
            val file = File(container.files.incomingWorkDir, "session-${Integer.toHexString(header.sessionId)}.bin")
            FileBlockStore(file, header.totalBlocks, header.blockSize)
        }

    /** Called on the camera thread with decoded QR texts. */
    fun onCodes(texts: List<String>) {
        for (text in texts) codes.trySend(text)
    }

    private suspend fun consumeCodes() {
        var last: String? = null
        var lastPublish = 0L
        for (text in codes) {
            if (uiState.value.phase != ReceivePhase.Scanning) continue
            if (text == last) continue
            last = text
            val event = runCatching { engine.ingest(text) }.getOrElse { ReceiverEngine.Event.Corrupted }
            val now = System.nanoTime()
            when (event) {
                is ReceiverEngine.Event.Foreign -> {
                    if (!FrameCodec.isMadixText(text) && event.text !in dismissedForeign && uiState.value.foreignText == null) {
                        uiState.update { it.copy(foreignText = event.text) }
                    }
                }
                ReceiverEngine.Event.PairingCode -> uiState.update { it.copy(pairingCodeSeen = true) }
                is ReceiverEngine.Event.Locked -> {
                    publish(pairingSeen = false)
                    lastPublish = now
                }
                ReceiverEngine.Event.Completed -> {
                    completedAt = System.currentTimeMillis()
                    publish(pairingSeen = false)
                    onComplete()
                }
                else -> if (now - lastPublish > PUBLISH_INTERVAL_NANOS) {
                    publish(pairingSeen = null)
                    lastPublish = now
                }
            }
        }
    }

    private fun publish(pairingSeen: Boolean?) {
        val snapshot = engine.snapshot()
        val code = engine.shortAuthString()
        uiState.update {
            it.copy(
                snapshot = snapshot,
                verificationCode = code,
                pairingCodeSeen = pairingSeen ?: it.pairingCodeSeen,
            )
        }
    }

    private fun onComplete() {
        val header = engine.header ?: return
        if (header.mode == SecurityMode.PASSWORD) {
            uiState.update { it.copy(phase = ReceivePhase.NeedsPassword(wrongAttempt = false)) }
        } else {
            open(null)
        }
    }

    fun dismissForeign() {
        val text = uiState.value.foreignText ?: return
        viewModelScope.launch(engineDispatcher) { dismissedForeign += text }
        uiState.update { it.copy(foreignText = null) }
    }

    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        open(password.toCharArray())
    }

    /** Discards the collected frames and starts listening for a new transfer. */
    fun restart() {
        openJob?.cancel()
        viewModelScope.launch(engineDispatcher) {
            runCatching { engine.close() }
            container.files.incomingWorkDir.listFiles()?.forEach { it.delete() }
            engine = newEngine()
            uiState.update { ReceiveUiState(wifi = it.wifi, network = it.network, wifiProblem = it.wifiProblem) }
        }
    }

    private fun open(password: CharArray?) {
        if (openJob?.isActive == true) return
        uiState.update { it.copy(phase = ReceivePhase.Opening(0f)) }
        openJob = viewModelScope.launch(engineDispatcher) {
            var key = ByteArray(0)
            try {
                val opener = engine.opener()
                val header = opener.header
                key = try {
                    opener.deriveKey(password)
                } finally {
                    password?.fill('\u0000')
                }
                if (!opener.verifyKey(key)) {
                    if (header.mode == SecurityMode.PASSWORD) {
                        uiState.update { it.copy(phase = ReceivePhase.NeedsPassword(wrongAttempt = true)) }
                    } else {
                        uiState.update { it.copy(phase = ReceivePhase.Failed(ReceiveFailure.INTEGRITY)) }
                    }
                    return@launch
                }
                val directory = container.files.createTransferDirectory()
                val sink = DirectorySink(directory)
                var lastFraction = 0f
                val manifest = try {
                    opener.open(key, sink, ProgressListener { done, total ->
                        val fraction = if (total > 0) done.toFloat() / total else 1f
                        if (fraction - lastFraction >= 0.01f) {
                            lastFraction = fraction
                            uiState.update { it.copy(phase = ReceivePhase.Opening(fraction)) }
                        }
                    })
                } catch (e: Throwable) {
                    directory.deleteRecursively()
                    throw e
                }
                val result = buildResult(manifest, sink, header)
                withContext(Dispatchers.IO) { runCatching { container.history.add(result.second) } }
                runCatching { engine.close() }
                container.files.incomingWorkDir.listFiles()?.forEach { it.delete() }
                uiState.update { it.copy(phase = ReceivePhase.Done(result.first)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WrongKeyException) {
                uiState.update { it.copy(phase = ReceivePhase.NeedsPassword(wrongAttempt = true)) }
            } catch (e: InsufficientStorageException) {
                uiState.update { it.copy(phase = ReceivePhase.Failed(ReceiveFailure.STORAGE)) }
            } catch (e: AuthenticationException) {
                uiState.update { it.copy(phase = ReceivePhase.Failed(ReceiveFailure.INTEGRITY)) }
            } catch (e: com.madix.beam.protocol.ProtocolException) {
                uiState.update { it.copy(phase = ReceivePhase.Failed(ReceiveFailure.INTEGRITY)) }
            } catch (e: IOException) {
                uiState.update { it.copy(phase = ReceivePhase.Failed(ReceiveFailure.STORAGE)) }
            } catch (e: Exception) {
                uiState.update { it.copy(phase = ReceivePhase.Failed(ReceiveFailure.UNKNOWN)) }
            } finally {
                key.fill(0)
            }
        }
    }

    private fun buildResult(manifest: ContainerManifest, sink: DirectorySink, header: TransferHeader): Pair<ReceiveResult, TransferRecord> {
        val entries = manifest.entries.mapIndexed { index, entry ->
            val file = sink.files.getValue(index)
            val isText = entry.kind == EntryKind.TEXT
            ReceivedEntry(
                file = file,
                name = if (isText) entry.name else file.name,
                mimeType = entry.mimeType,
                size = entry.size,
                sha256 = entry.sha256Hex,
                isText = isText,
                textPreview = if (isText && entry.size <= MAX_TEXT_PREVIEW) runCatching { file.readText() }.getOrNull() else null,
            )
        }
        val snapshot = uiState.value.snapshot
        val id = UUID.randomUUID().toString()
        val code = engine.shortAuthString()
        val result = ReceiveResult(
            recordId = id,
            entries = entries,
            senderName = manifest.senderName,
            totalBytes = manifest.totalBytes,
            durationMillis = snapshot.elapsedMillis,
            mode = header.mode,
            verificationCode = code,
        )
        val record = TransferRecord(
            id = id,
            direction = TransferDirection.RECEIVED,
            timestamp = System.currentTimeMillis(),
            items = entries.map {
                HistoryItem(
                    name = it.name,
                    mimeType = it.mimeType,
                    size = it.size,
                    relativePath = container.files.relativePath(it.file),
                    sha256 = it.sha256,
                    isText = it.isText,
                )
            },
            totalBytes = manifest.totalBytes,
            sealedBytes = header.cipherLength,
            durationMillis = snapshot.elapsedMillis,
            security = header.mode.name,
            peerName = manifest.senderName.ifBlank { null },
            verificationCode = code,
            frames = snapshot.framesDecoded,
            method = METHOD_QR,
        )
        return result to record
    }

    /** Writes unpacked entries into a fresh transfer directory with collision-free names. */
    private class DirectorySink(private val directory: File) : ContainerSink {
        val files = HashMap<Int, File>()

        override fun onManifest(manifest: ContainerManifest) {
            val required = manifest.totalBytes + STORAGE_MARGIN
            if (directory.usableSpace in 0 until required) throw InsufficientStorageException()
        }

        override fun openEntry(index: Int, entry: EntryInfo): OutputStream {
            val name = if (entry.kind == EntryKind.TEXT && !entry.name.contains('.')) "${entry.name}.txt" else entry.name
            val file = FileNames.unique(directory, name)
            files[index] = file
            return file.outputStream().buffered(64 * 1024)
        }
    }

    private class InsufficientStorageException : IOException("Insufficient storage")

    override fun onCleared() {
        openJob?.cancel()
        codes.close()
        runCatching { serverSocket?.close() }
        runCatching { activeLink?.close() }
        hotspot.onStopped = null
        hotspot.stop()
        val controller = wifi
        container.applicationScope.launch {
            runCatching { controller.removeGroup() }
            controller.close()
        }
        val current = engine
        engineExecutor.execute {
            runCatching { current.close() }
        }
        engineExecutor.shutdown()
        super.onCleared()
    }

    private companion object {
        const val IN_MEMORY_LIMIT = 24L * 1024 * 1024
        const val PUBLISH_INTERVAL_NANOS = 120_000_000L
        const val MAX_TEXT_PREVIEW = 256L * 1024
        const val STORAGE_MARGIN = 16L * 1024 * 1024
        const val LINK_TIMEOUT_MILLIS = 30_000
        const val PROGRESS_INTERVAL_MILLIS = 100L
        const val METHOD_WIFI = "WIFI"
        const val METHOD_QR = "QR"
    }
}
