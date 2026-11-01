package com.madix.beam.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madix.beam.R
import com.madix.beam.core.AppContainer
import com.madix.beam.data.history.TransferDirection
import com.madix.beam.data.history.TransferRecord
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.EmptyState
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.GradientIcon
import com.madix.beam.ui.components.MadixLogo
import com.madix.beam.ui.components.Pill
import com.madix.beam.ui.components.SectionHeader
import com.madix.beam.ui.components.StatTile
import com.madix.beam.ui.theme.MadixTheme
import com.madix.beam.util.Format
import kotlin.random.Random

enum class QuickAction { MEDIA, FILES, TEXT, SCAN }

@Composable
fun HomeScreen(
    container: AppContainer,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onQuickAction: (QuickAction) -> Unit,
    onHistory: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val records by container.history.records.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(container.initialSettings)
    val context = LocalContext.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    AuroraBackground {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 12.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MadixLogo(size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onHistory) {
                        Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.history_title))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            }

            item { HeroCard() }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ActionTile(
                        title = stringResource(R.string.home_send),
                        subtitle = stringResource(R.string.home_send_hint),
                        icon = Icons.Rounded.CallMade,
                        brush = MadixTheme.colors.sendGradient,
                        onClick = onSend,
                        modifier = Modifier.weight(1f),
                    )
                    ActionTile(
                        title = stringResource(R.string.home_receive),
                        subtitle = stringResource(R.string.home_receive_hint),
                        icon = Icons.Rounded.CallReceived,
                        brush = MadixTheme.colors.receiveGradient,
                        onClick = onReceive,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Column {
                SectionHeader(stringResource(R.string.home_quick_actions))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile(Icons.Rounded.PhotoLibrary, stringResource(R.string.quick_media), Modifier.weight(1f)) { onQuickAction(QuickAction.MEDIA) }
                    QuickTile(Icons.Rounded.FolderOpen, stringResource(R.string.quick_files), Modifier.weight(1f)) { onQuickAction(QuickAction.FILES) }
                    QuickTile(Icons.Rounded.Notes, stringResource(R.string.quick_text), Modifier.weight(1f)) { onQuickAction(QuickAction.TEXT) }
                    QuickTile(Icons.Rounded.QrCodeScanner, stringResource(R.string.quick_scan), Modifier.weight(1f)) { onQuickAction(QuickAction.SCAN) }
                }
                }
            }

            item {
                val totalBytes = records.sumOf { it.totalBytes }
                val totalFiles = records.sumOf { it.items.size }
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 18.dp, horizontal = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatTile(stringResource(R.string.stat_transfers), records.size.toString(), Modifier.weight(1f))
                        StatTile(stringResource(R.string.stat_data), Format.bytes(context, totalBytes), Modifier.weight(1f))
                        StatTile(stringResource(R.string.stat_files), totalFiles.toString(), Modifier.weight(1f))
                    }
                }
            }

            item {
                SectionHeader(
                    stringResource(R.string.home_recent),
                    trailing = if (records.isNotEmpty()) {
                        { TextButton(onClick = onHistory) { Text(stringResource(R.string.action_view_all)) } }
                    } else {
                        null
                    },
                )
            }

            if (records.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.History,
                        title = stringResource(R.string.home_empty_title),
                        message = stringResource(R.string.home_empty_body),
                    )
                }
            } else {
                items(records.take(4), key = { it.id }) { record ->
                    RecordRow(record, onClick = { onOpenRecord(record.id) })
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (settings.appLockEnabled) Icons.Rounded.Lock else Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = MadixTheme.colors.success,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.home_privacy_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard() {
    val transition = rememberInfiniteTransition(label = "hero")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "sweep",
    )
    val modules = remember {
        val random = Random(42)
        List(11 * 11) { random.nextFloat() < 0.48f }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MadixTheme.colors.brandGradient)
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_hero_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.home_hero_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(stringResource(R.string.badge_aes), color = Color.White, icon = Icons.Rounded.Lock)
                    Pill(stringResource(R.string.badge_offline), color = Color.White, icon = Icons.Rounded.WifiOff)
                }
            }
            Spacer(Modifier.width(12.dp))
            // Stylised QR code with a scanning beam.
            Canvas(
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White),
            ) {
                val cells = 11
                val pad = size.width * 0.08f
                val cell = (size.width - pad * 2) / cells
                for (y in 0 until cells) {
                    for (x in 0 until cells) {
                        val finder = (x < 3 && y < 3) || (x > 7 && y < 3) || (x < 3 && y > 7)
                        val gap = (x == 3 && (y < 4 || y > 6)) || (y == 3 && (x < 4 || x > 6)) ||
                            (x == 7 && y < 4) || (y == 7 && x < 4)
                        val filled = when {
                            finder -> true
                            gap -> false
                            else -> modules[y * cells + x]
                        }
                        if (filled) {
                            drawRoundRect(
                                color = Color(0xFF140A38),
                                topLeft = Offset(pad + x * cell + cell * 0.08f, pad + y * cell + cell * 0.08f),
                                size = Size(cell * 0.84f, cell * 0.84f),
                                cornerRadius = CornerRadius(cell * 0.22f),
                            )
                        }
                    }
                }
                val beamY = pad + sweep * (size.height - pad * 2)
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x995BE7FF), Color.Transparent),
                        startY = beamY - 14f,
                        endY = beamY + 14f,
                    ),
                    topLeft = Offset(0f, beamY - 14f),
                    size = Size(size.width, 28f),
                )
            }
        }
    }
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    brush: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(18.dp)) {
        GradientIcon(icon, brush = brush, size = 52.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MadixTheme.colors.glass)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RecordRow(record: TransferRecord, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sent = record.direction == TransferDirection.SENT
    val first = record.items.firstOrNull()?.name ?: stringResource(R.string.history_untitled)
    val extra = record.items.size - 1
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientIcon(
                if (sent) Icons.Rounded.CallMade else Icons.Rounded.CallReceived,
                brush = if (sent) MadixTheme.colors.sendGradient else MadixTheme.colors.receiveGradient,
                size = 42.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (extra > 0) pluralStringResource(R.plurals.record_title_more, extra, first, extra) else first,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        if (sent) R.string.record_subtitle_sent else R.string.record_subtitle_received,
                        Format.bytes(context, record.totalBytes),
                        Format.relative(record.timestamp),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
