package com.madix.beam.ui.receive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.madix.beam.R
import com.madix.beam.core.AppContainer
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.FileActions
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.GradientButton
import com.madix.beam.ui.components.GradientIcon
import com.madix.beam.ui.components.Pill
import com.madix.beam.ui.components.SoftButton
import com.madix.beam.ui.components.StoredFile
import com.madix.beam.ui.components.iconForMime
import com.madix.beam.ui.components.rememberDeviceExporter
import com.madix.beam.ui.send.modeLabel
import com.madix.beam.ui.theme.MadixTheme
import com.madix.beam.util.Format

@Composable
fun ReceiveResultScreen(viewModel: ReceiveViewModel, container: AppContainer, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result = (state.phase as? ReceivePhase.Done)?.result
    BackHandler(onBack = onDone)
    if (result == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val context = LocalContext.current
    val exporter = rememberDeviceExporter(container.files)
    val files = remember(result) { result.entries.filterNot { it.isText }.map { StoredFile(it.file, it.name, it.mimeType) } }
    val badgeScale = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { badgeScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.scale(badgeScale.value)) {
                            GradientIcon(Icons.Rounded.Check, size = 96.dp, iconSize = 56.dp, brush = MadixTheme.colors.beamGradient)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(stringResource(R.string.result_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(
                                R.string.result_summary,
                                pluralStringResource(R.plurals.item_count, result.entries.size, result.entries.size),
                                Format.bytes(context, result.totalBytes),
                                Format.duration(result.durationMillis / 1000),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (result.senderName.isNotBlank()) {
                            Text(
                                stringResource(R.string.result_from, result.senderName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(stringResource(R.string.result_verified), icon = Icons.Rounded.Verified, color = MadixTheme.colors.success)
                            Pill(stringResource(modeLabel(result.mode)))
                        }
                    }
                }

                items(result.entries, key = { it.file.absolutePath }) { entry ->
                    ReceivedEntryCard(entry, container)
                }
            }

            if (files.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SoftButton(
                        text = stringResource(R.string.action_save_all),
                        icon = Icons.Rounded.Download,
                        onClick = { exporter(files) },
                        modifier = Modifier.weight(1f),
                    )
                    SoftButton(
                        text = stringResource(R.string.action_share_all),
                        icon = Icons.Rounded.Share,
                        onClick = { FileActions.share(context, container.files, files) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            GradientButton(
                text = stringResource(R.string.action_done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ReceivedEntryCard(entry: ReceivedEntry, container: AppContainer) {
    val context = LocalContext.current
    val exporter = rememberDeviceExporter(container.files)
    val stored = StoredFile(entry.file, entry.name, entry.mimeType)
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.mimeType.startsWith("image/") || entry.mimeType.startsWith("video/")) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(entry.file).size(192).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                )
            } else {
                GradientIcon(iconForMime(entry.mimeType, entry.isText), size = 56.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    Format.bytes(context, entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.result_sha, entry.sha256.take(16)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        val preview = entry.textPreview
        if (entry.isText && preview != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                preview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .padding(4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (entry.isText && preview != null) {
                IconButton(onClick = { FileActions.copyText(context, preview) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                }
                IconButton(onClick = { FileActions.shareText(context, preview) }) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_share))
                }
            } else {
                IconButton(onClick = { FileActions.open(context, container.files, stored) }) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = stringResource(R.string.action_open))
                }
                IconButton(onClick = { exporter(listOf(stored)) }) {
                    Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.action_save))
                }
                IconButton(onClick = { FileActions.share(context, container.files, listOf(stored)) }) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_share))
                }
            }
        }
    }
}
