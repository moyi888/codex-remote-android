package dev.codexremote.app.scanner

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.Executors

@Composable
fun PairingScanner(
    onInvitation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember(lifecycleOwner) { Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pairing-qr-analyzer").apply { isDaemon = true }
    } }
    val currentOnInvitation by rememberUpdatedState(onInvitation)
    val analyzer = remember(lifecycleOwner) {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        PairingQrAnalyzer(BarcodeScanning.getClient(options)) { currentOnInvitation(it) }
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(analyzer, analysisExecutor) {
        onDispose {
            analyzer.close()
            analysisExecutor.shutdownNow()
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val view = previewView
        if (view == null) return@DisposableEffect onDispose { }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var disposed = false
        var cameraProvider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var pendingAnalysis: ImageAnalysis? = null
        cameraProviderFuture.addListener({
            if (disposed) return@addListener
            try {
                val provider = cameraProviderFuture.get()
                val activePreview = Preview.Builder().build().also {
                    it.surfaceProvider = view.surfaceProvider
                }
                val activeAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }
                pendingAnalysis = activeAnalysis
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    activePreview,
                    activeAnalysis,
                )
                cameraProvider = provider
                preview = activePreview
                analysis = activeAnalysis
            } catch (_: Exception) {
                pendingAnalysis?.clearAnalyzer()
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            cameraProvider?.unbind(*listOfNotNull(preview, analysis).toTypedArray())
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { activityContext ->
                PreviewView(activityContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        ScannerFrame(Modifier.fillMaxSize())
    }
}

@Composable
private fun ScannerFrame(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val edge = size.minDimension * 0.68f
        val topLeft = Offset((size.width - edge) / 2f, (size.height - edge) / 2f)
        drawRoundRect(
            color = Color.White,
            topLeft = topLeft,
            size = Size(edge, edge),
            cornerRadius = CornerRadius(24f, 24f),
            style = Stroke(width = 6f),
        )
    }
}
