package com.madix.beam.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madix.beam.R
import com.madix.beam.camera.QrCameraView
import com.madix.beam.ui.components.BannerKind
import com.madix.beam.ui.components.CameraPermissionGate
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.InfoBanner
import com.madix.beam.ui.components.ScannerOverlay
import kotlinx.coroutines.delay

/** Scans the receiver's pairing code, then hands over to the broadcast screen. */
@Composable
fun SendPairingScreen(viewModel: SendViewModel, onBack: () -> Unit, onPaired: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var torch by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<PairingScanResult?>(null) }

    LaunchedEffect(Unit) { viewModel.resetPairing() }

    LaunchedEffect(state.phase) {
        if (state.phase !is SendPhase.Composing) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onPaired()
        }
    }

    LaunchedEffect(feedback) {
        if (feedback != null && feedback != PairingScanResult.ACCEPTED) {
            delay(2500)
            feedback = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPermissionGate(rationale = stringResource(R.string.camera_rationale_pairing)) {
            QrCameraView(
                onCodes = { codes ->
                    val result = viewModel.onPairingCodes(codes)
                    if (result != null) feedback = result
                },
                torchEnabled = torch,
                modifier = Modifier.fillMaxSize(),
            )
            ScannerOverlay(centerFraction = 0.42f)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f))) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { torch = !torch }, colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f))) {
                Icon(
                    if (torch) Icons.Rounded.FlashlightOff else Icons.Rounded.FlashlightOn,
                    contentDescription = stringResource(R.string.action_torch),
                    tint = Color.White,
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            when (feedback) {
                PairingScanResult.NOT_PAIRING -> InfoBanner(stringResource(R.string.pairing_not_pairing_code), kind = BannerKind.WARNING)
                PairingScanResult.INVALID -> InfoBanner(stringResource(R.string.pairing_invalid_code), kind = BannerKind.ERROR)
                else -> Unit
            }
            Spacer(Modifier.height(10.dp))
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), MaterialTheme.shapes.large),
            ) {
                Text(stringResource(R.string.pairing_scan_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.pairing_scan_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .width(8.dp)
                            .height(8.dp)
                            .background(MaterialTheme.colorScheme.secondary, CircleShape),
                    )
                    Text(
                        stringResource(R.string.pairing_scan_searching),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}
