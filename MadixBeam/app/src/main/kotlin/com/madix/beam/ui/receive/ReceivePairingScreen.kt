package com.madix.beam.ui.receive

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madix.beam.R
import com.madix.beam.core.AppContainer
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.GradientButton
import com.madix.beam.ui.components.KeepScreenAwake
import com.madix.beam.ui.components.MadixTopBar
import com.madix.beam.ui.components.Pill
import com.madix.beam.ui.components.SoftButton
import com.madix.beam.ui.home.QuickAction
import com.madix.beam.ui.navigation.PendingAction
import com.madix.beam.ui.theme.MadixTheme
import com.madix.beam.wifi.WifiPermissions

/**
 * Shows the receiver's one-time code. Senders using Wi-Fi Direct connect automatically after
 * scanning it; senders using QR codes require "Start receiving".
 */
@Composable
fun ReceivePairingScreen(
    viewModel: ReceiveViewModel,
    container: AppContainer,
    onBack: () -> Unit,
    onStartScanning: () -> Unit,
    onCompleted: () -> Unit,
) {
    val pairingCode by viewModel.pairingCode.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(container.initialSettings)
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionAsked by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshWifi()
    }

    fun handleWifiAction(status: WifiHostStatus) {
        when (status) {
            WifiHostStatus.PERMISSION_REQUIRED -> permissionLauncher.launch(WifiPermissions.required)
            WifiHostStatus.WIFI_OFF -> {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
                runCatching { context.startActivity(intent) }
            }
            WifiHostStatus.LOCATION_OFF -> runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            WifiHostStatus.FAILED, WifiHostStatus.LAN_ONLY -> viewModel.refreshWifi()
            else -> Unit
        }
    }

    // "Scan" from the home screen skips straight to the camera.
    LaunchedEffect(Unit) {
        if (PendingAction.quickAction.value == QuickAction.SCAN) {
            PendingAction.quickAction.value = null
            onStartScanning()
        }
    }

    // Ask once for the Wi-Fi Direct permission when it is the only missing requirement.
    LaunchedEffect(state.wifi) {
        if (state.wifi == WifiHostStatus.PERMISSION_REQUIRED && !permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(WifiPermissions.required)
        }
    }

    // Returning from the Wi-Fi or location settings re-evaluates availability.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshWifi()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.phase) {
        when (state.phase) {
            is ReceivePhase.Linking -> if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            is ReceivePhase.Done -> {
                if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCompleted()
            }
            else -> Unit
        }
    }

    KeepScreenAwake(maxBrightness = false)

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            MadixTopBar(title = stringResource(R.string.receive_title), onBack = onBack)
            val phase = state.phase
            AnimatedContent(
                targetState = when (phase) {
                    is ReceivePhase.Linking, is ReceivePhase.Done -> 1
                    is ReceivePhase.Failed -> 2
                    else -> 0
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f),
                label = "receiveContent",
            ) { content ->
                when (content) {
                    1 -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        val link = (phase as? ReceivePhase.Linking)?.link
                        if (link != null) {
                            LinkReceivingPanel(link)
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                    2 -> FailureCard(
                        reason = (phase as? ReceivePhase.Failed)?.reason ?: ReceiveFailure.UNKNOWN,
                        onRetry = viewModel::restart,
                        onBack = onBack,
                    )
                    else -> PairingContent(
                        pairingCode = pairingCode,
                        deviceName = viewModel.deviceName,
                        wifi = state.wifi,
                        network = state.network,
                        problem = state.wifiProblem,
                        onWifiAction = ::handleWifiAction,
                        onStartScanning = onStartScanning,
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingContent(
    pairingCode: androidx.compose.ui.graphics.ImageBitmap?,
    deviceName: String,
    wifi: WifiHostStatus,
    network: HostedNetworkUi?,
    problem: HostProblem?,
    onWifiAction: (WifiHostStatus) -> Unit,
    onStartScanning: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
                Text(stringResource(R.string.pairing_code_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.pairing_code_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pairingCode == null) {
                        CircularProgressIndicator()
                    } else {
                        Image(
                            bitmap = pairingCode,
                            contentDescription = stringResource(R.string.pairing_code_title),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Pill(deviceName, icon = Icons.Rounded.PhoneAndroid)
                    Pill(stringResource(R.string.pairing_single_use), icon = Icons.Rounded.Timer, color = MadixTheme.colors.success)
                }
                Spacer(Modifier.height(12.dp))
                WifiStatusRow(wifi, network, problem, onAction = onWifiAction)
            }

            Spacer(Modifier.height(16.dp))
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
                Step(1, stringResource(R.string.receive_step_1))
                Spacer(Modifier.height(12.dp))
                Step(2, stringResource(R.string.receive_step_2))
                Spacer(Modifier.height(12.dp))
                Step(3, stringResource(R.string.receive_step_3))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.receive_direct_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
        SoftButton(
            text = stringResource(R.string.receive_start_qr),
            icon = Icons.Rounded.QrCodeScanner,
            onClick = onStartScanning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun FailureCard(reason: ReceiveFailure, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MadixTheme.colors.danger, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.scan_failed_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when (reason) {
                    ReceiveFailure.INTEGRITY -> R.string.scan_failed_integrity
                    ReceiveFailure.STORAGE -> R.string.scan_failed_storage
                    ReceiveFailure.CONNECTION -> R.string.scan_failed_connection
                    ReceiveFailure.UNKNOWN -> R.string.scan_failed_unknown
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        GradientButton(text = stringResource(R.string.receive_ready_again), onClick = onRetry, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SoftButton(text = stringResource(R.string.action_close), onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MadixTheme.colors.beamGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
    }
}
