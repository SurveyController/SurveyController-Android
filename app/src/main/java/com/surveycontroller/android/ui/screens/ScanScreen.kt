package com.surveycontroller.android.ui.screens

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.surveycontroller.android.ui.components.StudioEmblem
import com.surveycontroller.android.ui.components.StudioTopBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScanScreen(onResult: (String) -> Unit, onBack: () -> Unit) {
    val permission = rememberPermissionState(Manifest.permission.CAMERA)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { StudioTopBar("扫描二维码", onBack) },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
            if (permission.status.isGranted) {
                CameraPreview(onResult)
                ScannerReticle()
                Surface(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
                    shape = MaterialTheme.shapes.large,
                    color = Color(0xE61F222B),
                    contentColor = Color.White,
                ) {
                    Text("把问卷二维码放在取景框内", Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    StudioEmblem(Icons.Filled.QrCodeScanner)
                    Text("允许相机访问", style = MaterialTheme.typography.headlineSmall)
                    Text("打开相机后即可扫描问卷二维码。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { permission.launchPermissionRequest() }) { Text("授予相机权限") }
                }
            }
        }
    }
}

@Composable
private fun ScannerReticle() {
    Canvas(Modifier.fillMaxSize()) {
        val edge = size.minDimension * 0.64f
        val left = (size.width - edge) / 2
        val top = (size.height - edge) / 2
        val right = left + edge
        val bottom = top + edge
        val scrim = Color.Black.copy(alpha = 0.48f)
        val marker = Color(0xFFE5EBFF)
        drawRect(scrim, size = Size(size.width, top))
        drawRect(scrim, Offset(0f, bottom), Size(size.width, top))
        drawRect(scrim, Offset(0f, top), Size(left, edge))
        drawRect(scrim, Offset(right, top), Size(left, edge))
        val arm = edge * 0.13f
        val stroke = 3.dp.toPx()
        for (x in listOf(left, right)) {
            for (y in listOf(top, bottom)) {
                val corner = Offset(x, y)
                drawLine(marker, corner, Offset(x + if (x == left) arm else -arm, y), stroke, StrokeCap.Round)
                drawLine(marker, corner, Offset(x, y + if (y == top) arm else -arm), stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val handled = remember { AtomicBoolean(false) }
    val readerHolder = remember { ReaderHolder() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val cameraProvider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                    processImage(readerHolder, imageProxy) { value ->
                        if (handled.compareAndSet(false, true)) onResult(value)
                    }
                }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

// MultiFormatReader 非线程安全，用 ThreadLocal 保证每个线程独立实例。
private class ReaderHolder {
    private val threadLocal = ThreadLocal<MultiFormatReader>()

    fun get(): MultiFormatReader {
        var reader = threadLocal.get()
        if (reader == null) {
            reader = MultiFormatReader().apply {
                setHints(
                    mapOf<DecodeHintType, Any>(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.PDF_417),
                    ),
                )
            }
            threadLocal.set(reader)
        }
        return reader
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImage(
    holder: ReaderHolder,
    imageProxy: ImageProxy,
    onFound: (String) -> Unit,
) {
    try {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            return
        }
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = mediaImage.planes[0]
        val yBuffer = yPlane.buffer.duplicate()
        yBuffer.rewind()
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        val yBytes = ByteArray(width * height)
        if (rowStride == width && pixelStride == 1) {
            yBuffer.get(yBytes, 0, yBytes.size)
        } else {
            // 逐行拷贝以处理 rowStride padding / 非连续 pixelStride
            val rowBytes = ByteArray(width)
            var pos = 0
            for (row in 0 until height) {
                yBuffer.position(row * rowStride)
                if (pixelStride == 1) {
                    yBuffer.get(rowBytes, 0, width)
                } else {
                    var col = 0
                    while (col < width) {
                        rowBytes[col] = yBuffer.get()
                        if (col + 1 < width) {
                            yBuffer.position(yBuffer.position() + pixelStride - 1)
                        }
                        col++
                    }
                }
                System.arraycopy(rowBytes, 0, yBytes, pos, width)
                pos += width
            }
        }

        val source = PlanarYUVLuminanceSource(yBytes, width, height, 0, 0, width, height, false)
        val rotated = rotateClockwise(source, rotationDegrees)
        val binary = BinaryBitmap(HybridBinarizer(rotated))

        val reader = holder.get()
        try {
            val result = reader.decode(binary)
            result.text?.let(onFound)
        } catch (_: NotFoundException) {
            // 本帧未识别到条码，正常情况，忽略
        }
    } finally {
        imageProxy.close()
    }
}

// ZXing 仅提供 rotateCounterClockwise，故用 (360-deg)/90 次反向旋转实现顺时针旋转。
private fun rotateClockwise(source: LuminanceSource, degrees: Int): LuminanceSource {
    val normalized = ((degrees % 360) + 360) % 360
    val ccwSteps = ((360 - normalized) % 360) / 90
    var s = source
    repeat(ccwSteps) { s = s.rotateCounterClockwise() }
    return s
}
