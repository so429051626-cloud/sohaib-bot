package com.madix.beam.ui.send

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madix.beam.R
import com.madix.beam.core.AppContainer
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.session.PrepareStage
import com.madix.beam.protocol.session.TransferProfile
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.GradientButton
import com.madix.beam.ui.components.KeepScreenAwake
import com.madix.beam.ui.components.MadixTopBar
import com.madix.beam.ui.components.Pill
import com.madix.beam.ui.components.ProgressRing
import com.madix.beam.ui.components.SoftButton
import com.madix.beam.ui.theme.Brand
import com.madix.beam.ui.theme.MadixTheme
import com.madix.beam.util.Format

@Composable
fun BroadcastScreen(
    viewModel: SendViewModel,
    container: AppContainer,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(container.initialSettings)
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }

    fun stop() {
        viewModel.cancelTransfer()
        onBack()
    }

    BackHandler {
        when (state.phase) {
            is SendPhase.Broadcasting -> confirmStop = true
            else -> stop()
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.broadcast_stop_title)) },
            text = { Text(stringResource(R.string.broadcast_stop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    stop()
                }) { Text(stringResource(R.string.action_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.action_continue)) } },
        )
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text(stringResource(R.string.broadcast_finish_title)) },
            text = { Text(stringResource(R.string.broadcast_finish_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    viewModel.finish(onFinished)
                }) { Text(stringResource(R.string.action_finish)) }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            MadixTopBar(
                title = stringResource(R.string.broadcast_title),
                onBack = {
                    if (state.phase is SendPhase.Broadcasting) confirmStop = true else stop()
                },
            )
            when (val phase = state.phase) {
                is SendPhase.Preparing -> PreparingContent(phase, onCancel = ::stop)
                is SendPhase.Failed -> FailureContent(phase.reason, onBack = ::stop)
                is SendPhase.Broadcasting -> {
                    KeepScreenAwake(maxBrightness = settings.maxBrightness)
                    BroadcastContent(viewModel, onFinish = { confirmFinish = true })
                }
                SendPhase.Composing -> PreparingContent(SendPhase.Preparing(PrepareStage.PACKAGING, -1f), onCancel = ::stop)
            }
        }
    }
}

@Composable
private fun PreparingContent(phase: SendPhase.Preparing, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val indeterminate = phase.fraction < 0f
        ProgressRing(progress = phase.fraction.coerceAtLeast(0f), size = 140.dp, indeterminate = indeterminate) {
            if (!indeterminate) Text(Format.percent(phase.fraction), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(
                when (phase.stage) {
                    PrepareStage.PACKAGING -> R.string.prepare_packaging
                    PrepareStage.COMPRESSING -> R.string.prepare_compressing
                    PrepareStage.SEALING -> R.string.prepare_sealing
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.prepare_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        SoftButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
    }
}

@Composable
private fun FailureContent(reason: SendFailure, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MadixTheme.colors.danger, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.send_failed_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(failureMessage(reason)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        GradientButton(text = stringResource(R.string.action_back_to_items), onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun BroadcastContent(viewModel: SendViewModel, onFinish: () -> Unit) {
    val broadcast by viewModel.broadcast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(
                stringResource(modeLabel(broadcast.mode)),
                icon = when (broadcast.mode) {
                    SecurityMode.PAIRED -> Icons.Rounded.Shield
                    SecurityMode.PASSWORD -> Icons.Rounded.Key
                    SecurityMode.OPEN -> Icons.Rounded.LockOpen
                },
                color = if (broadcast.mode == SecurityMode.OPEN) MadixTheme.colors.warning else MadixTheme.colors.success,
            )
            Pill(stringResource(profileLabel(broadcast.profile)), icon = Icons.Rounded.Speed)
            Spacer(Modifier.weight(1f))
            broadcast.verificationCode?.let { code ->
                Text(
                    code,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        broadcast.verificationCode?.let {
            Text(
                if (broadcast.peerName.isNullOrBlank()) {
                    stringResource(R.string.verification_compare_generic)
                } else {
                    stringResource(R.string.verification_compare_named, broadcast.peerName.orEmpty())
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        // The codes always render black on white, as large as the screen allows.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            QrStack(broadcast.frames)
            if (broadcast.paused) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.broadcast_paused), color = Brand.Ink, style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { broadcast.passFraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(
                R.string.broadcast_status_line,
                broadcast.passes + 1,
                Format.duration(broadcast.elapsedMillis / 1000),
                Format.bytes(context, broadcast.sealedBytes),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.broadcast_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp, start = 8.dp, end = 8.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { viewModel.setFps(broadcast.fps - 1) }, enabled = broadcast.fps > TransferProfile.MIN_FPS) {
                Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.action_slower))
            }
            Text(
                stringResource(R.string.broadcast_fps_value, broadcast.fps),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = { viewModel.setFps(broadcast.fps + 1) }, enabled = broadcast.fps < TransferProfile.MAX_FPS) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_faster))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::togglePause) {
                Icon(
                    if (broadcast.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = stringResource(if (broadcast.paused) R.string.action_resume else R.string.action_pause),
                )
            }
            Spacer(Modifier.width(6.dp))
            GradientButton(
                text = stringResource(R.string.action_finish),
                icon = Icons.Rounded.CheckCircle,
                onClick = onFinish,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * Lays out the codes of one tick as large as possible. Each code is scaled by a whole number of
 * pixels per module so that every module has identical, perfectly sharp edges on screen.
 */
@Composable
private fun QrStack(frames: List<ImageBitmap>) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val count = frames.size
        val modules = frames.firstOrNull()?.width ?: 0
        if (count > 0 && modules > 0) {
            val gapPx = with(density) { 14.dp.roundToPx() }
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight
            val vertical = heightPx >= widthPx
            val cellPx = if (vertical) {
                minOf(widthPx, (heightPx - gapPx * (count - 1)) / count)
            } else {
                minOf(heightPx, (widthPx - gapPx * (count - 1)) / count)
            }
            val scale = maxOf(1, cellPx / modules)
            val side = with(density) { (modules * scale).toDp() }
            val gap = with(density) { gapPx.toDp() }
            val description = stringResource(R.string.broadcast_qr_description)
            if (vertical) {
                Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
                    frames.forEach { code -> QrImage(code, side, description) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    frames.forEach { code -> QrImage(code, side, description) }
                }
            }
        }
    }
}

@Composable
private fun QrImage(code: ImageBitmap, side: Dp, description: String) {
    Image(
        bitmap = code,
        contentDescription = description,
        modifier = Modifier.size(side),
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.None,
    )
}

fun modeLabel(mode: SecurityMode): Int = when (mode) {
    SecurityMode.PAIRED -> R.string.mode_paired
    SecurityMode.PASSWORD -> R.string.mode_password
    SecurityMode.OPEN -> R.string.mode_open
}
