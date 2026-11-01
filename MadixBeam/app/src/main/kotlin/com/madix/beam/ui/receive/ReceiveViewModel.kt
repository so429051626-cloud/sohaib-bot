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
import java.io.File
import java.io.IOException
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
)

sealed interface ReceivePhase {
    /** Camera active, collecting frames. */
    data object Scanning : ReceivePhase

    /** All frames collected; waiting for the password. */
    data class NeedsPassword(val wrongAttempt: Boolean) : ReceivePhase

    /** Decrypting, verifying and writing files. */
    data class Opening(val fraction: Float) : ReceivePhase

    data class Done(val result: ReceiveResult) : ReceivePhase

    data class Failed(val reason: ReceiveFailure) : ReceivePhase
}

enum class ReceiveFailure { INTEGRITY, STORAGE, UNKNOWN }

data class ReceiveUiState(
    val phase: ReceivePhase = ReceivePhase.Scanning,
    val snapshot: ReceptionSnapshot = ReceptionSnapshot(),
    val verificationCode: String? = null,
    val pairingCodeSeen: Boolean = false,
    val foreignText: String? = null,
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

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val nonce = SecureRandom().nextInt()
            val text = FrameCodec.toQrText(FrameCodec.encodePairing(PairingRequest(nonce, keys.publicKey, deviceName)))
            pairingImage.value = QrRenderer.render(text, ErrorCorrection.M).asImageBitmap()
        }
        viewModelScope.launch(engineDispatcher) { consumeCodes() }
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
            uiState.value = ReceiveUiState()
        }
    }

    private fun open(password: CharArray?) {
        if (openJob?.isActive == true) return
        uiState.update { it.copy(phase = ReceivePhase.Opening(0f)) }
        openJob = viewModelScope.launch(engineDispatcher) {
            val opener = engine.opener()
            val header = opener.header
            val key = try {
                opener.deriveKey(password)
            } finally {
                password?.fill('\u0000')
            }
            try {
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
    }
}
