package com.madix.beam.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.madix.beam.R
import com.madix.beam.security.BiometricGate
import com.madix.beam.ui.components.AuroraBackground
import com.madix.beam.ui.components.BannerKind
import com.madix.beam.ui.components.GradientButton
import com.madix.beam.ui.components.InfoBanner
import com.madix.beam.ui.components.MadixLogo
import com.madix.beam.util.findActivity
import kotlinx.coroutines.delay

/** Opaque overlay that hides the interface until the user authenticates. */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? FragmentActivity }
    var error by remember { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.lock_prompt_title)
    val subtitle = stringResource(R.string.lock_prompt_subtitle)
    val unavailable = stringResource(R.string.lock_unavailable)

    fun authenticate() {
        val host = activity ?: return
        if (!BiometricGate.isAvailable(host)) {
            // The screen lock was removed after app lock was enabled; nothing can be verified.
            error = unavailable
            onUnlocked()
            return
        }
        BiometricGate.authenticate(
            activity = host,
            title = title,
            subtitle = subtitle,
            onSuccess = {
                error = null
                onUnlocked()
            },
            onFailure = { message -> error = message },
        )
    }

    BackHandler { activity?.moveTaskToBack(true) }
    LaunchedEffect(Unit) {
        // Let the activity reach the resumed state before the system prompt is attached.
        delay(350)
        authenticate()
    }

    AuroraBackground(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MadixLogo(size = 112.dp)
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.lock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            error?.let {
                InfoBanner(it, kind = BannerKind.WARNING)
                Spacer(Modifier.height(16.dp))
            }
            GradientButton(
                text = stringResource(R.string.lock_unlock),
                icon = Icons.Rounded.Fingerprint,
                onClick = ::authenticate,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
