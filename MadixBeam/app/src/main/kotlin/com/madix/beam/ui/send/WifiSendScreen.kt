package com.madix.beam.ui.send

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madix.beam.R
import com.madix.beam.core.AppContainer
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.GradientButton
import com.madix.beam.ui.components.GradientIcon
import com.madix.beam.ui.components.KeepScreenAwake
import com.madix.beam.ui.components.MadixTopBar
import com.madix.beam.ui.components.ProgressRing
import com.madix.beam.ui.components.SoftButton
import com.madix.beam.ui.components.StatTile
import com.madix.beam.ui.receive.LinkTransferPanel
import com.madix.beam.ui.theme.MadixTheme
import com.madix.beam.util.Format
import com.madix.beam.wifi.WifiPermissions

@Composable
fun WifiSendScreen(
    viewModel: SendViewModel,
    container: AppContainer,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onFallback: () -> Unit,
) {
    val wifi by viewModel.wifi.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(container.initialSettings)
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val phase = wifi.phase
    val active = phase is WifiPhase.Connecting || phase is WifiPhase.Sending

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.retryWifi()
    }

    fun leave() {
        viewModel.cancelWifi()
        onBack()
    }

    BackHandler { if (active) confirmCancel = true else leave() }

    // A fallback to QR codes moves the flow to the broadcast screen.
    LaunchedEffect(state.phase, phase) {
        if (state.phase !is SendPhase.Composing && phase is WifiPhase.Idle) onFallback()
    }
    LaunchedEffect(phase is WifiPhase.Completed) {
        if (phase is WifiPhase.Completed && settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.wifi_cancel_title)) },
            text = { Text(stringResource(R.string.wifi_cancel_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    leave()
                }) { Text(stringResource(R.string.action_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.action_continue)) } },
        )
    }

    KeepScreenAwake(maxBrightness = false)

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            MadixTopBar(
                title = stringResource(R.string.wifi_send_title),
                onBack = { if (active) confirmCancel = true else leave() },
            )
            AnimatedContent(
                targetState = when (phase) {
                    is WifiPhase.Connecting, WifiPhase.Idle -> 0
                    is WifiPhase.Sending -> 1
                    is WifiPhase.Completed -> 2
                    is WifiPhase.Failed -> 3
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f),
                label = "wifiSend",
            ) { target ->
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    when (target) {
                        0 -> ConnectingContent((phase as? WifiPhase.Connecting)?.step, wifi.peerName, onCancel = ::leave)
                        1 -> {
                            val sending = phase as? WifiPhase.Sending
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (sending != null) {
                                    LinkTransferPanel(
                                        title = stringResource(R.string.wifi_sending_title),
                                        peerLabel = wifi.peerName?.let { stringResource(R.string.wifi_to, it) },
                                        verificationCode = wifi.verificationCode,
                                        bytesDone = sending.bytesDone,
                                        bytesTotal = sending.bytesTotal,
                                        bytesPerSecond = sending.bytesPerSecond,
                                        entryName = sending.entryName,
                                        entryIndex = sending.entryIndex,
                                        entryCount = sending.entryCount,
                                    )
                                }
                                Spacer(Modifier.height(28.dp))
                                SoftButton(text = stringResource(R.string.action_cancel), onClick = { confirmCancel = true })
                            }
                        }
                        2 -> (phase as? WifiPhase.Completed)?.let { completed ->
                            CompletedContent(completed, wifi.peerName, onDone = { viewModel.finishWifi(onFinished) })
                        }
                        else -> FailedContent(
                            reason = (phase as? WifiPhase.Failed)?.reason ?: WifiFailure.INTERRUPTED,
                            onRetry = viewModel::retryWifi,
                            onUseQr = viewModel::fallBackToQr,
                            onAllow = { permissionLauncher.launch(WifiPermissions.required) },
                            onOpenWifi = {
                                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    Intent(Settings.Panel.ACTION_WIFI)
                                } else {
                                    Intent(Settings.ACTION_WIFI_SETTINGS)
                                }
                                runCatching { context.startActivity(intent) }
                            },
                            onOpenLocation = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } },
                            onBack = ::leave,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectingContent(step: ConnectStep?, peerName: String?, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ProgressRing(progress = 0f, size = 150.dp, indeterminate = true) {
            Icon(Icons.Rounded.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(
                when (step) {
                    ConnectStep.JOINING -> R.string.wifi_step_joining
                    ConnectStep.SECURING -> R.string.wifi_step_securing
                    else -> R.string.wifi_step_connecting
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (!peerName.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.wifi_to, peerName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.wifi_connecting_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        SoftButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
    }
}

@Composable
private fun CompletedContent(completed: WifiPhase.Completed, peerName: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scale = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.scale(scale.value)) {
            GradientIcon(Icons.Rounded.Check, size = 104.dp, iconSize = 60.dp, brush = MadixTheme.colors.beamGradient)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.wifi_delivered_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (!peerName.isNullOrBlank()) {
            Text(
                stringResource(R.string.wifi_delivered_to, peerName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 18.dp, horizontal = 8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.stat_files), completed.itemCount.toString(), Modifier.weight(1f))
                StatTile(stringResource(R.string.detail_size), Format.bytes(context, completed.totalBytes), Modifier.weight(1f))
                StatTile(stringResource(R.string.wifi_stat_speed), Format.speed(context, completed.bytesPerSecond), Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.wifi_delivered_body, Format.duration((completed.durationMillis + 999) / 1000)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(28.dp))
        GradientButton(text = stringResource(R.string.action_done), onClick = onDone, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FailedContent(
    reason: WifiFailure,
    onRetry: () -> Unit,
    onUseQr: () -> Unit,
    onAllow: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenLocation: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MadixTheme.colors.danger, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.wifi_failed_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(wifiFailureMessage(reason)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        when (reason) {
            WifiFailure.PERMISSION -> GradientButton(stringResource(R.string.wifi_action_allow), onClick = onAllow, modifier = Modifier.fillMaxWidth())
            WifiFailure.WIFI_OFF -> GradientButton(stringResource(R.string.wifi_action_enable), onClick = onOpenWifi, modifier = Modifier.fillMaxWidth())
            WifiFailure.LOCATION_OFF -> GradientButton(stringResource(R.string.wifi_action_location), onClick = onOpenLocation, modifier = Modifier.fillMaxWidth())
            else -> GradientButton(stringResource(R.string.action_retry), icon = Icons.Rounded.Refresh, onClick = onRetry, modifier = Modifier.fillMaxWidth())
        }
        if (reason == WifiFailure.PERMISSION || reason == WifiFailure.WIFI_OFF || reason == WifiFailure.LOCATION_OFF) {
            Spacer(Modifier.height(10.dp))
            SoftButton(stringResource(R.string.action_retry), icon = Icons.Rounded.Refresh, onClick = onRetry, modifier = Modifier.fillMaxWidth())
        }
        if (reason != WifiFailure.UNREADABLE && reason != WifiFailure.STORAGE) {
            Spacer(Modifier.height(10.dp))
            SoftButton(stringResource(R.string.wifi_use_qr), icon = Icons.Rounded.QrCode2, onClick = onUseQr, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back_to_items)) }
    }
}

fun wifiFailureMessage(reason: WifiFailure): Int = when (reason) {
    WifiFailure.UNSUPPORTED -> R.string.wifi_error_unsupported
    WifiFailure.PERMISSION -> R.string.wifi_error_permission
    WifiFailure.WIFI_OFF -> R.string.wifi_error_off
    WifiFailure.LOCATION_OFF -> R.string.wifi_error_location
    WifiFailure.UNREACHABLE -> R.string.wifi_error_unreachable
    WifiFailure.BUSY -> R.string.wifi_error_busy
    WifiFailure.DECLINED -> R.string.wifi_error_declined
    WifiFailure.STORAGE -> R.string.wifi_error_storage
    WifiFailure.INTEGRITY -> R.string.wifi_error_integrity
    WifiFailure.INTERRUPTED -> R.string.wifi_error_interrupted
    WifiFailure.UNREADABLE -> R.string.send_error_unreadable
}
