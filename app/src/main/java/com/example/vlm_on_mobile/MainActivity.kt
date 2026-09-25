package com.example.vlm_on_mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.vlm_on_mobile.gemma.GemmaNarrator
import com.example.vlm_on_mobile.gemma.GemmaState
import com.example.vlm_on_mobile.ui.theme.VLM_on_mobileTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var gemmaNarrator: GemmaNarrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        gemmaNarrator = GemmaNarrator(applicationContext)

        setContent {
            VLM_on_mobileTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GemmaBenchmarkScreen(
                        narrator = gemmaNarrator,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gemmaNarrator.close()
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

    // Generate a simple synthetic test image
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

        // Status Card
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
                                text = "Model File NOT Found on Device!\n\nPlease push '${GemmaNarrator.MODEL_FILENAME}' to:\n/sdcard/Android/data/com.example.vlm_on_mobile/files/",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    is GemmaState.Initializing -> {
                        RowVerticalCenter {
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

        // Test Input Section
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

@Composable
private fun RowVerticalCenter(content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}
