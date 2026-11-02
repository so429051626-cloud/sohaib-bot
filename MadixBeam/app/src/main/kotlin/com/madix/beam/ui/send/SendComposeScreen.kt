package com.madix.beam.ui.send

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.madix.beam.R
import com.madix.beam.core.AppContainer
import com.madix.beam.data.settings.ImageQuality
import com.madix.beam.data.settings.TransferMethod
import com.madix.beam.wifi.WifiPermissions
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.QrCode2
import com.madix.beam.data.source.PickedItem
import com.madix.beam.protocol.Protocol
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.session.TransferProfile
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.BannerKind
import com.madix.beam.ui.components.EmptyState
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.GradientButton
import com.madix.beam.ui.components.GradientIcon
import com.madix.beam.ui.components.InfoBanner
import com.madix.beam.ui.components.MadixTopBar
import com.madix.beam.ui.components.SectionHeader
import com.madix.beam.ui.components.SoftButton
import com.madix.beam.ui.components.iconForMime
import com.madix.beam.ui.home.QuickAction
import com.madix.beam.ui.navigation.PendingAction
import com.madix.beam.ui.theme.MadixTheme
import com.madix.beam.util.Format

@Composable
fun SendComposeScreen(
    viewModel: SendViewModel,
    container: AppContainer,
    onBack: () -> Unit,
    onPairingRequired: () -> Unit,
    onBroadcast: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showTextDialog by rememberSaveable { mutableStateOf(false) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.addUris(uris)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addUris(uris)
    }
    // Wi-Fi Direct needs a runtime permission; scanning proceeds either way and the transfer
    // falls back to a shared network or QR codes when the permission is refused.
    val wifiPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPairingRequired()
    }
    fun pickMedia() = mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    fun pickFiles() = filePicker.launch(arrayOf("*/*"))

    LaunchedEffect(pendingShare) {
        if (pendingShare != null) container.consumeShare()?.let(viewModel::applyShare)
    }
    LaunchedEffect(Unit) {
        when (PendingAction.quickAction.value) {
            QuickAction.MEDIA -> pickMedia()
            QuickAction.FILES -> pickFiles()
            QuickAction.TEXT -> showTextDialog = true
            else -> Unit
        }
        if (PendingAction.quickAction.value != QuickAction.SCAN) PendingAction.quickAction.value = null
    }

    if (showTextDialog) {
        TextComposerDialog(
            onDismiss = { showTextDialog = false },
            onConfirm = { text ->
                viewModel.addText(text)
                showTextDialog = false
            },
        )
    }

    val failure = (state.phase as? SendPhase.Failed)?.reason
    if (failure != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissFailure,
            confirmButton = { TextButton(onClick = viewModel::dismissFailure) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(stringResource(R.string.send_failed_title)) },
            text = { Text(stringResource(failureMessage(failure))) },
        )
    }

    AuroraBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            MadixTopBar(
                title = stringResource(R.string.send_title),
                onBack = onBack,
                actions = {
                    if (state.items.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearItems) { Text(stringResource(R.string.action_clear)) }
                    }
                },
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AddButton(Icons.Rounded.PhotoLibrary, stringResource(R.string.add_media), Modifier.weight(1f), ::pickMedia)
                        AddButton(Icons.Rounded.FolderOpen, stringResource(R.string.add_files), Modifier.weight(1f), ::pickFiles)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AddButton(Icons.Rounded.Notes, stringResource(R.string.add_text), Modifier.weight(1f)) { showTextDialog = true }
                        AddButton(Icons.Rounded.ContentPaste, stringResource(R.string.add_clipboard), Modifier.weight(1f)) {
                            clipboard.getText()?.text?.let { viewModel.addText(it) }
                        }
                    }
                    }
                }

                if (state.items.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Send,
                            title = stringResource(R.string.send_empty_title),
                            message = stringResource(R.string.send_empty_body),
                        )
                    }
                } else {
                    item {
                        SectionHeader(pluralStringResource(R.plurals.selected_items, state.items.size, state.items.size))
                    }
                    items(state.items, key = { it.id }) { item ->
                        PickedItemRow(item, onRemove = { viewModel.remove(item.id) })
                    }
                }

                item {
                    Column {
                        SectionHeader(stringResource(R.string.send_method))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecurityOption(
                                selected = state.method == TransferMethod.WIFI,
                                icon = Icons.Rounded.Bolt,
                                title = stringResource(R.string.method_wifi),
                                body = stringResource(R.string.method_wifi_body),
                                badge = stringResource(R.string.method_fastest),
                                onClick = { viewModel.setMethod(TransferMethod.WIFI) },
                            )
                            SecurityOption(
                                selected = state.method == TransferMethod.QR,
                                icon = Icons.Rounded.QrCode2,
                                title = stringResource(R.string.method_qr),
                                body = stringResource(R.string.method_qr_body),
                                onClick = { viewModel.setMethod(TransferMethod.QR) },
                            )
                        }
                        if (state.method == TransferMethod.WIFI) {
                            Spacer(Modifier.height(10.dp))
                            InfoBanner(stringResource(R.string.method_wifi_note), kind = BannerKind.SUCCESS)
                        }
                    }
                }

                if (state.method == TransferMethod.QR) item {
                    Column {
                    SectionHeader(stringResource(R.string.send_security))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecurityOption(
                            selected = state.security == SecurityMode.PAIRED,
                            icon = Icons.Rounded.Shield,
                            title = stringResource(R.string.security_paired),
                            body = stringResource(R.string.security_paired_body),
                            badge = stringResource(R.string.security_recommended),
                            onClick = { viewModel.setSecurity(SecurityMode.PAIRED) },
                        )
                        SecurityOption(
                            selected = state.security == SecurityMode.PASSWORD,
                            icon = Icons.Rounded.Key,
                            title = stringResource(R.string.security_password),
                            body = stringResource(R.string.security_password_body),
                            onClick = { viewModel.setSecurity(SecurityMode.PASSWORD) },
                        )
                        SecurityOption(
                            selected = state.security == SecurityMode.OPEN,
                            icon = Icons.Rounded.LockOpen,
                            title = stringResource(R.string.security_open),
                            body = stringResource(R.string.security_open_body),
                            onClick = { viewModel.setSecurity(SecurityMode.OPEN) },
                        )
                    }
                    if (state.security == SecurityMode.PASSWORD) {
                        Spacer(Modifier.height(12.dp))
                        PasswordField(state.password, viewModel::setPassword, state.password.isNotEmpty() && !state.passwordValid)
                    }
                    }
                }

                if (state.method == TransferMethod.QR) item {
                    Column {
                        SectionHeader(stringResource(R.string.send_speed))
                        ProfileSelector(state.profile, viewModel::setProfile)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(profileDescription(state.profile)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.hasImages && state.method == TransferMethod.QR) {
                    item {
                        Column {
                        SectionHeader(stringResource(R.string.send_photo_quality))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ImageQuality.entries.forEach { quality ->
                                FilterChip(
                                    selected = state.imageQuality == quality,
                                    onClick = { viewModel.setImageQuality(quality) },
                                    label = { Text(stringResource(qualityLabel(quality))) },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.send_photo_quality_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        }
                    }
                }

                if (state.tooLarge) {
                    item {
                        InfoBanner(
                            stringResource(R.string.send_too_large, Format.bytes(context, Protocol.MAX_PAYLOAD_BYTES)),
                            kind = BannerKind.ERROR,
                        )
                    }
                } else if (state.method == TransferMethod.QR && state.totalBytes > LARGE_TRANSFER_BYTES) {
                    item {
                        InfoBanner(stringResource(R.string.send_large_warning), kind = BannerKind.WARNING)
                    }
                }
            }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                if (state.items.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.send_summary,
                            pluralStringResource(R.plurals.item_count, state.items.size, state.items.size),
                            Format.bytes(context, state.totalBytes),
                            Format.duration(state.estimatedSeconds()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                val needsScan = state.method == TransferMethod.WIFI || state.security == SecurityMode.PAIRED
                GradientButton(
                    text = stringResource(
                        when {
                            state.method == TransferMethod.WIFI -> R.string.send_continue_scan
                            state.security == SecurityMode.PAIRED -> R.string.send_continue_pair
                            else -> R.string.send_start
                        },
                    ),
                    icon = if (needsScan) Icons.Rounded.QrCodeScanner else Icons.Rounded.Send,
                    enabled = state.canContinue,
                    onClick = {
                        when {
                            state.method == TransferMethod.WIFI && !WifiPermissions.granted(context) -> {
                                viewModel.resetPairing()
                                wifiPermissionLauncher.launch(WifiPermissions.required)
                            }
                            needsScan -> {
                                viewModel.resetPairing()
                                onPairingRequired()
                            }
                            else -> {
                                viewModel.beginTransfer()
                                onBroadcast()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private const val LARGE_TRANSFER_BYTES = 20L * 1024 * 1024

@Composable
private fun AddButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SoftButton(text = label, icon = icon, onClick = onClick, modifier = modifier, contentColor = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PickedItemRow(item: PickedItem, onRemove: () -> Unit) {
    val context = LocalContext.current
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if ((item.isImage || item.isVideo) && item.uri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(item.uri).size(192).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                GradientIcon(iconForMime(item.mimeType, item.isText), size = 52.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (item.isText) item.text.orEmpty().lineSequence().first() else item.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (item.isText) {
                        stringResource(R.string.item_text_message, Format.bytes(context, item.size))
                    } else if (item.size >= 0) {
                        Format.bytes(context, item.size)
                    } else {
                        stringResource(R.string.item_size_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove))
            }
        }
    }
}

@Composable
private fun SecurityOption(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = MadixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else colors.glass)
            .border(
                if (selected) BorderStroke(1.5.dp, colors.beamGradient) else BorderStroke(1.dp, colors.glassBorder),
                shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientIcon(icon, size = 42.dp, brush = if (selected) colors.beamGradient else colors.brandGradient)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.success,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.success.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, showError: Boolean) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.password_label)) },
        supportingText = {
            Text(
                stringResource(if (showError) R.string.password_too_short else R.string.password_hint, SendUiState.MIN_PASSWORD_LENGTH),
            )
        },
        isError = showError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
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
}

@Composable
private fun ProfileSelector(selected: TransferProfile, onSelect: (TransferProfile) -> Unit) {
    val profiles = TransferProfile.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        profiles.forEachIndexed { index, profile ->
            SegmentedButton(
                selected = profile == selected,
                onClick = { onSelect(profile) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = profiles.size),
                label = { Text(stringResource(profileLabel(profile)), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun TextComposerDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_TEXT_LENGTH) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                placeholder = { Text(stringResource(R.string.text_dialog_hint)) },
                shape = RoundedCornerShape(16.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_TEXT_LENGTH = 100_000

fun profileLabel(profile: TransferProfile): Int = when (profile) {
    TransferProfile.RELIABLE -> R.string.profile_reliable
    TransferProfile.BALANCED -> R.string.profile_balanced
    TransferProfile.FAST -> R.string.profile_fast
    TransferProfile.TURBO -> R.string.profile_turbo
}

fun profileDescription(profile: TransferProfile): Int = when (profile) {
    TransferProfile.RELIABLE -> R.string.profile_reliable_body
    TransferProfile.BALANCED -> R.string.profile_balanced_body
    TransferProfile.FAST -> R.string.profile_fast_body
    TransferProfile.TURBO -> R.string.profile_turbo_body
}

fun qualityLabel(quality: ImageQuality): Int = when (quality) {
    ImageQuality.ORIGINAL -> R.string.quality_original
    ImageQuality.HIGH -> R.string.quality_high
    ImageQuality.BALANCED -> R.string.quality_balanced
    ImageQuality.COMPACT -> R.string.quality_compact
    ImageQuality.INSTANT -> R.string.quality_instant
}

fun failureMessage(failure: SendFailure): Int = when (failure) {
    SendFailure.TOO_LARGE -> R.string.send_error_too_large
    SendFailure.UNREADABLE -> R.string.send_error_unreadable
    SendFailure.INVALID_PAIRING -> R.string.send_error_pairing
    SendFailure.STORAGE -> R.string.send_error_storage
    SendFailure.UNKNOWN -> R.string.send_error_unknown
}
