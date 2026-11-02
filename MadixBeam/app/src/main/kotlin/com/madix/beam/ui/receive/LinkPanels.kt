package com.madix.beam.ui.receive

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madix.beam.R
import com.madix.beam.ui.components.Pill
import com.madix.beam.ui.components.ProgressRing
import com.madix.beam.ui.theme.MadixTheme
import com.madix.beam.util.Format

/** Live view of a direct Wi-Fi transfer: progress ring, speed, remaining time and file. */
@Composable
fun LinkTransferPanel(
    title: String,
    peerLabel: String?,
    verificationCode: String?,
    bytesDone: Long,
    bytesTotal: Long,
    bytesPerSecond: Double,
    entryName: String,
    entryIndex: Int,
    entryCount: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val known = bytesTotal > 0
    val fraction = if (known) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
    val eta = if (known && bytesPerSecond > 1.0) ((bytesTotal - bytesDone).coerceAtLeast(0) / bytesPerSecond).toLong() + 1 else null
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Pill(stringResource(R.string.link_badge), icon = Icons.Rounded.Bolt, color = MadixTheme.colors.success)
        Spacer(Modifier.height(16.dp))
        ProgressRing(progress = fraction, size = 168.dp, stroke = 12.dp, indeterminate = !known && bytesDone == 0L) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (bytesPerSecond > 0) Format.speed(context, bytesPerSecond) else "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (known) Format.percent(fraction) else Format.bytes(context, bytesDone),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (!peerLabel.isNullOrBlank()) {
            Text(peerLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        val amount = if (known) {
            stringResource(R.string.scan_received_of, Format.bytes(context, bytesDone), Format.bytes(context, bytesTotal))
        } else {
            Format.bytes(context, bytesDone)
        }
        val remaining = eta?.let { stringResource(R.string.link_remaining, Format.duration(it)) }
        Text(
            if (remaining != null) "$amount · $remaining" else amount,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (entryCount > 0 && entryName.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.link_current_item, entryIndex + 1, entryCount, entryName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (verificationCode != null) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MadixTheme.colors.success, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.link_encrypted_code),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    verificationCode,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Reception panel bound to the receive view model state. */
@Composable
fun LinkReceivingPanel(link: LinkUi, modifier: Modifier = Modifier) {
    LinkTransferPanel(
        title = stringResource(R.string.link_receiving_title),
        peerLabel = link.senderName.takeIf { it.isNotBlank() }?.let { stringResource(R.string.result_from, it) },
        verificationCode = link.verificationCode,
        bytesDone = link.bytesDone,
        bytesTotal = link.bytesTotal,
        bytesPerSecond = link.bytesPerSecond,
        entryName = link.entryName,
        entryIndex = link.entryIndex,
        entryCount = link.entryCount,
        modifier = modifier,
    )
}

/** One-line status of Wi-Fi Direct reception with an optional corrective action. */
@Composable
fun WifiStatusRow(status: WifiHostStatus, onAction: (WifiHostStatus) -> Unit, modifier: Modifier = Modifier) {
    val colors = MadixTheme.colors
    val (icon: ImageVector, text: Int, action: Int?) = when (status) {
        WifiHostStatus.CHECKING, WifiHostStatus.STARTING -> Triple(Icons.Rounded.WifiTethering, R.string.wifi_status_starting, null)
        WifiHostStatus.READY -> Triple(Icons.Rounded.Wifi, R.string.wifi_status_ready, null)
        WifiHostStatus.LAN_ONLY -> Triple(Icons.Rounded.Wifi, R.string.wifi_status_lan, null)
        WifiHostStatus.PERMISSION_REQUIRED -> Triple(Icons.Rounded.WifiOff, R.string.wifi_status_permission, R.string.wifi_action_allow)
        WifiHostStatus.WIFI_OFF -> Triple(Icons.Rounded.WifiOff, R.string.wifi_status_off, R.string.wifi_action_enable)
        WifiHostStatus.LOCATION_OFF -> Triple(Icons.Rounded.LocationOff, R.string.wifi_status_location, R.string.wifi_action_location)
        WifiHostStatus.UNSUPPORTED -> Triple(Icons.Rounded.QrCode2, R.string.wifi_status_unsupported, null)
    }
    val tint = when (status) {
        WifiHostStatus.READY, WifiHostStatus.LAN_ONLY -> colors.success
        WifiHostStatus.CHECKING, WifiHostStatus.STARTING -> MaterialTheme.colorScheme.primary
        WifiHostStatus.UNSUPPORTED -> colors.muted
        else -> colors.warning
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (status == WifiHostStatus.CHECKING || status == WifiHostStatus.STARTING) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        if (action != null) {
            TextButton(onClick = { onAction(status) }) { Text(stringResource(action)) }
        }
    }
}
