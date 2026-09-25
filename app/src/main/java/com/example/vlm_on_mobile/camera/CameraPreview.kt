package com.example.vlm_on_mobile.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.vlm_on_mobile.orientation.BearingProjector
import com.example.vlm_on_mobile.orientation.CameraIntrinsics
import java.util.concurrent.Executors

data class FrameAnalysisData(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val intrinsics: CameraIntrinsics?
)

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onFrameAnalyzed: (FrameAnalysisData) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            var cachedIntrinsics: CameraIntrinsics? = null

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(executor) { imageProxy ->
                        val timestampNs = imageProxy.imageInfo.timestamp
                        val width = imageProxy.width
                        val height = imageProxy.height

                        if (cachedIntrinsics == null) {
                            val focalLength = width * 0.8f
                            cachedIntrinsics = CameraIntrinsics(
                                fx = focalLength,
                                fy = focalLength,
                                cx = width / 2f,
                                cy = height / 2f
                            )
                        }

                        onFrameAnalyzed(
                            FrameAnalysisData(
                                timestampNs = timestampNs,
                                width = width,
                                height = height,
                                intrinsics = cachedIntrinsics
                            )
                        )

                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                // Query Camera2 characteristics directly from CameraManager
                val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics = cameraManager.getCameraCharacteristics(camera2Info.cameraId)

                val intrinsics = BearingProjector.getIntrinsics(
                    characteristics = characteristics,
                    imageWidth = previewView.width.takeIf { it > 0 } ?: 1280,
                    imageHeight = previewView.height.takeIf { it > 0 } ?: 720
                )
                cachedIntrinsics = intrinsics

            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(context))

        onDispose {
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}
