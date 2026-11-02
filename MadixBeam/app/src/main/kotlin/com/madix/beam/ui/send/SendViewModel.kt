package com.madix.beam.ui.send

import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.madix.beam.core.AppContainer
import com.madix.beam.core.IncomingShare
import com.madix.beam.data.history.HistoryItem
import com.madix.beam.data.history.TransferDirection
import com.madix.beam.data.history.TransferRecord
import com.madix.beam.data.settings.ImageQuality
import com.madix.beam.data.source.PickedItem
import com.madix.beam.data.source.SourceResolver
import com.madix.beam.protocol.Protocol
import com.madix.beam.protocol.ProtocolException
import com.madix.beam.protocol.container.EntryKind
import com.madix.beam.protocol.container.PayloadTooLargeException
import com.madix.beam.protocol.crypto.EphemeralKeyPair
import com.madix.beam.protocol.frame.Frame
import com.madix.beam.protocol.frame.FrameCodec
import com.madix.beam.protocol.frame.PairingRequest
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.session.PrepareStage
import com.madix.beam.protocol.session.PreparedTransfer
import com.madix.beam.protocol.session.SecurityParams
import com.madix.beam.protocol.session.TransferPreparer
import com.madix.beam.protocol.session.TransferProfile
import com.madix.beam.qr.QrRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build
import com.madix.beam.data.settings.TransferMethod
import com.madix.beam.protocol.frame.LinkInvitation
import com.madix.beam.protocol.link.LinkException
import com.madix.beam.protocol.link.LinkSender
import com.madix.beam.wifi.GroupCredentials
import com.madix.beam.wifi.LinkNetwork
import com.madix.beam.wifi.WifiDirectController
import com.madix.beam.wifi.WifiDirectException
import com.madix.beam.wifi.WifiReadiness
import java.io.FileNotFoundException
import java.net.InetAddress
import java.net.Socket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed interface SendPhase {
    data object Composing : SendPhase
    data class Preparing(val stage: PrepareStage, val fraction: Float) : SendPhase
    data object Broadcasting : SendPhase
    data class Failed(val reason: SendFailure) : SendPhase
}

enum class SendFailure { TOO_LARGE, UNREADABLE, INVALID_PAIRING, STORAGE, UNKNOWN }

data class SendUiState(
    val items: List<PickedItem> = emptyList(),
    val method: TransferMethod = TransferMethod.WIFI,
    /** Set when a Wi-Fi transfer had to continue with QR codes. */
    val fellBackToQr: Boolean = false,
    val security: SecurityMode = SecurityMode.PAIRED,
    val password: String = "",
    val profile: TransferProfile = TransferProfile.DEFAULT,
    val imageQuality: ImageQuality = ImageQuality.INSTANT,
    val phase: SendPhase = SendPhase.Composing,
    val loadingItems: Boolean = false,
) {
    val totalBytes: Long get() = items.sumOf { it.size.coerceAtLeast(0) }
    val hasImages: Boolean get() = items.any { it.isImage }
    val passwordValid: Boolean get() = password.length >= MIN_PASSWORD_LENGTH
    val tooLarge: Boolean get() = method == TransferMethod.QR && totalBytes > Protocol.MAX_PAYLOAD_BYTES
    val canContinue: Boolean
        get() = items.isNotEmpty() && !tooLarge && !loadingItems &&
            (method == TransferMethod.WIFI || security != SecurityMode.PASSWORD || passwordValid)

    /** Rough duration estimate before compression and photo optimisation. */
    fun estimatedSeconds(): Long = if (method == TransferMethod.WIFI) {
        (totalBytes / WIFI_ESTIMATE_BYTES_PER_SECOND).coerceAtLeast(1)
    } else {
        profile.estimateSeconds(totalBytes + 2048)
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 6

        /** Conservative Wi-Fi Direct throughput used for estimates. */
        const val WIFI_ESTIMATE_BYTES_PER_SECOND = 20L * 1024 * 1024
    }
}

