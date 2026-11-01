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
import java.io.FileNotFoundException
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
    val security: SecurityMode = SecurityMode.PAIRED,
    val password: String = "",
    val profile: TransferProfile = TransferProfile.DEFAULT,
    val imageQuality: ImageQuality = ImageQuality.BALANCED,
    val phase: SendPhase = SendPhase.Composing,
    val loadingItems: Boolean = false,
) {
    val totalBytes: Long get() = items.sumOf { it.size.coerceAtLeast(0) }
    val hasImages: Boolean get() = items.any { it.isImage }
    val passwordValid: Boolean get() = password.length >= MIN_PASSWORD_LENGTH
    val tooLarge: Boolean get() = totalBytes > Protocol.MAX_PAYLOAD_BYTES
    val canContinue: Boolean
        get() = items.isNotEmpty() && !tooLarge && !loadingItems &&
            (security != SecurityMode.PASSWORD || passwordValid)

    /** Rough duration estimate before compression and photo optimisation. */
    fun estimatedSeconds(): Long = profile.estimateSeconds(totalBytes + 2048)

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}

data class BroadcastUi(
    val frame: ImageBitmap? = null,
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
            val frames = totalBlocks * Protocol.REPAIR_PERIOD / (Protocol.REPAIR_PERIOD - 1.0) /
                (1.0 - 1.0 / Protocol.HEADER_INTERVAL)
            return (frames / fps.coerceAtLeast(1)).toLong() + 1
        }
}

/** Pairing scan outcome used for user feedback. */
enum class PairingScanResult { ACCEPTED, NOT_PAIRING, INVALID }

class SendViewModel(private val container: AppContainer) : ViewModel() {

    private val uiState = MutableStateFlow(
        SendUiState(
            security = container.initialSettings.defaultSecurity,
            profile = container.initialSettings.profile,
            imageQuality = container.initialSettings.imageQuality,
        ),
    )
    val state: StateFlow<SendUiState> = uiState.asStateFlow()

    private val broadcastState = MutableStateFlow(BroadcastUi())
    val broadcast: StateFlow<BroadcastUi> = broadcastState.asStateFlow()

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
                    it.copy(security = settings.defaultSecurity, profile = settings.profile, imageQuality = settings.imageQuality)
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
            if (frame is Frame.Pairing) {
                if (!EphemeralKeyPair.isValidPublicKey(frame.request.publicKey)) {
                    result = PairingScanResult.INVALID
                    continue
                }
                if (pairingAccepted.compareAndSet(false, true)) {
                    viewModelScope.launch { beginTransfer(frame.request) }
                    return PairingScanResult.ACCEPTED
                }
                return null
            }
            result = PairingScanResult.NOT_PAIRING
        }
        return result
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
            val frames = Channel<ImageBitmap>(capacity = 4)
            val producer = launch {
                try {
                    while (isActive) {
                        val text = scheduler.nextQrText()
                        frames.send(QrRenderer.render(text, profile.errorCorrection).asImageBitmap())
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
                    shown++
                    val rate = fps.value
                    broadcastState.update {
                        it.copy(
                            frame = frame,
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
        prepareJob?.cancel()
        stopBroadcast()
        releasePrepared()
        super.onCleared()
    }

    // endregion
}
