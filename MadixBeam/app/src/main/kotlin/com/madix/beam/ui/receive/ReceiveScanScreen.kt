package com.madix.beam.ui.receive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madix.beam.R
import com.madix.beam.ui.components.LightSystemBarIcons
import com.madix.beam.camera.QrCameraView
import com.madix.beam.core.AppContainer
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.session.ReceptionSnapshot
import com.madix.beam.ui.components.BannerKind
import com.madix.beam.ui.components.CameraPermissionGate
import com.madix.beam.ui.components.FileActions
import com.madix.beam.ui.components.GradientButton
import com.madix.beam.ui.components.InfoBanner
import com.madix.beam.ui.components.KeepScreenAwake
import com.madix.beam.ui.components.Pill
import com.madix.beam.ui.components.ProgressRing
import com.madix.beam.ui.components.ScannerOverlay
import com.madix.beam.ui.components.SoftButton
import com.madix.beam.ui.send.modeLabel
import com.madix.beam.ui.theme.MadixTheme
import com.madix.beam.util.Format

@Composable
fun ReceiveScanScreen(
    viewModel: ReceiveViewModel,
    container: AppContainer,
    onClose: () -> Unit,
    onCompleted: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(container.initialSettings)
    val haptics = LocalHapticFeedback.current
    var torch by remember { mutableStateOf(false) }
    var zoomed by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.snapshot
    val locked = snapshot.header != null
    val scanning = state.phase == ReceivePhase.Scanning

    LightSystemBarIcons()
    KeepScreenAwake(maxBrightness = false)

    LaunchedEffect(locked) {
        if (locked && settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(state.phase) {
        if (state.phase is ReceivePhase.Done) {
            if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onCompleted()
        }
    }

    state.foreignText?.let { text -> ForeignCodeDialog(text, onDismiss = viewModel::dismissForeign) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPermissionGate(rationale = stringResource(R.string.camera_rationale_receive)) {
            if (scanning) {
                QrCameraView(
                    onCodes = viewModel::onCodes,
                    torchEnabled = torch,
                    zoomRatio = if (zoomed) 2f else 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ScannerOverlay(centerFraction = 0.36f, sizeFraction = 0.8f, aspect = 0.62f, scanning = scanning)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val buttonColors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f))
            IconButton(onClick = onClose, colors = buttonColors) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { zoomed = !zoomed }, colors = buttonColors) {
                Icon(
                    if (zoomed) Icons.Rounded.ZoomOut else Icons.Rounded.ZoomIn,
                    contentDescription = stringResource(R.string.action_zoom),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { torch = !torch }, colors = buttonColors) {
                Icon(
                    if (torch) Icons.Rounded.FlashlightOff else Icons.Rounded.FlashlightOn,
                    contentDescription = stringResource(R.string.action_torch),
                    tint = Color.White,
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                AnimatedContent(
                    targetState = panelKind(state),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "panel",
                ) { kind ->
                    when (kind) {
                        PanelKind.SEARCHING -> SearchingPanel(state)
                        PanelKind.RECEIVING -> ReceivingPanel(snapshot, state.verificationCode)
                        PanelKind.PASSWORD -> PasswordPanel(
                            wrongAttempt = (state.phase as? ReceivePhase.NeedsPassword)?.wrongAttempt == true,
                            onSubmit = viewModel::submitPassword,
                        )
                        PanelKind.OPENING -> OpeningPanel((state.phase as? ReceivePhase.Opening)?.fraction ?: 0f)
                        PanelKind.FAILED -> FailedPanel(
                            reason = (state.phase as? ReceivePhase.Failed)?.reason ?: ReceiveFailure.UNKNOWN,
                            onRetry = viewModel::restart,
                            onClose = onClose,
                        )
                    }
                }
            }
        }
    }
}

private enum class PanelKind { SEARCHING, RECEIVING, PASSWORD, OPENING, FAILED }

private fun panelKind(state: ReceiveUiState): PanelKind = when (state.phase) {
    ReceivePhase.Scanning -> if (state.snapshot.header == null) PanelKind.SEARCHING else PanelKind.RECEIVING
    is ReceivePhase.NeedsPassword -> PanelKind.PASSWORD
    is ReceivePhase.Opening, is ReceivePhase.Done -> PanelKind.OPENING
    is ReceivePhase.Failed -> PanelKind.FAILED
}

@Composable
private fun SearchingPanel(state: ReceiveUiState) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.scan_searching_title), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.scan_searching_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.snapshot.wrongRecipientSeen) {
            Spacer(Modifier.height(12.dp))
            InfoBanner(stringResource(R.string.scan_wrong_recipient), kind = BannerKind.WARNING)
        }
        if (state.pairingCodeSeen) {
            Spacer(Modifier.height(12.dp))
            InfoBanner(stringResource(R.string.scan_pairing_code_seen), kind = BannerKind.INFO)
        }
    }
}

