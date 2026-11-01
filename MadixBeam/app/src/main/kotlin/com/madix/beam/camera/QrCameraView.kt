package com.madix.beam.camera

import android.util.Size
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Live camera preview that decodes QR codes with the on-device ML Kit model.
 *
 * [onCodes] is invoked on a background thread with the raw text of every QR code found in a
 * frame; callers must hand the values over to their own processing thread.
 */
@Composable
fun QrCameraView(
    onCodes: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    torchEnabled: Boolean = false,
    zoomRatio: Float = 1f,
    onCameraReady: (Camera) -> Unit = {},
    onError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCodes by rememberUpdatedState(onCodes)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnReady by rememberUpdatedState(onCameraReady)

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        // ML Kit may complete a task after disposal; late callbacks are dropped instead of
        // being rejected by the terminated executor.
        val callbackExecutor = Executor { command ->
            if (!executor.isShutdown) runCatching { executor.execute(command) }
        }
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, QrAnalyzer(scanner, callbackExecutor) { currentOnCodes(it) })
                provider.unbindAll()
                val bound = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                tuneExposureForScreens(bound)
                camera = bound
                currentOnReady(bound)
            } catch (e: Exception) {
                currentOnError(e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            camera = null
            runCatching {
                if (providerFuture.isDone) providerFuture.get().unbindAll()
            }
            scanner.close()
            executor.shutdown()
            runCatching { executor.awaitTermination(200, TimeUnit.MILLISECONDS) }
        }
    }

    LaunchedEffect(camera, torchEnabled) {
        val active = camera ?: return@LaunchedEffect
        if (active.cameraInfo.hasFlashUnit()) active.cameraControl.enableTorch(torchEnabled)
    }

    LaunchedEffect(camera, zoomRatio) {
        val active = camera ?: return@LaunchedEffect
        val state = active.cameraInfo.zoomState.value
        val min = state?.minZoomRatio ?: 1f
        val max = state?.maxZoomRatio ?: 1f
        active.cameraControl.setZoomRatio(zoomRatio.coerceIn(min, max))
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
        update = { view ->
            view.setOnTouchListener { v, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    val active = camera
                    if (active != null) {
                        val point = view.meteringPointFactory.createPoint(event.x, event.y)
                        active.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build(),
                        )
                    }
                    v.performClick()
                }
                true
            }
        },
    )
}

/**
 * Screens are bright, so automatic exposure tends to wash out the light modules of a QR code.
 * Lowering the exposure by about one stop keeps module edges crisp when reading a display.
 */
private fun tuneExposureForScreens(camera: Camera) {
    runCatching {
        val exposure = camera.cameraInfo.exposureState
        if (!exposure.isExposureCompensationSupported) return
        val step = exposure.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val range = exposure.exposureCompensationRange
        val index = (SCREEN_EXPOSURE_EV / step).roundToInt().coerceIn(range.lower, range.upper)
        camera.cameraControl.setExposureCompensationIndex(index)
    }
}

private const val SCREEN_EXPOSURE_EV = -1f

private class QrAnalyzer(
    private val scanner: BarcodeScanner,
    private val executor: Executor,
    private val onCodes: (List<String>) -> Unit,
) : ImageAnalysis.Analyzer {

    @OptIn(markerClass = [ExperimentalGetImage::class])
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener(executor) { barcodes ->
                val values = barcodes.mapNotNull { it.rawValue }
                if (values.isNotEmpty()) onCodes(values)
            }
            // Runs after success, failure and cancellation alike.
            .addOnCompleteListener(executor) { image.close() }
    }
}
