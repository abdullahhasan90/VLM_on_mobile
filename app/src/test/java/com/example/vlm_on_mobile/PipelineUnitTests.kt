package com.example.vlm_on_mobile

import com.example.vlm_on_mobile.detection.BoundingBox
import com.example.vlm_on_mobile.detection.Detection
import com.example.vlm_on_mobile.events.AppEvent
import com.example.vlm_on_mobile.events.EventBuffer
import com.example.vlm_on_mobile.motion.MotionState
import com.example.vlm_on_mobile.motion.MotionTracker
import com.example.vlm_on_mobile.narrator.TemplateNarrator
import com.example.vlm_on_mobile.orientation.BearingProjector
import com.example.vlm_on_mobile.orientation.CameraIntrinsics
import com.example.vlm_on_mobile.orientation.OrientationSample
import com.example.vlm_on_mobile.tracking.MapEntry
import com.example.vlm_on_mobile.tracking.MapEntryState
import com.example.vlm_on_mobile.tracking.ObjectTracker
import com.example.vlm_on_mobile.tracking.SpatialDirectionMap
import com.example.vlm_on_mobile.transcript.TranscriptWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineUnitTests {

    @Test
    fun testBearingProjector_CenterPixel_ReturnsZeroAzimuthAndElevation() {
        val intrinsics = CameraIntrinsics(fx = 1000f, fy = 1000f, cx = 500f, cy = 500f)
        val identityMatrix = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        val bearing = BearingProjector.projectPixelToWorldBearing(
            u = 500f,
            v = 500f,
            intrinsics = intrinsics,
            rotationMatrix = identityMatrix
        )

        assertEquals(0f, bearing.azimuthDeg, 0.1f)
        assertEquals(0f, bearing.elevationDeg, 0.1f)
    }

    @Test
    fun testObjectTracker_RequiresThreeConsecutiveFramesForConfirmation() {
        val tracker = ObjectTracker(confirmationFrames = 3, confirmationMinScore = 0.25f)
        val labels = listOf("chair", "table")
        val det = Detection(BoundingBox(0.1f, 0.1f, 0.3f, 0.3f), labelIndex = 0, score = 0.8f)

        // Frame 1 (1st detection)
        var confirmed = tracker.update(listOf(det), labels, timestampMs = 1000L)
        assertEquals(0, confirmed.size)

        // Frame 2 (2nd detection)
        confirmed = tracker.update(listOf(det), labels, timestampMs = 1033L)
        assertEquals(0, confirmed.size)

        // Frame 3 (3rd consecutive detection -> Confirmed)
        confirmed = tracker.update(listOf(det), labels, timestampMs = 1066L)
        assertEquals(1, confirmed.size)
        assertEquals("chair", confirmed[0].label)
    }

    @Test
    fun testSpatialDirectionMap_KeepsRotatedObjectsInOutOfView() {
        val directionMap = SpatialDirectionMap()
        val labels = listOf("door")
        val tracker = ObjectTracker(confirmationFrames = 1, confirmationMinScore = 0.2f)

        val det = Detection(BoundingBox(0.4f, 0.4f, 0.6f, 0.6f), labelIndex = 0, score = 0.9f)
        val confirmed = tracker.update(listOf(det), labels, timestampMs = 1000L)

        val bearingCenter = BearingProjector.projectPixelToWorldBearing(
            500f, 500f,
            CameraIntrinsics(1000f, 1000f, 500f, 500f),
            floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        )

        // 1. Initial update -> In view
        val update1 = directionMap.update(
            trackedObjects = listOf(confirmed[0] to bearingCenter),
            cameraCenterBearing = bearingCenter,
            timestampMs = 1000L
        )
        assertEquals(1, update1.allEntries.size)
        assertEquals(MapEntryState.IN_VIEW, update1.allEntries[0].state)

        // 2. Camera turns away 90 degrees right (Center bearing azimuth = 90)
        val turnedBearing = bearingCenter.copy(azimuthDeg = 90f)
        val update2 = directionMap.update(
            trackedObjects = emptyList(), // Object no longer in frame
            cameraCenterBearing = turnedBearing,
            timestampMs = 1500L
        )

        // Object must NOT be removed; it must transition to OUT_OF_VIEW
        assertEquals(1, update2.allEntries.size)
        assertEquals(MapEntryState.OUT_OF_VIEW, update2.allEntries[0].state)
    }

    @Test
    fun testMotionTracker_TransitionsFromMovingToSettledAfterDwell() {
        val motionTracker = MotionTracker(
            movingSpeedThresholdDeg = 20.0f,
            settledSpeedThresholdDeg = 8.0f,
            settleDwellMs = 300L
        )

        val s1 = OrientationSample(1_000_000_000L, floatArrayOf(), yawDeg = 0f, pitchDeg = 0f, rollDeg = 0f)
        val s2 = OrientationSample(1_050_000_000L, floatArrayOf(), yawDeg = 15f, pitchDeg = 0f, rollDeg = 0f) // ~300 deg/s

        // High speed -> MOVING
        var status = motionTracker.update(s1, timestampMs = 1000L)
        status = motionTracker.update(s2, timestampMs = 1050L)
        assertEquals(MotionState.MOVING, status.state)

        // Slow speed for 100ms -> Still MOVING
        val s3 = OrientationSample(1_150_000_000L, floatArrayOf(), yawDeg = 15.1f, pitchDeg = 0f, rollDeg = 0f)
        status = motionTracker.update(s3, timestampMs = 1150L)
        assertEquals(MotionState.MOVING, status.state)

        // Slow speed for >300ms -> SETTLED
        val s4 = OrientationSample(1_500_000_000L, floatArrayOf(), yawDeg = 15.2f, pitchDeg = 0f, rollDeg = 0f)
        status = motionTracker.update(s4, timestampMs = 1500L)
        assertEquals(MotionState.SETTLED, status.state)
    }

    @Test
    fun testTemplateNarrator_FormatsDirectionAndObjectList() {
        val narrator = TemplateNarrator(null)

        val entry = MapEntry(
            id = 1,
            labelIndex = 0,
            label = "chair",
            azimuthDeg = 15f,
            elevationDeg = 0f,
            confidence = 0.9f,
            lastSeenMs = 1000L
        )

        narrator.onNewObject(entry)

        val text = narrator.onRotationSegment(
            yawDeltaDeg = 25f,
            pitchDeltaDeg = -15f,
            timestampMs = 2000L,
            forceFlush = true
        )

        assertNotNull(text)
        assertTrue(text!!.contains("Turning right and down"))
        assertTrue(text.contains("chair"))
    }
}
