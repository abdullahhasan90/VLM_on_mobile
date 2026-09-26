package com.example.vlm_on_mobile.tracking

import com.example.vlm_on_mobile.detection.BoundingBox
import com.example.vlm_on_mobile.detection.Detection
import kotlin.math.max
import kotlin.math.min

data class TrackedObject(
    val trackId: Int,
    val labelIndex: Int,
    val label: String,
    var currentBox: BoundingBox,
    var confidence: Float,
    val firstSeenMs: Long,
    var lastSeenMs: Long,
    var consecutiveDetections: Int = 1,
    var isConfirmed: Boolean = false,
    val confidenceHistory: MutableList<Float> = mutableListOf()
)

class ObjectTracker(
    private val confirmationFrames: Int = 3,
    private val confirmationMinScore: Float = 0.25f,
    private val iouThreshold: Float = 0.3f
) {
    private var nextTrackId = 1
    private val activeTracks = mutableListOf<TrackedObject>()

    fun update(
        detections: List<Detection>,
        labels: List<String>,
        timestampMs: Long
    ): List<TrackedObject> {
        val unmatchedDetections = detections.toMutableList()
        val updatedTracks = mutableListOf<TrackedObject>()

        // 1. Associate detections to existing active tracks via IoU & class matching
        for (track in activeTracks) {
            var bestMatch: Detection? = null
            var maxIou = iouThreshold

            for (det in unmatchedDetections) {
                if (det.labelIndex == track.labelIndex) {
                    val iou = calculateIoU(track.currentBox, det.box)
                    if (iou > maxIou) {
                        maxIou = iou
                        bestMatch = det
                    }
                }
            }

            if (bestMatch != null) {
                unmatchedDetections.remove(bestMatch)

                track.currentBox = bestMatch.box
                track.confidence = bestMatch.score
                track.lastSeenMs = timestampMs
                track.confidenceHistory.add(bestMatch.score)

                if (bestMatch.score >= confirmationMinScore) {
                    track.consecutiveDetections++
                } else {
                    track.consecutiveDetections = 0
                }

                if (!track.isConfirmed && track.consecutiveDetections >= confirmationFrames) {
                    track.isConfirmed = true
                }

                updatedTracks.add(track)
            }
        }

        // 2. Create new tracks for unmatched detections
        for (det in unmatchedDetections) {
            val labelStr = labels.getOrElse(det.labelIndex) { "object" }
            val newTrack = TrackedObject(
                trackId = nextTrackId++,
                labelIndex = det.labelIndex,
                label = labelStr,
                currentBox = det.box,
                confidence = det.score,
                firstSeenMs = timestampMs,
                lastSeenMs = timestampMs,
                consecutiveDetections = if (det.score >= confirmationMinScore) 1 else 0,
                isConfirmed = confirmationFrames <= 1 && det.score >= confirmationMinScore,
                confidenceHistory = mutableListOf(det.score)
            )
            activeTracks.add(newTrack)
            updatedTracks.add(newTrack)
        }

        // Prune stale tracks that haven't been seen for > 1 second
        activeTracks.removeAll { timestampMs - it.lastSeenMs > 1000L }

        return activeTracks.filter { it.isConfirmed }
    }

    private fun calculateIoU(b1: BoundingBox, b2: BoundingBox): Float {
        return calculateIoU(b1.left, b1.top, b1.right, b1.bottom, b2.left, b2.top, b2.right, b2.bottom)
    }

    private fun calculateIoU(l1: Float, t1: Float, r1: Float, b1: Float, l2: Float, t2: Float, r2: Float, b2: Float): Float {
        val interLeft = max(l1, l2)
        val interTop = max(t1, t2)
        val interRight = min(r1, r2)
        val interBottom = min(b1, b2)

        if (interLeft >= interRight || interTop >= interBottom) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val area1 = (r1 - l1) * (b1 - t1)
        val area2 = (r2 - l2) * (b2 - t2)

        return interArea / (area1 + area2 - interArea)
    }

    fun clear() {
        activeTracks.clear()
        nextTrackId = 1
    }
}
