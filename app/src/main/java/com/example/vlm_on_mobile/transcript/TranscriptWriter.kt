package com.example.vlm_on_mobile.transcript

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TranscriptRecord(
    val id: Int,
    val t: Long = System.currentTimeMillis(),
    val source: String, // "template", "gemma", "correction"
    val text: String,
    val azimuth: Float? = null,
    val elevation: Float? = null,
    val objects: List<Int> = emptyList(),
    val corrects: Int? = null,
    val latency_ms: Long? = null
)

class TranscriptWriter(private val context: Context) {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val recordChannel = Channel<TranscriptRecord>(Channel.UNLIMITED)

    private var nextRecordId = 1
    private var jsonlFile: File? = null
    private var txtFile: File? = null
    private var printWriter: PrintWriter? = null

    private val records = mutableListOf<TranscriptRecord>()

    companion object {
        private const val TAG = "TranscriptWriter"
    }

    fun startSession() {
        scope.launch {
            val transcriptDir = File(context.getExternalFilesDir(null), "transcripts")
            if (!transcriptDir.exists()) {
                transcriptDir.mkdirs()
            }

            val timeStr = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            jsonlFile = File(transcriptDir, "session-$timeStr.jsonl")
            txtFile = File(transcriptDir, "session-$timeStr.txt")

            printWriter = PrintWriter(OutputStreamWriter(FileOutputStream(jsonlFile!!, true), "UTF-8"), true)
            Log.d(TAG, "Started transcript session: ${jsonlFile?.absolutePath}")

            for (record in recordChannel) {
                val jsonLine = gson.toJson(record)
                printWriter?.println(jsonLine)
                records.add(record)
            }
        }
    }

    fun writeRecord(
        source: String,
        text: String,
        azimuth: Float? = null,
        elevation: Float? = null,
        objects: List<Int> = emptyList(),
        corrects: Int? = null,
        latencyMs: Long? = null
    ): TranscriptRecord {
        val record = TranscriptRecord(
            id = nextRecordId++,
            source = source,
            text = text,
            azimuth = azimuth,
            elevation = elevation,
            objects = objects,
            corrects = corrects,
            latency_ms = latencyMs
        )
        recordChannel.trySend(record)
        return record
    }

    suspend fun stopSession() = withContext(Dispatchers.IO) {
        recordChannel.close()
        printWriter?.flush()
        printWriter?.close()
        printWriter = null

        // Render plain text file session.txt from jsonl records
        txtFile?.let { tFile ->
            val txtWriter = PrintWriter(OutputStreamWriter(FileOutputStream(tFile), "UTF-8"))
            for (rec in records) {
                if (rec.source == "correction" && rec.corrects != null) {
                    txtWriter.println("[Correction] ${rec.text}")
                } else {
                    txtWriter.println(rec.text)
                }
            }
            txtWriter.flush()
            txtWriter.close()
            Log.d(TAG, "Rendered plain text transcript: ${tFile.absolutePath}")
        }
    }
}