data class BroadcastUi(
    /** QR codes currently on screen; [TransferProfile.codesPerFrame] entries per tick. */
    val frames: List<ImageBitmap> = emptyList(),
    val framesShown: Long = 0,
    val passes: Int = 0,
    val passFraction: Float = 0f,
    val fps: Int = TransferProfile.DEFAULT.defaultFps,
    val paused: Boolean = false,
    val elapsedMillis: Long = 0,
    val verificationCode: String? = null,
    val peerName: String? = null,
    val sealedBytes: Long = 0,
    val payloadBytes: Long = 0,
    val totalBlocks: Int = 0,
    val itemCount: Int = 0,
    val mode: SecurityMode = SecurityMode.OPEN,
    val compressed: Boolean = false,
    val profile: TransferProfile = TransferProfile.DEFAULT,
) {
    /** Seconds needed for one complete pass at the current frame rate. */
    val passSeconds: Long
        get() {
            if (totalBlocks == 0) return 0
            val codes = totalBlocks * Protocol.REPAIR_PERIOD / (Protocol.REPAIR_PERIOD - 1.0) /
                (1.0 - 1.0 / Protocol.HEADER_INTERVAL)
            val ticks = codes / profile.codesPerFrame
            return (ticks / fps.coerceAtLeast(1)).toLong() + 1
        }
}

/** Pairing scan outcome used for user feedback. */
enum class PairingScanResult { ACCEPTED, NOT_PAIRING, INVALID }

enum class ConnectStep { CONNECTING, JOINING, SECURING }

enum class WifiFailure { UNSUPPORTED, PERMISSION, WIFI_OFF, LOCATION_OFF, UNREACHABLE, BUSY, DECLINED, STORAGE, INTEGRITY, INTERRUPTED, UNREADABLE }

sealed interface WifiPhase {
    data object Idle : WifiPhase
    data class Connecting(val step: ConnectStep) : WifiPhase
    data class Sending(
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Double,
        val entryName: String,
        val entryIndex: Int,
        val entryCount: Int,
    ) : WifiPhase

    data class Completed(val totalBytes: Long, val durationMillis: Long, val bytesPerSecond: Double, val itemCount: Int) : WifiPhase
    data class Failed(val reason: WifiFailure) : WifiPhase
}

data class WifiSendUi(
    val phase: WifiPhase = WifiPhase.Idle,
    val peerName: String? = null,
    val verificationCode: String? = null,
)

/** A requirement for the direct Wi-Fi link is not met. */
private class WifiUnavailable(val reason: WifiFailure) : Exception(reason.name)

class SendViewModel(private val container: AppContainer) : ViewModel() {

    private val uiState = MutableStateFlow(
        SendUiState(
            method = container.initialSettings.transferMethod,
            security = container.initialSettings.defaultSecurity,
            profile = container.initialSettings.profile,
            imageQuality = container.initialSettings.imageQuality,
        ),
    )
    val state: StateFlow<SendUiState> = uiState.asStateFlow()

    private val broadcastState = MutableStateFlow(BroadcastUi())
    val broadcast: StateFlow<BroadcastUi> = broadcastState.asStateFlow()

    private val wifiState = MutableStateFlow(WifiSendUi())
    val wifi: StateFlow<WifiSendUi> = wifiState.asStateFlow()

    private val wifiController by lazy { WifiDirectController(container.appContext) }

    @Volatile
    private var wifiJob: Job? = null

    @Volatile
    private var wifiSocket: Socket? = null

    @Volatile
    private var lastInvitation: LinkInvitation? = null

    private val paused = MutableStateFlow(false)
    private val fps = MutableStateFlow(TransferProfile.DEFAULT.defaultFps)
    private val pairingAccepted = AtomicBoolean(false)

    @Volatile
    private var prepared: PreparedTransfer? = null

    @Volatile
    private var prepareJob: Job? = null

