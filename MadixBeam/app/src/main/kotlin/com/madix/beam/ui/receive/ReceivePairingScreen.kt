package com.madix.beam.ui.receive

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
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madix.beam.R
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.GlassCard
import com.madix.beam.ui.components.GradientButton
import com.madix.beam.ui.components.MadixTopBar
import com.madix.beam.ui.components.Pill
import com.madix.beam.ui.home.QuickAction
import com.madix.beam.ui.navigation.PendingAction
import com.madix.beam.ui.theme.MadixTheme

/** Shows the receiver's one-time pairing code before scanning starts. */
@Composable
fun ReceivePairingScreen(viewModel: ReceiveViewModel, onBack: () -> Unit, onStartScanning: () -> Unit) {
    val pairingCode by viewModel.pairingCode.collectAsStateWithLifecycle()

    // "Scan" from the home screen skips straight to the camera.
    LaunchedEffect(Unit) {
        if (PendingAction.quickAction.value == QuickAction.SCAN) {
            PendingAction.quickAction.value = null
            onStartScanning()
        }
    }

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            MadixTopBar(title = stringResource(R.string.receive_title), onBack = onBack)
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
                        val image = pairingCode
                        if (image == null) {
                            CircularProgressIndicator()
                        } else {
                            Image(
                                bitmap = image,
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
                        Pill(viewModel.deviceName, icon = Icons.Rounded.PhoneAndroid)
                        Pill(stringResource(R.string.pairing_single_use), icon = Icons.Rounded.Timer, color = MadixTheme.colors.success)
                    }
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
            GradientButton(
                text = stringResource(R.string.receive_start),
                icon = Icons.Rounded.QrCodeScanner,
                onClick = onStartScanning,
                brush = MadixTheme.colors.receiveGradient,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
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
