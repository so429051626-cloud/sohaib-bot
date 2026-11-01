package com.madix.beam.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.madix.beam.R
import com.madix.beam.ui.theme.Brand
import com.madix.beam.util.findActivity

/** Keeps the display on while composed and optionally raises the brightness to maximum. */
@Composable
fun KeepScreenAwake(maxBrightness: Boolean) {
    val context = LocalContext.current
    DisposableEffect(maxBrightness) {
        val window = context.findActivity()?.window
        val original = window?.attributes?.screenBrightness
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (maxBrightness && window != null) {
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null && original != null) {
                window.attributes = window.attributes.apply { screenBrightness = original }
            }
        }
    }
}

/** Requests the camera permission and renders [content] once it is granted. */
@Composable
fun CameraPermissionGate(rationale: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun isGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(isGranted()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        requested = true
    }

    LaunchedEffect(Unit) {
        if (!granted && !requested) launcher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = isGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GradientIcon(Icons.Rounded.CameraAlt, size = 84.dp)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            GradientButton(
                text = stringResource(R.string.camera_permission_grant),
                onClick = {
                    val activity = context.findActivity()
                    val canAsk = activity == null || !requested ||
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
                    if (canAsk) {
                        launcher.launch(Manifest.permission.CAMERA)
                    } else {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Dims the camera image outside a rounded viewfinder and draws glowing corners with a
 * sweeping beam. [centerFraction] positions the viewfinder vertically.
 */
@Composable
fun ScannerOverlay(
    modifier: Modifier = Modifier,
    centerFraction: Float = 0.4f,
    sizeFraction: Float = 0.74f,
    scanning: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "scanner")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    Canvas(modifier.fillMaxSize()) {
        val side = minOf(size.width, size.height) * sizeFraction
        val left = (size.width - side) / 2f
        val top = (size.height * centerFraction - side / 2f).coerceAtLeast(24.dp.toPx())
        val frame = Rect(left, top, left + side, top + side)
        val radius = 28.dp.toPx()

        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRoundRect(RoundRect(frame, CornerRadius(radius)))
        }
        drawPath(dim, Color.Black.copy(alpha = 0.55f))

        val strokeWidth = 5.dp.toPx()
        val arm = side * 0.16f
        val accent = Brand.Cyan300
        val corners = listOf(
            Triple(Offset(frame.left, frame.top), 180f, Offset(1f, 1f)),
            Triple(Offset(frame.right, frame.top), 270f, Offset(-1f, 1f)),
            Triple(Offset(frame.right, frame.bottom), 0f, Offset(-1f, -1f)),
            Triple(Offset(frame.left, frame.bottom), 90f, Offset(1f, -1f)),
        )
        for ((corner, startAngle, direction) in corners) {
            val cx = corner.x + direction.x * radius
            val cy = corner.y + direction.y * radius
            drawArc(
                color = accent,
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
            drawLine(accent, Offset(cx, corner.y), Offset(corner.x + direction.x * arm, corner.y), strokeWidth, StrokeCap.Round)
            drawLine(accent, Offset(corner.x, cy), Offset(corner.x, corner.y + direction.y * arm), strokeWidth, StrokeCap.Round)
        }

        if (scanning) {
            val y = frame.top + 16.dp.toPx() + (frame.height - 32.dp.toPx()) * sweep
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, accent, Color.Transparent),
                    startX = frame.left,
                    endX = frame.right,
                ),
                start = Offset(frame.left + 18.dp.toPx(), y),
                end = Offset(frame.right - 18.dp.toPx(), y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
