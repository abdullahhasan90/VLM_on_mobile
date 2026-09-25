package com.example.vlm_on_mobile.gemma

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

sealed class GemmaState {
    object Uninitialized : GemmaState()
    object Initializing : GemmaState()
    data class Ready(val modelPath: String, val initTimeMs: Long) : GemmaState()
    data class Error(val message: String) : GemmaState()
}

data class GemmaInferenceResult(
    val text: String,
    val latencyMs: Long
)

class GemmaNarrator(private val context: Context) {

    private val _state = MutableStateFlow<GemmaState>(GemmaState.Uninitialized)
    val state: StateFlow<GemmaState> = _state.asStateFlow()

    private var engine: Engine? = null

    companion object {
        private const val TAG = "GemmaNarrator"
        const val MODEL_FILENAME = "gemma-4-E4B-it.litertlm"
    }

    /**
     * Resolves the candidate model file paths on device storage.
     */
    fun findModelFile(): File? {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), MODEL_FILENAME),
            File("/sdcard/Android/data/${context.packageName}/files", MODEL_FILENAME),
            File("/data/local/tmp", MODEL_FILENAME)
        )

        for (file in candidates) {
            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "Found model file at: ${file.absolutePath} (${file.length() / (1024 * 1024)} MB)")
                return file
            }
        }
        return null
    }

    /**
     * Initializes LiteRT-LM engine off the main thread.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (_state.value is GemmaState.Ready) return@withContext true

        _state.value = GemmaState.Initializing
        val startTime = System.currentTimeMillis()

        val modelFile = findModelFile()
        if (modelFile == null) {
            val errStr = "Model file '$MODEL_FILENAME' not found on device storage."
            Log.e(TAG, errStr)
            _state.value = GemmaState.Error(errStr)
            return@withContext false
        }

        try {
            Log.d(TAG, "Initializing LiteRT-LM Engine with model at ${modelFile.absolutePath}...")
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                maxNumTokens = 64,
                maxNumImages = 1
            )

            val newEngine = Engine(config)
            newEngine.initialize()

            engine = newEngine
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "LiteRT-LM initialized successfully in ${elapsed}ms")

            _state.value = GemmaState.Ready(modelFile.absolutePath, elapsed)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LiteRT-LM Engine", e)
            _state.value = GemmaState.Error("Init failed: ${e.localizedMessage ?: e.message}")
            false
        }
    }

    /**
     * Runs Gemma 4 E4B inference on a frame with text prompt.
     */
    suspend fun generateDescription(
        prompt: String,
        bitmap: Bitmap? = null
    ): Result<GemmaInferenceResult> = withContext(Dispatchers.IO) {
        val currentEngine = engine
        if (currentEngine == null || !_state.value.let { it is GemmaState.Ready }) {
            return@withContext Result.failure(IllegalStateException("GemmaNarrator is not initialized"))
        }

        val startTime = System.currentTimeMillis()
        try {
            val conversation = currentEngine.createConversation()
            val contentList = mutableListOf<Content>()

            if (bitmap != null) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val bytes = stream.toByteArray()
                contentList.add(Content.ImageBytes(bytes))
            }

            contentList.add(Content.Text(prompt))

            val userMessage = Message.user(Contents.of(contentList))
            val responseMessage = conversation.sendMessage(userMessage)

            val responseText = responseMessage.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(" ") { it.text }
                .trim()

            conversation.close()

            val elapsed = System.currentTimeMillis() - startTime
            Result.success(GemmaInferenceResult(responseText, elapsed))
        } catch (e: Exception) {
            Log.e(TAG, "Error during Gemma inference", e)
            Result.failure(e)
        }
    }

    fun close() {
        try {
            engine?.close()
            engine = null
            _state.value = GemmaState.Uninitialized
        } catch (e: Exception) {
            Log.e(TAG, "Error closing LiteRT-LM Engine", e)
        }
    }
}
