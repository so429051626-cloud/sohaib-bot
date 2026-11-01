package com.madix.beam.ui.send

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
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
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.GradientButton
import com.madix.beam.ui.components.KeepScreenAwake
import com.madix.beam.ui.components.MadixTopBar
import com.madix.beam.ui.components.Pill
import com.madix.beam.ui.components.ProgressRing
import com.madix.beam.ui.components.SoftButton
import com.madix.beam.ui.components.StatTile
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        broadcast.verificationCode?.let { code ->
            VerificationCard(code, broadcast.peerName)
            Spacer(Modifier.height(14.dp))
        }

        // The QR code always renders black on white for reliable scanning in any theme.
        Box(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(30.dp))
                .background(Brush.sweepGradient(listOf(Brand.Cyan500, Brand.Violet500, Brand.Rose400, Brand.Cyan500)))
                .padding(4.dp)
                .clip(RoundedCornerShape(27.dp))
                .background(Color.White)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            val frame = broadcast.frame
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = stringResource(R.string.broadcast_qr_description),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
            }
            if (broadcast.paused) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.broadcast_paused),
                        color = Brand.Ink,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }

        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.stat_frames), broadcast.framesShown.toString(), Modifier.weight(1f))
                StatTile(
                    stringResource(R.string.stat_pass),
                    stringResource(R.string.broadcast_pass_value, broadcast.passes + 1, Format.percent(broadcast.passFraction)),
                    Modifier.weight(1f),
                )
                StatTile(stringResource(R.string.stat_elapsed), Format.duration(broadcast.elapsedMillis / 1000), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.broadcast_frame_rate), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.broadcast_pass_duration, Format.duration(broadcast.passSeconds)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.setFps(broadcast.fps - 1) }, enabled = broadcast.fps > TransferProfile.MIN_FPS) {
                    Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.action_slower))
                }
                Text(
                    stringResource(R.string.broadcast_fps_value, broadcast.fps),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = { viewModel.setFps(broadcast.fps + 1) }, enabled = broadcast.fps < TransferProfile.MAX_FPS) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_faster))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.broadcast_payload_summary,
                    Format.bytes(context, broadcast.payloadBytes),
                    Format.bytes(context, broadcast.sealedBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.broadcast_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftButton(
                text = stringResource(if (broadcast.paused) R.string.action_resume else R.string.action_pause),
                icon = if (broadcast.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                onClick = viewModel::togglePause,
                modifier = Modifier.weight(1f),
            )
            GradientButton(
                text = stringResource(R.string.action_finish),
                icon = Icons.Rounded.CheckCircle,
                onClick = onFinish,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun VerificationCard(code: String, peerName: String?) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.verification_code), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (peerName.isNullOrBlank()) {
                        stringResource(R.string.verification_compare_generic)
                    } else {
                        stringResource(R.string.verification_compare_named, peerName)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                code,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

fun modeLabel(mode: SecurityMode): Int = when (mode) {
    SecurityMode.PAIRED -> R.string.mode_paired
    SecurityMode.PASSWORD -> R.string.mode_password
    SecurityMode.OPEN -> R.string.mode_open
}
