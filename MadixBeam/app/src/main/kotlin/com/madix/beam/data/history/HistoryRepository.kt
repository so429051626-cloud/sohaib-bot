package com.madix.beam.data.history

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class TransferDirection { SENT, RECEIVED }

@Serializable
data class HistoryItem(
    val name: String,
    val mimeType: String,
    val size: Long,
    /** Path relative to the received-files directory; null for sent items. */
    val relativePath: String? = null,
    val sha256: String? = null,
    val isText: Boolean = false,
)

@Serializable
data class TransferRecord(
    val id: String,
    val direction: TransferDirection,
    val timestamp: Long,
    val items: List<HistoryItem>,
    val totalBytes: Long,
    val sealedBytes: Long,
    val durationMillis: Long,
    val security: String,
    val peerName: String? = null,
    val verificationCode: String? = null,
    val frames: Long = 0,
    val profile: String? = null,
    val compressed: Boolean = false,
    /** "WIFI" for direct Wi-Fi transfers, "QR" for optical transfers; null in older records. */
    val method: String? = null,
)

/** Persists the transfer log as an atomically written JSON document. */
class HistoryRepository(context: Context, scope: CoroutineScope) {

    private val file = AtomicFile(File(context.filesDir, "history.json"))
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(TransferRecord.serializer())
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private val state = MutableStateFlow<List<TransferRecord>>(emptyList())

    val records: StateFlow<List<TransferRecord>> = state.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                state.value = runCatching {
                    json.decodeFromString(serializer, String(file.readFully(), Charsets.UTF_8))
                }.getOrDefault(emptyList())
            }
            loaded.complete(Unit)
        }
    }

    fun find(id: String): TransferRecord? = state.value.firstOrNull { it.id == id }

    suspend fun add(record: TransferRecord) = mutate { current ->
        (listOf(record) + current.filterNot { it.id == record.id }).take(MAX_RECORDS)
    }

    suspend fun remove(id: String) = mutate { current -> current.filterNot { it.id == id } }

    suspend fun clear() = mutate { emptyList() }

    private suspend fun mutate(transform: (List<TransferRecord>) -> List<TransferRecord>) {
        loaded.await()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = transform(state.value)
                // Persistence is best effort: a storage failure must never crash the caller.
                runCatching {
                    val stream = file.startWrite()
                    try {
                        stream.write(json.encodeToString(serializer, updated).toByteArray(Charsets.UTF_8))
                        file.finishWrite(stream)
                    } catch (e: Exception) {
                        file.failWrite(stream)
                        throw e
                    }
                }
                state.value = updated
            }
        }
    }

    private companion object {
        const val MAX_RECORDS = 500
    }
}