@Composable
private fun ReceivingPanel(snapshot: ReceptionSnapshot, verificationCode: String?) {
    val context = LocalContext.current
    val header = snapshot.header ?: return
    val collected = ((snapshot.recoveredBlocks + snapshot.pendingEquations).toLong() * header.blockSize)
        .coerceAtMost(header.cipherLength)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(progress = snapshot.progress, size = 92.dp, stroke = 9.dp) {
                Text(Format.percent(snapshot.progress), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.scan_receiving_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.scan_received_of, Format.bytes(context, collected), Format.bytes(context, header.cipherLength)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        R.string.scan_speed_eta,
                        Format.speed(context, snapshot.bytesPerSecond),
                        snapshot.etaSeconds?.let { Format.duration(it) } ?: "—",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Pill(
                    stringResource(modeLabel(header.mode)),
                    icon = when (header.mode) {
                        SecurityMode.PAIRED -> Icons.Rounded.Shield
                        SecurityMode.PASSWORD -> Icons.Rounded.Key
                        SecurityMode.OPEN -> Icons.Rounded.LockOpen
                    },
                    color = if (header.mode == SecurityMode.OPEN) MadixTheme.colors.warning else MadixTheme.colors.success,
                )
            }
        }
        if (verificationCode != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.scan_verification_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    verificationCode,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.scan_frames, snapshot.framesUseful, snapshot.framesDecoded),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PasswordPanel(wrongAttempt: Boolean, onSubmit: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.scan_password_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.scan_password_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(128) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = wrongAttempt,
            label = { Text(stringResource(R.string.password_label)) },
            supportingText = if (wrongAttempt) {
                { Text(stringResource(R.string.scan_password_wrong)) }
            } else {
                null
            },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onSubmit(password) }),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (visible) R.string.password_hide else R.string.password_show),
                    )
                }
            },
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.height(12.dp))
        GradientButton(
            text = stringResource(R.string.scan_password_unlock),
            icon = Icons.Rounded.LockOpen,
            enabled = password.isNotEmpty(),
            onClick = { onSubmit(password) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OpeningPanel(fraction: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProgressRing(progress = fraction, size = 72.dp, stroke = 7.dp, indeterminate = fraction <= 0f) {
            if (fraction > 0f) Text(Format.percent(fraction), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.scan_opening_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.scan_opening_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedPanel(reason: ReceiveFailure, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MadixTheme.colors.danger, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.scan_failed_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                when (reason) {
                    ReceiveFailure.INTEGRITY -> R.string.scan_failed_integrity
                    ReceiveFailure.STORAGE -> R.string.scan_failed_storage
                    ReceiveFailure.UNKNOWN -> R.string.scan_failed_unknown
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(stringResource(R.string.action_close), onClick = onClose, modifier = Modifier.weight(1f))
            GradientButton(stringResource(R.string.scan_retry), onClick = onRetry, icon = Icons.Rounded.Refresh, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ForeignCodeDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isLink = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.foreign_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.foreign_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(text.take(2_000), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (isLink) {
                TextButton(onClick = {
                    FileActions.openLink(context, text)
                    onDismiss()
                }) { Text(stringResource(R.string.action_open_link)) }
            } else {
                TextButton(onClick = {
                    FileActions.copyText(context, text)
                    onDismiss()
                }) { Text(stringResource(R.string.action_copy)) }
            }
        },
        dismissButton = {
            Row {
                if (isLink) {
                    TextButton(onClick = {
                        FileActions.copyText(context, text)
                        onDismiss()
                    }) { Text(stringResource(R.string.action_copy)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}
