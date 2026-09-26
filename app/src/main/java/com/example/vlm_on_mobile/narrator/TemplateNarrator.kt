package com.example.vlm_on_mobile.narrator

import com.example.vlm_on_mobile.tracking.MapEntry
import com.example.vlm_on_mobile.transcript.TranscriptWriter

class TemplateNarrator(private val transcriptWriter: TranscriptWriter? = null) {

    private val pendingNewObjects = mutableListOf<MapEntry>()
    private var lastFlushTimeMs: Long = 0L

    companion object {
        private const val FLUSH_INTERVAL_MS = 1500L
    }

    fun onNewObject(entry: MapEntry) {
        if (!pendingNewObjects.any { it.id == entry.id }) {
            pendingNewObjects.add(entry)
        }
    }

    fun onRotationSegment(
        yawDeltaDeg: Float,
        pitchDeltaDeg: Float,
        timestampMs: Long,
        forceFlush: Boolean = false
    ): String? {
        if (pendingNewObjects.isEmpty()) return null

        if (!forceFlush && timestampMs - lastFlushTimeMs < FLUSH_INTERVAL_MS) {
            return null
        }

        val directionParts = mutableListOf<String>()

        if (yawDeltaDeg > 10f) {
            directionParts.add("right")
        } else if (yawDeltaDeg < -10f) {
            directionParts.add("left")
        }

        if (pitchDeltaDeg > 10f) {
            directionParts.add("up")
        } else if (pitchDeltaDeg < -10f) {
            directionParts.add("down")
        }

        val directionStr = if (directionParts.isNotEmpty()) {
            "Turning ${directionParts.joinToString(" and ")}"
        } else {
            "In view"
        }

        val objectLabels = pendingNewObjects.map { it.label }.distinct().joinToString(", ")
        val textLine = "$directionStr: $objectLabels."

        val avgAz = pendingNewObjects.map { it.azimuthDeg }.average().toFloat()
        val avgEl = pendingNewObjects.map { it.elevationDeg }.average().toFloat()
        val objectIds = pendingNewObjects.map { it.id }

        transcriptWriter?.writeRecord(
            source = "template",
            text = textLine,
            azimuth = avgAz,
            elevation = avgEl,
            objects = objectIds
        )

        pendingNewObjects.clear()
        lastFlushTimeMs = timestampMs

        return textLine
    }

    fun clear() {
        pendingNewObjects.clear()
    }
}
