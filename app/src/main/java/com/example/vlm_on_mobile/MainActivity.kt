package com.example.vlm_on_mobile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.vlm_on_mobile.camera.CameraPreview
import com.example.vlm_on_mobile.detection.DetectorInput
import com.example.vlm_on_mobile.detection.YoloWorldDetector
import com.example.vlm_on_mobile.gemma.GemmaNarrator
import com.example.vlm_on_mobile.gemma.GemmaState
import com.example.vlm_on_mobile.orientation.BearingProjector
import com.example.vlm_on_mobile.orientation.BearingResult
import com.example.vlm_on_mobile.orientation.OrientationSample
import com.example.vlm_on_mobile.orientation.OrientationTracker
import com.example.vlm_on_mobile.tracking.MapEntry
import com.example.vlm_on_mobile.tracking.ObjectTracker
import com.example.vlm_on_mobile.tracking.SpatialDirectionMap
import com.example.vlm_on_mobile.ui.DetectedBoxOverlay
import com.example.vlm_on_mobile.ui.DetectionOverlay
import com.example.vlm_on_mobile.ui.OrientationOverlay
import com.example.vlm_on_mobile.ui.theme.VLM_on_mobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var gemmaNarrator: GemmaNarrator
    private lateinit var orientationTracker: OrientationTracker
    private var detector: YoloWorldDetector? = null
    private val objectTracker = ObjectTracker()
    private val directionMap = SpatialDirectionMap()

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(this, "Camera permission is required for spatial view", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        gemmaNarrator = GemmaNarrator(applicationContext)
        orientationTracker = OrientationTracker(applicationContext)

        try {
            detector = YoloWorldDetector(applicationContext, useGpu = false)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            VLM_on_mobileTheme {
                var selectedTabIndex by remember { mutableIntStateOf(0) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TabRow(selectedTabIndex = selectedTabIndex) {
                            Tab(
                                selected = selectedTabIndex == 0,
                                onClick = { selectedTabIndex = 0 },
                                text = { Text("Spatial Tracker") }
                            )
                            Tab(
                                selected = selectedTabIndex == 1,
                                onClick = { selectedTabIndex = 1 },
                                text = { Text("Gemma 4 Test") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (selectedTabIndex) {
                            0 -> SpatialTrackerScreen(
                                orientationTracker = orientationTracker,
                                detector = detector,
                                objectTracker = objectTracker,
                                directionMap = directionMap
                            )
                            1 -> GemmaBenchmarkScreen(narrator = gemmaNarrator)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        orientationTracker.displayRotation = windowManager.defaultDisplay.rotation
        orientationTracker.start()
    }

    override fun onPause() {
        super.onPause()
        orientationTracker.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        gemmaNarrator.close()
        detector?.close()
    }
}

@Composable
fun SpatialTrackerScreen(
    orientationTracker: OrientationTracker,
    detector: YoloWorldDetector?,
    objectTracker: ObjectTracker,
    directionMap: SpatialDirectionMap
) {
    var currentSample by remember { mutableStateOf<OrientationSample?>(null) }
    var centerBearing by remember { mutableStateOf<BearingResult?>(null) }
    var boxOverlays by remember { mutableStateOf<List<DetectedBoxOverlay>>(emptyList()) }
    var mapEntries by remember { mutableStateOf<List<MapEntry>>(emptyList()) }

    var isProcessingFrame by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onFrameAnalyzed = { frameData ->
                val sample = orientationTracker.getSampleAtTimestamp(frameData.timestampNs)
                currentSample = sample ?: orientationTracker.currentSample

                val currentRotMatrix = sample?.rotationMatrix ?: orientationTracker.currentSample?.rotationMatrix
                val intrinsics = frameData.intrinsics

                if (currentRotMatrix != null && intrinsics != null) {
                    val cx = intrinsics.cx
                    val cy = intrinsics.cy
                    val cameraCenter = BearingProjector.projectPixelToWorldBearing(
                        u = cx,
                        v = cy,
                        intrinsics = intrinsics,
                        rotationMatrix = currentRotMatrix
                    )
                    centerBearing = cameraCenter

                    // Trigger detection pipeline on frame if non-busy
                    if (detector != null && !isProcessingFrame) {
                        isProcessingFrame = true
                        val timestampMs = System.currentTimeMillis()

                        scope.launch(Dispatchers.Default) {
                            try {
                                // Synthetic frame test bitmap for detector input
                                val bmp = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
                                val frameRes = detector.detect(DetectorInput.Bmp(bmp))

                                val confirmedTracks = objectTracker.update(
                                    detections = frameRes.detections,
                                    labels = detector.labels,
                                    timestampMs = timestampMs
                                )

                                val trackedPairs = confirmedTracks.map { track ->
                                    val boxCenterU = (track.currentBox.left + track.currentBox.right) / 2f * intrinsics.cx * 2f
                                    val boxCenterV = (track.currentBox.top + track.currentBox.bottom) / 2f * intrinsics.cy * 2f
                                    val bearing = BearingProjector.projectPixelToWorldBearing(
                                        u = boxCenterU,
                                        v = boxCenterV,
                                        intrinsics = intrinsics,
                                        rotationMatrix = currentRotMatrix
                                    )
                                    track to bearing
                                }

                                val mapResult = directionMap.update(
                                    trackedObjects = trackedPairs,
                                    cameraCenterBearing = cameraCenter,
                                    timestampMs = timestampMs
                                )

                                withContext(Dispatchers.Main) {
                                    boxOverlays = trackedPairs.map { (track, bearing) ->
                                        DetectedBoxOverlay(track, bearing.azimuthDeg, bearing.elevationDeg)
                                    }
                                    mapEntries = mapResult.allEntries
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                isProcessingFrame = false
                            }
                        }
                    }
                }
            }
        )

        OrientationOverlay(
            currentSample = currentSample,
            centerBearing = centerBearing,
            onResetYaw = { orientationTracker.zeroYaw() },
            modifier = Modifier.fillMaxSize()
        )

        DetectionOverlay(
            boxes = boxOverlays,
            mapEntries = mapEntries,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun GemmaBenchmarkScreen(
    narrator: GemmaNarrator,
    modifier: Modifier = Modifier
) {
    val state by narrator.state.collectAsState()
    val scope = rememberCoroutineScope()

    var promptText by remember { mutableStateOf("Describe this image in one concise sentence.") }
    var outputText by remember { mutableStateOf("") }
    var lastLatencyMs by remember { mutableStateOf<Long?>(null) }
    var isRunningInference by remember { mutableStateOf(false) }

    val testBitmap = remember {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.LTGRAY)
        val paint = Paint().apply {
            color = Color.RED
            textSize = 48f
            isAntiAlias = true
        }
        canvas.drawCircle(256f, 200f, 80f, paint)
        paint.color = Color.BLUE
        canvas.drawText("VLM Benchmark", 100f, 380f, paint)
        bmp
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Milestone 1: Gemma 4 E4B Test",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Engine Status:",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                when (val s = state) {
                    is GemmaState.Uninitialized -> {
                        val modelFile = narrator.findModelFile()
                        if (modelFile != null) {
                            Text("Model File Found:\n${modelFile.absolutePath}\nSize: ${modelFile.length() / (1024 * 1024)} MB")
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { scope.launch { narrator.initialize() } }) {
                                Text("Initialize Gemma 4 (GPU)")
                            }
                        } else {
                            Text(
                                text = "Model File NOT Found on Device!\n\nPlease push '${GemmaNarrator.MODEL_FILENAME}' to app storage.",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    is GemmaState.Initializing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                            Text(" Initializing LiteRT-LM Engine on GPU...")
                        }
                    }
                    is GemmaState.Ready -> {
                        Text(
                            text = "READY",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Model: ${s.modelPath}")
                        Text("Init Latency: ${s.initTimeMs} ms")
                    }
                    is GemmaState.Error -> {
                        Text(
                            text = "Error: ${s.message}",
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { scope.launch { narrator.initialize() } }) {
                            Text("Retry Initialization")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state is GemmaState.Ready) {
            Text(
                text = "Test Synthetic Input Frame:",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Image(
                bitmap = testBitmap.asImageBitmap(),
                contentDescription = "Test Image",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                enabled = !isRunningInference,
                onClick = {
                    scope.launch {
                        isRunningInference = true
                        outputText = "Running inference..."
                        val result = narrator.generateDescription(promptText, testBitmap)
                        isRunningInference = false

                        result.onSuccess {
                            outputText = it.text
                            lastLatencyMs = it.latencyMs
                        }.onFailure { err ->
                            outputText = "Error: ${err.localizedMessage ?: err.message}"
                            lastLatencyMs = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRunningInference) {
                    CircularProgressIndicator()
                } else {
                    Text("Run Gemma Description")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (outputText.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Gemma Output:",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = outputText,
                            style = MaterialTheme.typography.bodyLarge
                        )

                        lastLatencyMs?.let { latency ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Inference Latency: ${latency} ms",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
