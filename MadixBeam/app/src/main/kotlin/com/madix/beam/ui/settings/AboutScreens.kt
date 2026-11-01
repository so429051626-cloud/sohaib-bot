package com.madix.beam.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.madix.beam.BuildConfig
import com.madix.beam.R
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.GradientIcon
import com.madix.beam.ui.components.MadixLogo
import com.madix.beam.ui.components.MadixTopBar
import com.madix.beam.ui.components.SectionHeader
import com.madix.beam.ui.components.SoftButton

private data class Feature(val icon: ImageVector, @StringRes val title: Int, @StringRes val body: Int)

private val features = listOf(
    Feature(Icons.Rounded.WifiOff, R.string.feature_offline_title, R.string.feature_offline_body),
    Feature(Icons.Rounded.Shield, R.string.feature_e2e_title, R.string.feature_e2e_body),
    Feature(Icons.Rounded.AllInclusive, R.string.feature_fountain_title, R.string.feature_fountain_body),
    Feature(Icons.Rounded.Verified, R.string.feature_integrity_title, R.string.feature_integrity_body),
    Feature(Icons.Rounded.Key, R.string.feature_password_title, R.string.feature_password_body),
    Feature(Icons.Rounded.Fingerprint, R.string.feature_lock_title, R.string.feature_lock_body),
)

private val licenses = listOf(
    "AndroidX, Jetpack Compose, CameraX — Apache License 2.0",
    "ZXing — Apache License 2.0",
    "Google ML Kit Barcode Scanning — ML Kit Terms of Service",
    "Coil — Apache License 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization — Apache License 2.0",
    "IBM Plex Sans Arabic — SIL Open Font License 1.1",
)

@Composable
fun AboutScreen(onBack: () -> Unit, onPrivacy: () -> Unit) {
    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MadixTopBar(title = stringResource(R.string.about_title), onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                MadixLogo(size = 104.dp)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.about_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                SectionHeader(stringResource(R.string.about_features))
                features.forEach { feature ->
                    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentPadding = PaddingValues(16.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            GradientIcon(feature.icon, size = 40.dp, iconSize = 22.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(feature.title), style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    stringResource(feature.body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                SectionHeader(stringResource(R.string.about_technology))
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    Text(stringResource(R.string.about_technology_body), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                SectionHeader(stringResource(R.string.about_licenses))
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    licenses.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                SoftButton(text = stringResource(R.string.privacy_title), onClick = onPrivacy, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.about_copyright),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val sections = listOf(
        R.string.privacy_section_data_title to R.string.privacy_section_data_body,
        R.string.privacy_section_network_title to R.string.privacy_section_network_body,
        R.string.privacy_section_camera_title to R.string.privacy_section_camera_body,
        R.string.privacy_section_storage_title to R.string.privacy_section_storage_body,
        R.string.privacy_section_security_title to R.string.privacy_section_security_body,
        R.string.privacy_section_contact_title to R.string.privacy_section_contact_body,
    )
    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MadixTopBar(title = stringResource(R.string.privacy_title), onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
            ) {
                Text(
                    stringResource(R.string.privacy_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                sections.forEach { (title, body) ->
                    GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = PaddingValues(18.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    stringResource(R.string.privacy_updated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}
