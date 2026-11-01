package com.madix.beam.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madix.beam.R
import com.madix.beam.ui.components.LightSystemBarIcons
import com.madix.beam.core.AppContainer
import com.madix.beam.ui.components.MadixLogo
import com.madix.beam.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Animated brand introduction shown on every cold start. */
@Composable
fun SplashScreen(container: AppContainer, onFinished: (onboardingDone: Boolean) -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    val logoScale = remember { Animatable(0.55f) }
    val logoAlpha = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    val titleOffset = remember { Animatable(24f) }
    val footerAlpha = remember { Animatable(0f) }

    val transition = rememberInfiniteTransition(label = "splash")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "wave",
    )

    LightSystemBarIcons()
    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(450)) }
        logoScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
        launch { titleOffset.animateTo(0f, tween(550)) }
        titleAlpha.animateTo(1f, tween(550))
        footerAlpha.animateTo(1f, tween(400))
        delay(650)
        val settings = runCatching { container.settings.current() }.getOrDefault(container.initialSettings)
        finish(settings.onboardingDone)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.Ink, Brand.Violet800, Brand.Night))),
        contentAlignment = Alignment.Center,
    ) {
        // Expanding rings evoke light pulses travelling between two screens.
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension * 0.16f
            for (i in 0 until 3) {
                val phase = (wave + i / 3f) % 1f
                val radius = base + phase * size.minDimension * 0.42f
                drawCircle(
                    color = Brand.Cyan300.copy(alpha = (1f - phase) * 0.35f * logoAlpha.value),
                    radius = radius,
                    center = center.copy(y = center.y - 40.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MadixLogo(
                size = 132.dp,
                modifier = Modifier
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value),
            )
            Spacer(Modifier.height(28.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .alpha(titleAlpha.value)
                    .graphicsLayer { translationY = titleOffset.value.dp.toPx() },
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.splash_tagline),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .alpha(footerAlpha.value),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = Brand.Cyan300, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.splash_footer),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
