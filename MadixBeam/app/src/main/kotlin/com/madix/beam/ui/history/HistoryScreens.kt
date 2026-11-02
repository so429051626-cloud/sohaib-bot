package com.madix.beam.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madix.beam.R
import com.madix.beam.core.AppContainer
import com.madix.beam.data.history.HistoryItem
import com.madix.beam.data.history.TransferDirection
import com.madix.beam.data.history.TransferRecord
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.EmptyState
import com.madix.beam.ui.components.FileActions
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.GradientIcon
import com.madix.beam.ui.components.MadixTopBar
import com.madix.beam.ui.components.SectionHeader
import com.madix.beam.ui.components.StoredFile
import com.madix.beam.ui.components.iconForMime
import com.madix.beam.ui.components.rememberDeviceExporter
import com.madix.beam.ui.home.RecordRow
import com.madix.beam.ui.send.modeLabel
import com.madix.beam.ui.settings.methodLabel
import com.madix.beam.data.settings.TransferMethod
import com.madix.beam.ui.send.profileLabel
import com.madix.beam.protocol.session.TransferProfile
import com.madix.beam.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class HistoryFilter { ALL, SENT, RECEIVED }

@Composable
fun HistoryScreen(container: AppContainer, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val records by container.history.records.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val visible = when (filter) {
        HistoryFilter.ALL -> records
        HistoryFilter.SENT -> records.filter { it.direction == TransferDirection.SENT }
        HistoryFilter.RECEIVED -> records.filter { it.direction == TransferDirection.RECEIVED }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { container.history.clear() }
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MadixTopBar(
                title = stringResource(R.string.history_title),
                onBack = onBack,
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.history_clear_title))
                        }
                    }
                },
            )
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = {
                            Text(
                                stringResource(
                                    when (option) {
                                        HistoryFilter.ALL -> R.string.filter_all
                                        HistoryFilter.SENT -> R.string.filter_sent
                                        HistoryFilter.RECEIVED -> R.string.filter_received
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (visible.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.History,
                            title = stringResource(R.string.history_empty_title),
                            message = stringResource(R.string.history_empty_body),
                        )
                    }
                } else {
                    items(visible, key = { it.id }) { record ->
                        RecordRow(record, onClick = { onOpen(record.id) })
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
fun HistoryDetailScreen(container: AppContainer, recordId: String, onBack: () -> Unit) {
    val records by container.history.records.collectAsStateWithLifecycle()
    val record = records.firstOrNull { it.id == recordId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val exporter = rememberDeviceExporter(container.files)

    if (confirmDelete && record != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (record.direction == TransferDirection.RECEIVED) R.string.history_delete_body_received else R.string.history_delete_body_sent,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            container.files.delete(record.items.mapNotNull { it.relativePath })
                        }
                        container.history.remove(record.id)
                        onBack()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MadixTopBar(
                title = stringResource(R.string.history_detail_title),
                onBack = onBack,
                actions = {
                    if (record != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                },
            )
            if (record == null) {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.history_missing_title),
                    message = stringResource(R.string.history_missing_body),
                )
            } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { RecordSummary(record) }
                item { SectionHeader(stringResource(R.string.history_items)) }
                items(record.items) { item ->
                    val file = item.relativePath?.let { path -> container.files.resolve(path) }
                    HistoryItemRow(
                        item = item,
                        available = file != null,
                        onOpen = { file?.let { FileActions.open(context, container.files, StoredFile(it, item.name, item.mimeType)) } },
                        onSave = { file?.let { exporter(listOf(StoredFile(it, item.name, item.mimeType))) } },
                        onShare = { file?.let { FileActions.share(context, container.files, listOf(StoredFile(it, item.name, item.mimeType))) } },
                        showActions = record.direction == TransferDirection.RECEIVED,
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
            }
        }
    }
}

@Composable
private fun RecordSummary(record: TransferRecord) {
    val context = LocalContext.current
    val mode = runCatching { SecurityMode.valueOf(record.security) }.getOrDefault(SecurityMode.OPEN)
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        DetailRow(
            stringResource(R.string.detail_direction),
            stringResource(if (record.direction == TransferDirection.SENT) R.string.filter_sent else R.string.filter_received),
        )
        DetailRow(stringResource(R.string.detail_date), Format.dateTime(context, record.timestamp))
        record.peerName?.let {
            DetailRow(stringResource(if (record.direction == TransferDirection.SENT) R.string.detail_recipient else R.string.detail_sender), it)
        }
        DetailRow(stringResource(R.string.detail_size), Format.bytes(context, record.totalBytes))
        DetailRow(stringResource(R.string.detail_sealed_size), Format.bytes(context, record.sealedBytes))
        DetailRow(stringResource(R.string.detail_duration), Format.duration(record.durationMillis / 1000))
        record.method?.let { method ->
            val transferMethod = runCatching { TransferMethod.valueOf(method) }.getOrNull()
            if (transferMethod != null) DetailRow(stringResource(R.string.detail_method), stringResource(methodLabel(transferMethod)))
        }
        DetailRow(stringResource(R.string.detail_security), stringResource(modeLabel(mode)))
        record.verificationCode?.let { DetailRow(stringResource(R.string.verification_code), it) }
        record.profile?.let { id ->
            DetailRow(stringResource(R.string.detail_profile), stringResource(profileLabel(TransferProfile.fromId(id))))
        }
        if (record.frames > 0) DetailRow(stringResource(R.string.stat_frames), record.frames.toString())
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HistoryItemRow(
    item: HistoryItem,
    available: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    showActions: Boolean,
) {
    val context = LocalContext.current
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientIcon(iconForMime(item.mimeType, item.isText), size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (showActions && !available) {
                        stringResource(R.string.history_file_removed)
                    } else {
                        Format.bytes(context, item.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.sha256?.let {
                    Text(
                        stringResource(R.string.result_sha, it.take(16)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (showActions && available) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onOpen) { Icon(Icons.Rounded.OpenInNew, contentDescription = stringResource(R.string.action_open), modifier = Modifier.size(22.dp)) }
                IconButton(onClick = onSave) { Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.action_save), modifier = Modifier.size(22.dp)) }
                IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_share), modifier = Modifier.size(22.dp)) }
            }
        }
    }
}
