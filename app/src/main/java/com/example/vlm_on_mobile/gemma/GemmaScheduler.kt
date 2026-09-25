package com.example.vlm_on_mobile.gemma

import android.util.Log
import com.example.vlm_on_mobile.events.AppEvent
import com.example.vlm_on_mobile.events.EventBuffer
import com.example.vlm_on_mobile.transcript.TranscriptWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GemmaScheduler(
    private val narrator: GemmaNarrator,
    private val transcriptWriter: TranscriptWriter,
    private val ringBuffer: FrameRingBuffer,
    private val eventBuffer: EventBuffer
) {

    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val recentSentences = mutableListOf<String>()
    private var lastDescriptionTimestampMs: Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "GemmaScheduler"
    }

    fun triggerScheduledDescription(
        detectorHints: List<String>,
        yawDeltaDeg: Float,
        pitchDeltaDeg: Float,
        onComplete: ((String) -> Unit)? = null
    ) {
        scope.launch {
            if (mutex.isLocked) {
                Log.d(TAG, "Gemma engine busy. Skipping trigger.")
                return@launch
            }

            mutex.withLock {
                val bestFrame = ringBuffer.selectBestFrame()
                if (bestFrame == null) {
                    Log.d(TAG, "No suitable frame in ring buffer for Gemma scheduler.")
                    return@withLock
                }

                val nowMs = System.currentTimeMillis()
                val recentEvents = eventBuffer.getRecentEvents(sinceTimestampMs = lastDescriptionTimestampMs)

                val prompt = buildPrompt(
                    detectorHints = detectorHints,
                    yawDeltaDeg = yawDeltaDeg,
                    pitchDeltaDeg = pitchDeltaDeg,
                    recentEvents = recentEvents
                )

                Log.d(TAG, "Running Gemma scheduled description...")
                val result = narrator.generateDescription(prompt, bestFrame.bitmap)

                result.onSuccess { res ->
                    var responseText = res.text
                    if (responseText.startsWith("Then, ", ignoreCase = true)) {
                        responseText = responseText.removePrefix("Then, ").removePrefix("then, ")
                    }

                    if (responseText.isNotEmpty()) {
                        synchronized(recentSentences) {
                            recentSentences.add(responseText)
                            if (recentSentences.size > 3) {
                                recentSentences.removeAt(0)
                            }
                        }

                        transcriptWriter.writeRecord(
                            source = "gemma",
                            text = responseText,
                            latencyMs = res.latencyMs
                        )

                        lastDescriptionTimestampMs = nowMs
                        onComplete?.invoke(responseText)
                    }
                }.onFailure { err ->
                    Log.e(TAG, "Gemma scheduled description failed", err)
                }
            }
        }
    }

    private fun buildPrompt(
        detectorHints: List<String>,
        yawDeltaDeg: Float,
        pitchDeltaDeg: Float,
        recentEvents: List<AppEvent>
    ): String {
        val historyStr = synchronized(recentSentences) {
            if (recentSentences.isEmpty()) "None" else recentSentences.joinToString(" ")
        }

        val rotStr = buildRotationSummary(yawDeltaDeg, pitchDeltaDeg)
        val hintsStr = if (detectorHints.isEmpty()) "None" else detectorHints.distinct().joinToString(", ")

        val eventsStr = if (recentEvents.isEmpty()) {
            "None"
        } else {
            recentEvents.mapNotNull { ev ->
                when (ev) {
                    is AppEvent.ObjectNew -> "New ${ev.entry.label}"
                    is AppEvent.ObjectLost -> "Lost ${ev.entry.label}"
                    is AppEvent.ObjectRemoved -> "Removed ${ev.entry.label}"
                    else -> null
                }
            }.distinct().joinToString(", ").ifEmpty { "None" }
        }

        return """
            You narrate a live camera view for a seated user, one or two sentences at a time.
            Describe only what is new or changed. Do not repeat earlier sentences.
            The image decides. Detector hints may be wrong; ignore any you cannot see.

            Earlier narration: $historyStr
            Camera: $rotStr
            Detector suggests: $hintsStr
            Changes since last time: $eventsStr
        """.trimIndent()
    }

    private fun buildRotationSummary(yawDeltaDeg: Float, pitchDeltaDeg: Float): String {
        val parts = mutableListOf<String>()
        if (Math.abs(yawDeltaDeg) >= 5f) {
            parts.add(if (yawDeltaDeg > 0) "turned ${yawDeltaDeg.toInt()}° right" else "turned ${Math.abs(yawDeltaDeg).toInt()}° left")
        }
        if (Math.abs(pitchDeltaDeg) >= 5f) {
            parts.add(if (pitchDeltaDeg > 0) "slightly up" else "slightly down")
        }
        return if (parts.isEmpty()) "stationary" else parts.joinToString(", ")
    }
}
