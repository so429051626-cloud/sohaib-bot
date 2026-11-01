package com.madix.beam.ui.settings

import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madix.beam.BuildConfig
import com.madix.beam.MadixApp
import com.madix.beam.R
import com.madix.beam.core.AppContainer
import com.madix.beam.data.settings.AppSettings
import com.madix.beam.data.settings.ImageQuality
import com.madix.beam.data.settings.ThemeMode
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.session.TransferProfile
import com.madix.beam.security.BiometricGate
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.GradientIcon
import com.madix.beam.ui.components.MadixTopBar
import com.madix.beam.ui.components.SectionHeader
import com.madix.beam.ui.send.modeLabel
import com.madix.beam.ui.send.profileLabel
import com.madix.beam.ui.send.qualityLabel
import com.madix.beam.util.Format
import com.madix.beam.util.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsDialog { THEME, LANGUAGE, SECURITY, PROFILE, QUALITY, DEVICE_NAME, CLEAR_FILES, CLEAR_HISTORY }

private enum class AppLanguage(val tag: String) { SYSTEM(""), ARABIC("ar"), ENGLISH("en") }

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onAbout: () -> Unit, onPrivacy: () -> Unit) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(container.initialSettings)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var storageBytes by remember { mutableLongStateOf(-1L) }
    val language = remember {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        when {
            tags.startsWith("ar") -> AppLanguage.ARABIC
            tags.startsWith("en") -> AppLanguage.ENGLISH
            else -> AppLanguage.SYSTEM
        }
    }

    LaunchedEffect(Unit) {
        storageBytes = withContext(Dispatchers.IO) { container.files.totalSize() }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settings.update(transform) }
    }

    val lockTitle = stringResource(R.string.lock_prompt_title)
    val lockSubtitle = stringResource(R.string.settings_lock_confirm)

    fun toggleAppLock(enable: Boolean) {
        if (!enable) {
            update { it.copy(appLockEnabled = false) }
            return
        }
        val activity = context.findActivity() as? FragmentActivity ?: return
        if (!BiometricGate.isAvailable(activity)) {
            Toast.makeText(context, R.string.settings_lock_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        BiometricGate.authenticate(
            activity = activity,
            title = lockTitle,
            subtitle = lockSubtitle,
            onSuccess = { update { it.copy(appLockEnabled = true) } },
            onFailure = {},
        )
    }

    when (dialog) {
        SettingsDialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { stringResource(themeLabel(it)) },
            onSelect = { mode ->
                dialog = null
                scope.launch {
                    container.settings.update { it.copy(themeMode = mode) }
                    MadixApp.applyThemeMode(mode)
                }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.LANGUAGE -> ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = AppLanguage.entries,
            selected = language,
            label = {
                when (it) {
                    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                    AppLanguage.ARABIC -> "العربية"
                    AppLanguage.ENGLISH -> "English"
                }
            },
            onSelect = { choice ->
                dialog = null
                val locales = if (choice.tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(choice.tag)
                AppCompatDelegate.setApplicationLocales(locales)
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.SECURITY -> ChoiceDialog(
            title = stringResource(R.string.settings_default_security),
            options = listOf(SecurityMode.PAIRED, SecurityMode.PASSWORD, SecurityMode.OPEN),
            selected = settings.defaultSecurity,
            label = { stringResource(modeLabel(it)) },
            onSelect = { mode ->
                dialog = null
                update { it.copy(defaultSecurity = mode) }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.PROFILE -> ChoiceDialog(
            title = stringResource(R.string.settings_default_profile),
            options = TransferProfile.entries,
            selected = settings.profile,
            label = { stringResource(profileLabel(it)) },
            onSelect = { profile ->
                dialog = null
                update { it.copy(profile = profile) }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.QUALITY -> ChoiceDialog(
            title = stringResource(R.string.settings_photo_quality),
            options = ImageQuality.entries,
            selected = settings.imageQuality,
            label = { stringResource(qualityLabel(it)) },
            onSelect = { quality ->
                dialog = null
                update { it.copy(imageQuality = quality) }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.DEVICE_NAME -> DeviceNameDialog(
            initial = container.settings.effectiveDeviceName(settings),
            onSave = { name ->
                dialog = null
                update { it.copy(deviceName = name) }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.CLEAR_FILES -> ConfirmDialog(
            title = stringResource(R.string.settings_clear_files),
            body = stringResource(R.string.settings_clear_files_body),
            onConfirm = {
                dialog = null
                scope.launch {
                    storageBytes = withContext(Dispatchers.IO) {
                        container.files.deleteAll()
                        container.files.totalSize()
                    }
                }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.CLEAR_HISTORY -> ConfirmDialog(
            title = stringResource(R.string.history_clear_title),
            body = stringResource(R.string.history_clear_body),
            onConfirm = {
                dialog = null
                scope.launch { container.history.clear() }
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MadixTopBar(title = stringResource(R.string.settings_title), onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
            ) {
                SectionHeader(stringResource(R.string.settings_section_appearance))
                SettingsGroup {
                    SettingRow(Icons.Rounded.DarkMode, stringResource(R.string.settings_theme), stringResource(themeLabel(settings.themeMode))) {
                        dialog = SettingsDialog.THEME
                    }
                    SettingRow(
                        Icons.Rounded.Language,
                        stringResource(R.string.settings_language),
                        when (language) {
                            AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                            AppLanguage.ARABIC -> "العربية"
                            AppLanguage.ENGLISH -> "English"
                        },
                    ) { dialog = SettingsDialog.LANGUAGE }
                }

                SectionHeader(stringResource(R.string.settings_section_security))
                SettingsGroup {
                    SwitchRow(
                        Icons.Rounded.Fingerprint,
                        stringResource(R.string.settings_app_lock),
                        stringResource(R.string.settings_app_lock_body),
                        settings.appLockEnabled,
                        ::toggleAppLock,
                    )
                    SwitchRow(
                        Icons.Rounded.Screenshot,
                        stringResource(R.string.settings_secure_screen),
                        stringResource(R.string.settings_secure_screen_body),
                        settings.secureScreen,
                    ) { enabled -> update { it.copy(secureScreen = enabled) } }
                    SettingRow(
                        Icons.Rounded.Shield,
                        stringResource(R.string.settings_default_security),
                        stringResource(modeLabel(settings.defaultSecurity)),
                    ) { dialog = SettingsDialog.SECURITY }
                }

                SectionHeader(stringResource(R.string.settings_section_transfer))
                SettingsGroup {
                    SettingRow(Icons.Rounded.Speed, stringResource(R.string.settings_default_profile), stringResource(profileLabel(settings.profile))) {
                        dialog = SettingsDialog.PROFILE
                    }
                    SettingRow(Icons.Rounded.Image, stringResource(R.string.settings_photo_quality), stringResource(qualityLabel(settings.imageQuality))) {
                        dialog = SettingsDialog.QUALITY
                    }
                    SettingRow(Icons.Rounded.Badge, stringResource(R.string.settings_device_name), container.settings.effectiveDeviceName(settings)) {
                        dialog = SettingsDialog.DEVICE_NAME
                    }
                    SwitchRow(
                        Icons.Rounded.BrightnessHigh,
                        stringResource(R.string.settings_brightness),
                        stringResource(R.string.settings_brightness_body),
                        settings.maxBrightness,
                    ) { enabled -> update { it.copy(maxBrightness = enabled) } }
                    SwitchRow(
                        Icons.Rounded.Vibration,
                        stringResource(R.string.settings_haptics),
                        stringResource(R.string.settings_haptics_body),
                        settings.haptics,
                    ) { enabled -> update { it.copy(haptics = enabled) } }
                }

                SectionHeader(stringResource(R.string.settings_section_storage))
                SettingsGroup {
                    SettingRow(
                        Icons.Rounded.CleaningServices,
                        stringResource(R.string.settings_clear_files),
                        if (storageBytes < 0) "…" else stringResource(R.string.settings_storage_used, Format.bytes(context, storageBytes)),
                    ) { dialog = SettingsDialog.CLEAR_FILES }
                    SettingRow(Icons.Rounded.DeleteSweep, stringResource(R.string.history_clear_title), stringResource(R.string.settings_clear_history_body)) {
                        dialog = SettingsDialog.CLEAR_HISTORY
                    }
                }

                SectionHeader(stringResource(R.string.settings_section_about))
                SettingsGroup {
                    SettingRow(Icons.Rounded.Info, stringResource(R.string.about_title), stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), onClick = onAbout)
                    SettingRow(Icons.Rounded.Policy, stringResource(R.string.privacy_title), stringResource(R.string.settings_privacy_body), onClick = onPrivacy)
                }
                Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        content()
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientIcon(icon, size = 38.dp, iconSize = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch, onClick = { onChange(!checked) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientIcon(icon, size = 38.dp, iconSize = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DeviceNameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_device_name)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_device_name_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
