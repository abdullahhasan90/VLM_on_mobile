package com.example.vlm_on_mobile.tracking

import com.example.vlm_on_mobile.orientation.BearingResult
import kotlin.math.abs
import kotlin.math.cos

enum class MapEntryState {
    IN_VIEW,
    OUT_OF_VIEW,
    REMOVED
}

enum class VerificationState {
    UNVERIFIED,
    CONFIRMED_BY_GEMMA,
    CONTRADICTED
}

data class MapEntry(
    val id: Int,
    val labelIndex: Int,
    val label: String,
    var azimuthDeg: Float,
    var elevationDeg: Float,
    var confidence: Float,
    var state: MapEntryState = MapEntryState.IN_VIEW,
    var lastSeenMs: Long,
    var verifiedState: VerificationState = VerificationState.UNVERIFIED
)

class SpatialDirectionMap(
    private val matchAngleDeg: Float = 10.0f,
    private val lossTimeoutMs: Long = 2000L
) {
    private var nextEntryId = 1
    private val entries = mutableListOf<MapEntry>()

    fun getEntries(): List<MapEntry> = entries.toList()

    /**
     * Updates map entries with newly tracked objects and current camera center bearing.
     */
    fun update(
        trackedObjects: List<Pair<TrackedObject, BearingResult>>,
        cameraCenterBearing: BearingResult,
        fovHorizontalDeg: Float = 60.0f,
        fovVerticalDeg: Float = 45.0f,
        timestampMs: Long
    ): MapUpdateResult {
        val newEntries = mutableListOf<MapEntry>()
        val lostEntries = mutableListOf<MapEntry>()

        // 1. Process tracked objects and match against existing entries
        for ((track, bearing) in trackedObjects) {
            var bestMatch: MapEntry? = null
            var minDistance = matchAngleDeg

            for (entry in entries.filter { it.state != MapEntryState.REMOVED && it.labelIndex == track.labelIndex }) {
                val dist = angularDistanceDeg(
                    az1 = entry.azimuthDeg, el1 = entry.elevationDeg,
                    az2 = bearing.azimuthDeg, el2 = bearing.elevationDeg
                )
                if (dist < minDistance) {
                    minDistance = dist
                    bestMatch = entry
                }
            }

            if (bestMatch != null) {
                // Update running average position (70% old, 30% new)
                bestMatch.azimuthDeg = 0.7f * bestMatch.azimuthDeg + 0.3f * bearing.azimuthDeg
                bestMatch.elevationDeg = 0.7f * bestMatch.elevationDeg + 0.3f * bearing.elevationDeg
                bestMatch.confidence = maxOf(bestMatch.confidence, track.confidence)
                bestMatch.lastSeenMs = timestampMs
                bestMatch.state = MapEntryState.IN_VIEW
            } else {
                // Create new map entry
                val newEntry = MapEntry(
                    id = nextEntryId++,
                    labelIndex = track.labelIndex,
                    label = track.label,
                    azimuthDeg = bearing.azimuthDeg,
                    elevationDeg = bearing.elevationDeg,
                    confidence = track.confidence,
                    state = MapEntryState.IN_VIEW,
                    lastSeenMs = timestampMs
                )
                entries.add(newEntry)
                newEntries.add(newEntry)
            }
        }

        // 2. Check existing in-view entries to see if they rotated out of view or were lost
        for (entry in entries.filter { it.state == MapEntryState.IN_VIEW }) {
            val inFrustum = isInsideFrustum(
                azimuthDeg = entry.azimuthDeg,
                elevationDeg = entry.elevationDeg,
                cameraCenter = cameraCenterBearing,
                fovHorizontal = fovHorizontalDeg,
                fovVertical = fovVerticalDeg
            )

            if (!inFrustum) {
                // Rotated out of frame -> OUT_OF_VIEW (NOT lost/removed)
                entry.state = MapEntryState.OUT_OF_VIEW
            } else {
                // In frustum but unseen for lossTimeoutMs -> REMOVED/LOST
                if (timestampMs - entry.lastSeenMs > lossTimeoutMs) {
                    entry.state = MapEntryState.REMOVED
                    lostEntries.add(entry)
                }
            }
        }

        // 3. Check out-of-view entries coming back into frame
        for (entry in entries.filter { it.state == MapEntryState.OUT_OF_VIEW }) {
            val inFrustum = isInsideFrustum(
                azimuthDeg = entry.azimuthDeg,
                elevationDeg = entry.elevationDeg,
                cameraCenter = cameraCenterBearing,
                fovHorizontal = fovHorizontalDeg,
                fovVertical = fovVerticalDeg
            )
            if (inFrustum) {
                // Entry came back into view
                entry.lastSeenMs = timestampMs
                entry.state = MapEntryState.IN_VIEW
            }
        }

        return MapUpdateResult(
            newEntries = newEntries,
            lostEntries = lostEntries,
            allEntries = entries.filter { it.state != MapEntryState.REMOVED }
        )
    }

    private fun isInsideFrustum(
        azimuthDeg: Float,
        elevationDeg: Float,
        cameraCenter: BearingResult,
        fovHorizontal: Float,
        fovVertical: Float
    ): Boolean {
        var deltaAz = abs(azimuthDeg - cameraCenter.azimuthDeg)
        if (deltaAz > 180f) deltaAz = 360f - deltaAz

        val deltaEl = abs(elevationDeg - cameraCenter.elevationDeg)
        return deltaAz <= (fovHorizontal / 2f) && deltaEl <= (fovVertical / 2f)
    }

    private fun angularDistanceDeg(az1: Float, el1: Float, az2: Float, el2: Float): Float {
        var deltaAz = abs(az1 - az2)
        if (deltaAz > 180f) deltaAz = 360f - deltaAz

        val meanElRad = Math.toRadians(((el1 + el2) / 2f).toDouble())
        val effDeltaAz = deltaAz * cos(meanElRad).toFloat()
        val deltaEl = el1 - el2

        return Math.toDegrees(Math.sqrt((effDeltaAz * effDeltaAz + deltaEl * deltaEl).toDouble())).toFloat()
    }

    fun clear() {
        entries.clear()
        nextEntryId = 1
    }
}

data class MapUpdateResult(
    val newEntries: List<MapEntry>,
    val lostEntries: List<MapEntry>,
    val allEntries: List<MapEntry>
)