    @Volatile
    private var broadcastJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = container.settings.current()
            uiState.update {
                if (it.phase == SendPhase.Composing && it.items.isEmpty()) {
                    it.copy(
                        method = settings.transferMethod,
                        security = settings.defaultSecurity,
                        profile = settings.profile,
                        imageQuality = settings.imageQuality,
                    )
                } else {
                    it
                }
            }
        }
    }

    // region Composition

    fun applyShare(share: IncomingShare) {
        if (share.uris.isNotEmpty()) addUris(share.uris)
        share.text?.takeIf { it.isNotBlank() }?.let { addText(it) }
    }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uiState.update { it.copy(loadingItems = true) }
        viewModelScope.launch {
            val described = withContext(Dispatchers.IO) {
                uris.distinct().mapNotNull { uri ->
                    runCatching { SourceResolver.describe(container.appContext.contentResolver, uri) }.getOrNull()
                }
            }
            uiState.update { current ->
                val known = current.items.mapNotNull { it.uri }.toSet()
                val fresh = described.filter { it.uri !in known }
                current.copy(items = (current.items + fresh).take(Protocol.MAX_ENTRIES), loadingItems = false)
            }
        }
    }

    fun addText(text: String, name: String = "message.txt") {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        uiState.update { it.copy(items = it.items + SourceResolver.textItem(trimmed, name)) }
    }

    fun remove(id: Long) = uiState.update { state -> state.copy(items = state.items.filterNot { it.id == id }) }

    fun clearItems() = uiState.update { it.copy(items = emptyList()) }

    fun setSecurity(mode: SecurityMode) = uiState.update { it.copy(security = mode) }

    fun setPassword(value: String) = uiState.update { it.copy(password = value.take(128)) }

    fun setProfile(profile: TransferProfile) = uiState.update { it.copy(profile = profile) }

    fun setImageQuality(quality: ImageQuality) = uiState.update { it.copy(imageQuality = quality) }

    fun dismissFailure() = uiState.update { it.copy(phase = SendPhase.Composing) }

    fun setMethod(method: TransferMethod) = uiState.update { it.copy(method = method) }

    // endregion

    // region Pairing

    fun resetPairing() = pairingAccepted.set(false)

    /** Called from the camera thread with decoded QR texts. */
    fun onPairingCodes(texts: List<String>): PairingScanResult? {
        if (pairingAccepted.get()) return null
        var result: PairingScanResult? = null
        for (text in texts) {
            val frame = try {
                FrameCodec.parseQrText(text)
            } catch (e: ProtocolException) {
                result = PairingScanResult.INVALID
                continue
            }
            val request = when (frame) {
                is Frame.Invite -> frame.invitation.toPairingRequest()
                is Frame.Pairing -> frame.request
                else -> null
            }
            if (request == null) {
                result = PairingScanResult.NOT_PAIRING
                continue
            }
            if (!EphemeralKeyPair.isValidPublicKey(request.publicKey)) {
                result = PairingScanResult.INVALID
                continue
            }
            if (!pairingAccepted.compareAndSet(false, true)) return null
            val invitation = (frame as? Frame.Invite)?.invitation
            viewModelScope.launch {
                when {
                    uiState.value.method == TransferMethod.WIFI && invitation != null -> beginWifiTransfer(invitation)
                    uiState.value.method == TransferMethod.WIFI -> {
                        // The receiver cannot accept direct transfers; continue optically.
                        uiState.update { it.copy(security = SecurityMode.PAIRED, fellBackToQr = true) }
                        beginTransfer(request)
                    }
                    else -> beginTransfer(request)
                }
            }
            return PairingScanResult.ACCEPTED
        }
        return result
    }

    // endregion

    // region Direct Wi-Fi transfer

    /** Connects to the receiver described by [invitation] and streams the selected items. */
    fun beginWifiTransfer(invitation: LinkInvitation) {
        if (wifiJob?.isActive == true) return
        lastInvitation = invitation
        val snapshot = uiState.value
        wifiState.value = WifiSendUi(phase = WifiPhase.Connecting(ConnectStep.CONNECTING), peerName = invitation.deviceName)
        wifiJob = viewModelScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            var joinedGroup = false
            var stage = WifiFailure.UNREACHABLE
            try {
                socket = connectToReceiver(invitation) { joinedGroup = true }
                wifiSocket = socket
                stage = WifiFailure.INTERRUPTED
                socket.soTimeout = LINK_TIMEOUT_MILLIS
                wifiState.update { it.copy(phase = WifiPhase.Connecting(ConnectStep.SECURING)) }
                val settings = container.settings.current()
                val sources = snapshot.items.map { SourceResolver.toSource(container.appContext, it, ImageQuality.ORIGINAL) }
                var sendStart = 0L
                var lastPublish = 0L
                val result = LinkSender(invitation.publicKey, container.settings.effectiveDeviceName(settings)).send(
                    input = socket.getInputStream().buffered(16 * 1024),
                    output = socket.getOutputStream().buffered(256 * 1024),
                    sources = sources,
                    listener = { progress ->
                        val now = SystemClock.elapsedRealtime()
                        if (sendStart == 0L) sendStart = now
                        if (now - lastPublish >= PROGRESS_INTERVAL_MILLIS) {
                            lastPublish = now
                            val seconds = (now - sendStart).coerceAtLeast(1) / 1000.0
                            wifiState.update {
                                it.copy(
                                    phase = WifiPhase.Sending(
                                        bytesDone = progress.bytesDone,
                                        bytesTotal = progress.totalBytes,
                                        bytesPerSecond = progress.bytesDone / seconds,
                                        entryName = progress.entryName,
                                        entryIndex = progress.entryIndex,
                                        entryCount = progress.entryCount,
                                    ),
                                )
                            }
                        }
                    },
                    onHandshake = { code, receiverName ->
                        sendStart = SystemClock.elapsedRealtime()
                        wifiState.update {
                            it.copy(
                                verificationCode = code,
                                peerName = receiverName.ifBlank { invitation.deviceName },
                                phase = WifiPhase.Sending(0, sources.sumOf { s -> s.sizeHint }.takeIf { sources.all { s -> s.sizeHint >= 0 } } ?: -1L, 0.0, "", 0, sources.size),
                            )
                        }
                    },
                )
                val duration = (SystemClock.elapsedRealtime() - sendStart).coerceAtLeast(1)
                val rate = result.totalBytes / (duration / 1000.0)
                runCatching {
                    container.history.add(
                        TransferRecord(
                            id = UUID.randomUUID().toString(),
                            direction = TransferDirection.SENT,
                            timestamp = System.currentTimeMillis(),
                            items = result.entries.map {
                                HistoryItem(name = it.name, mimeType = it.mimeType, size = it.size, sha256 = it.sha256Hex, isText = it.kind == EntryKind.TEXT)
                            },
                            totalBytes = result.totalBytes,
                            sealedBytes = result.totalBytes,
                            durationMillis = duration,
                            security = SecurityMode.PAIRED.name,
                            peerName = result.receiverName.ifBlank { invitation.deviceName },
                            verificationCode = result.verificationCode,
                            method = TransferMethod.WIFI.name,
                        ),
                    )
                }
                wifiState.update {
                    it.copy(phase = WifiPhase.Completed(result.totalBytes, duration, rate, result.entries.size))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WifiUnavailable) {
                wifiState.update { it.copy(phase = WifiPhase.Failed(e.reason)) }
            } catch (e: LinkException) {
                val reason = when (e.reason) {
                    LinkException.Reason.BUSY -> WifiFailure.BUSY
                    LinkException.Reason.DECLINED -> WifiFailure.DECLINED
                    LinkException.Reason.INSUFFICIENT_STORAGE -> WifiFailure.STORAGE
                    LinkException.Reason.INTEGRITY -> WifiFailure.INTEGRITY
                    else -> WifiFailure.INTERRUPTED
                }
                wifiState.update { it.copy(phase = WifiPhase.Failed(reason)) }
            } catch (e: FileNotFoundException) {
                wifiState.update { it.copy(phase = WifiPhase.Failed(WifiFailure.UNREADABLE)) }
            } catch (e: SecurityException) {
                wifiState.update { it.copy(phase = WifiPhase.Failed(WifiFailure.UNREADABLE)) }
            } catch (e: Exception) {
                wifiState.update { it.copy(phase = WifiPhase.Failed(stage)) }
            } finally {
                runCatching { socket?.close() }
                wifiSocket = null
                if (joinedGroup) {
                    withContext(NonCancellable) { runCatching { wifiController.removeGroup() } }
                }
            }
        }
    }

    /**
     * Opens a connection to the receiver: first over a shared Wi-Fi network (instant), then by
     * joining the receiver's Wi-Fi Direct group with the credentials from its invitation.
     */
    private suspend fun connectToReceiver(invitation: LinkInvitation, onJoined: () -> Unit): Socket {
        val wifiNetwork = LinkNetwork.wifiNetwork(container.appContext)
        for (raw in invitation.addresses) {
            val address = InetAddress.getByAddress(raw)
            try {
                return LinkNetwork.connect(address, invitation.port, LAN_CONNECT_TIMEOUT_MILLIS, wifiNetwork)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not on the same network; try the next route.
            }
        }
        if (!invitation.hasDirectGroup) throw WifiUnavailable(WifiFailure.UNREACHABLE)
        when (wifiController.readiness()) {
            WifiReadiness.READY -> Unit
            WifiReadiness.UNSUPPORTED -> throw WifiUnavailable(WifiFailure.UNSUPPORTED)
            WifiReadiness.PERMISSION_REQUIRED -> throw WifiUnavailable(WifiFailure.PERMISSION)
            WifiReadiness.WIFI_OFF -> throw WifiUnavailable(WifiFailure.WIFI_OFF)
            WifiReadiness.LOCATION_OFF -> throw WifiUnavailable(WifiFailure.LOCATION_OFF)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw WifiUnavailable(WifiFailure.UNSUPPORTED)
        wifiState.update { it.copy(phase = WifiPhase.Connecting(ConnectStep.JOINING)) }
        val owner = try {
            wifiController.joinGroup(GroupCredentials(invitation.networkName, invitation.passphrase))
        } catch (e: WifiDirectException) {
            throw WifiUnavailable(WifiFailure.UNREACHABLE)
        }
        onJoined()
        wifiState.update { it.copy(phase = WifiPhase.Connecting(ConnectStep.CONNECTING)) }
        val deadline = SystemClock.elapsedRealtime() + GROUP_CONNECT_WINDOW_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                return LinkNetwork.connect(owner, invitation.port, GROUP_CONNECT_TIMEOUT_MILLIS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(500)
            }
        }
        throw WifiUnavailable(WifiFailure.UNREACHABLE)
    }

    fun retryWifi() {
        val invitation = lastInvitation ?: return
        if (wifiJob?.isActive == true) return
        beginWifiTransfer(invitation)
    }

    /** Abandons the Wi-Fi attempt and transmits the same items through QR codes instead. */
    fun fallBackToQr() {
        val invitation = lastInvitation ?: return
        cancelWifi()
        uiState.update { it.copy(security = SecurityMode.PAIRED, fellBackToQr = true) }
        pairingAccepted.set(true)
        beginTransfer(invitation.toPairingRequest())
    }

    /** Stops a running Wi-Fi transfer and returns to the composer. */
    fun cancelWifi() {
        wifiJob?.cancel()
        runCatching { wifiSocket?.close() }
        wifiState.value = WifiSendUi()
        pairingAccepted.set(false)
    }

    /** Clears the composer after a delivered Wi-Fi transfer. */
    fun finishWifi(onDone: () -> Unit) {
        wifiState.value = WifiSendUi()
        pairingAccepted.set(false)
        uiState.update { it.copy(items = emptyList(), password = "", fellBackToQr = false, phase = SendPhase.Composing) }
        onDone()
    }

    // endregion

    // region Preparation and broadcast

    /** Seals the selected items and starts broadcasting. [pairing] is required in paired mode. */
    fun beginTransfer(pairing: PairingRequest? = null) {
        if (prepareJob?.isActive == true) return
        val snapshot = uiState.value
        val security: SecurityParams = when (snapshot.security) {
            SecurityMode.OPEN -> SecurityParams.Open
            SecurityMode.PASSWORD -> SecurityParams.Password(snapshot.password.toCharArray())
            SecurityMode.PAIRED -> SecurityParams.Paired(pairing ?: return)
        }
        stopBroadcast()
        releasePrepared()
        uiState.update { it.copy(phase = SendPhase.Preparing(PrepareStage.PACKAGING, -1f)) }

        prepareJob = viewModelScope.launch(Dispatchers.IO) {
            var lastStage: PrepareStage? = null
            var lastFraction = -2f
            try {
                val settings = container.settings.current()
                val sources = snapshot.items.map { SourceResolver.toSource(container.appContext, it, snapshot.imageQuality) }
                val result = TransferPreparer(container.files.outgoingWorkDir).prepare(
                    sources = sources,
                    senderName = container.settings.effectiveDeviceName(settings),
                    security = security,
                    profile = snapshot.profile,
                    isCancelled = { !isActive },
                ) { stage, fraction ->
                    if (stage != lastStage || fraction < 0f || fraction - lastFraction >= 0.01f) {
                        lastStage = stage
                        lastFraction = fraction
                        uiState.update { it.copy(phase = SendPhase.Preparing(stage, fraction)) }
                    }
                }
                if (!isActive) {
                    result.close()
                    return@launch
                }
                prepared = result
                startBroadcast(result)
                uiState.update { it.copy(phase = SendPhase.Broadcasting) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                uiState.update { it.copy(phase = SendPhase.Composing) }
            } catch (e: Throwable) {
                uiState.update { it.copy(phase = SendPhase.Failed(classify(e))) }
            } finally {
                (security as? SecurityParams.Password)?.wipe()
            }
        }
    }

    private fun classify(error: Throwable): SendFailure = when (error) {
        is PayloadTooLargeException -> SendFailure.TOO_LARGE
        is FileNotFoundException, is SecurityException -> SendFailure.UNREADABLE
        is ProtocolException -> SendFailure.INVALID_PAIRING
        is IOException -> if (error.message?.contains("ENOSPC") == true) SendFailure.STORAGE else SendFailure.UNREADABLE
        else -> SendFailure.UNKNOWN
    }

    private fun startBroadcast(transfer: PreparedTransfer) {
        val scheduler = transfer.openScheduler()
        val profile = transfer.profile
        fps.value = profile.defaultFps
        paused.value = false
        broadcastState.value = BroadcastUi(
            fps = profile.defaultFps,
            verificationCode = transfer.shortAuthString,
            peerName = transfer.peerName,
            sealedBytes = transfer.sealedLength,
            payloadBytes = transfer.manifest.totalBytes,
            totalBlocks = transfer.header.totalBlocks,
            itemCount = transfer.manifest.entries.size,
            mode = transfer.header.mode,
            compressed = transfer.compressed,
            profile = profile,
        )
        broadcastJob = viewModelScope.launch(Dispatchers.Default) {
            val codesPerTick = profile.codesPerFrame
            val frames = Channel<List<ImageBitmap>>(capacity = 4)
            val producer = launch {
                try {
                    while (isActive) {
                        val tick = List(codesPerTick) {
                            QrRenderer.render(scheduler.nextQrText(), profile.errorCorrection).asImageBitmap()
                        }
                        frames.send(tick)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    frames.close(e)
                }
            }
            var activeMillis = 0L
            var last = SystemClock.elapsedRealtime()
            var deadline = System.nanoTime()
            var shown = 0L
            try {
                for (frame in frames) {
                    if (paused.value) {
                        broadcastState.update { it.copy(paused = true) }
                        paused.first { !it }
                        last = SystemClock.elapsedRealtime()
                        deadline = System.nanoTime()
                    }
                    val now = SystemClock.elapsedRealtime()
                    activeMillis += now - last
                    last = now
                    shown += frame.size
                    val rate = fps.value
                    broadcastState.update {
                        it.copy(
                            frames = frame,
                            framesShown = shown,
                            passes = scheduler.passes,
                            passFraction = scheduler.passFraction,
                            elapsedMillis = activeMillis,
                            fps = rate,
                            paused = false,
                        )
                    }
                    deadline += 1_000_000_000L / rate
                    val wait = deadline - System.nanoTime()
                    if (wait > 0) delay(wait / 1_000_000) else deadline = System.nanoTime()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.update { it.copy(phase = SendPhase.Failed(SendFailure.UNREADABLE)) }
            } finally {
                withContext(NonCancellable) {
                    producer.cancelAndJoin()
                    scheduler.close()
                }
            }
        }
    }

    fun togglePause() {
        val next = !paused.value
        paused.value = next
        broadcastState.update { it.copy(paused = next) }
    }

    fun setFps(value: Int) {
        val clamped = value.coerceIn(TransferProfile.MIN_FPS, TransferProfile.MAX_FPS)
        fps.value = clamped
        broadcastState.update { it.copy(fps = clamped) }
    }

    /** Stops broadcasting and returns to the composer, keeping the selected items. */
    fun cancelTransfer() {
        prepareJob?.cancel()
        stopBroadcast()
        releasePrepared()
        pairingAccepted.set(false)
        broadcastState.value = BroadcastUi()
        uiState.update { it.copy(phase = SendPhase.Composing) }
    }

    /** Ends a successful broadcast, records it in the history and clears the composer. */
    fun finish(onDone: () -> Unit) {
        val transfer = prepared
        val snapshot = broadcastState.value
        viewModelScope.launch {
            stopBroadcast()
            if (transfer != null && snapshot.framesShown > 0) {
                runCatching {
                    container.history.add(
                        TransferRecord(
                            id = UUID.randomUUID().toString(),
                            direction = TransferDirection.SENT,
                            timestamp = System.currentTimeMillis(),
                            items = transfer.manifest.entries.map {
                                HistoryItem(
                                    name = it.name,
                                    mimeType = it.mimeType,
                                    size = it.size,
                                    sha256 = it.sha256Hex,
                                    isText = it.kind == EntryKind.TEXT,
                                )
                            },
                            totalBytes = transfer.manifest.totalBytes,
                            sealedBytes = transfer.sealedLength,
                            durationMillis = snapshot.elapsedMillis,
                            security = transfer.header.mode.name,
                            peerName = transfer.peerName,
                            verificationCode = transfer.shortAuthString,
                            frames = snapshot.framesShown,
                            profile = transfer.profile.id,
                            compressed = transfer.compressed,
                        ),
                    )
                }
            }
            releasePrepared()
            pairingAccepted.set(false)
            broadcastState.value = BroadcastUi()
            uiState.update { it.copy(items = emptyList(), password = "", phase = SendPhase.Composing) }
            onDone()
        }
    }

    private fun stopBroadcast() {
        broadcastJob?.cancel()
        broadcastJob = null
        paused.value = false
    }

    private fun releasePrepared() {
        val transfer = prepared ?: return
        prepared = null
        // The broadcast job closes its scheduler itself; deleting the sealed file is safe on
        // every supported file system even while a descriptor is still open.
        runCatching { transfer.close() }
    }

    override fun onCleared() {
        wifiJob?.cancel()
        runCatching { wifiSocket?.close() }
        if (lastInvitation != null) {
            val controller = wifiController
            container.applicationScope.launch {
                runCatching { controller.removeGroup() }
                controller.close()
            }
        }
        prepareJob?.cancel()
        stopBroadcast()
        releasePrepared()
        super.onCleared()
    }

    // endregion

    private companion object {
        const val LINK_TIMEOUT_MILLIS = 30_000
        const val PROGRESS_INTERVAL_MILLIS = 100L
        const val LAN_CONNECT_TIMEOUT_MILLIS = 1_000
        const val GROUP_CONNECT_TIMEOUT_MILLIS = 3_000
        const val GROUP_CONNECT_WINDOW_MILLIS = 15_000L
    }
}
