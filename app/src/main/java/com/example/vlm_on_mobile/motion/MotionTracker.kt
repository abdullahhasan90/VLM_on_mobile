package com.example.vlm_on_mobile.motion

import com.example.vlm_on_mobile.orientation.OrientationSample
import kotlin.math.abs
import kotlin.math.max

enum class MotionState {
    IDLE,
    MOVING,
    SETTLED
}

data class MotionStatus(
    val state: MotionState,
    val angularSpeedDegPerSec: Float,
    val yawDeltaDeg: Float,
    val pitchDeltaDeg: Float
)

class MotionTracker(
    private val movingSpeedThresholdDeg: Float = 20.0f,
    private val settledSpeedThresholdDeg: Float = 8.0f,
    private val settleDwellMs: Long = 300L
) {
    private var lastSample: OrientationSample? = null
    private var currentState: MotionState = MotionState.IDLE
    private var settledStartTimeMs: Long = 0L

    private var accumulatedYawDelta: Float = 0f
    private var accumulatedPitchDelta: Float = 0f
    private var lastMotionStateChangeTimeMs: Long = 0L

    fun update(
        sample: OrientationSample,
        timestampMs: Long
    ): MotionStatus {
        val prev = lastSample
        lastSample = sample

        if (prev == null) {
            return MotionStatus(currentState, 0f, 0f, 0f)
        }

        val dtSec = max((sample.timestampNs - prev.timestampNs) / 1_000_000_000f, 0.001f)

        var deltaYaw = sample.yawDeg - prev.yawDeg
        if (deltaYaw > 180f) deltaYaw -= 360f
        if (deltaYaw < -180f) deltaYaw += 360f

        val deltaPitch = sample.pitchDeg - prev.pitchDeg

        val yawSpeed = abs(deltaYaw) / dtSec
        val pitchSpeed = abs(deltaPitch) / dtSec
        val totalAngularSpeed = max(yawSpeed, pitchSpeed)

        accumulatedYawDelta += deltaYaw
        accumulatedPitchDelta += deltaPitch

        when (currentState) {
            MotionState.IDLE -> {
                if (totalAngularSpeed >= movingSpeedThresholdDeg) {
                    currentState = MotionState.MOVING
                    lastMotionStateChangeTimeMs = timestampMs
                }
            }
            MotionState.MOVING -> {
                if (totalAngularSpeed < settledSpeedThresholdDeg) {
                    if (settledStartTimeMs == 0L) {
                        settledStartTimeMs = timestampMs
                    } else if (timestampMs - settledStartTimeMs >= settleDwellMs) {
                        currentState = MotionState.SETTLED
                        lastMotionStateChangeTimeMs = timestampMs
                        settledStartTimeMs = 0L
                    }
                } else {
                    settledStartTimeMs = 0L
                }
            }
            MotionState.SETTLED -> {
                if (totalAngularSpeed >= movingSpeedThresholdDeg) {
                    currentState = MotionState.MOVING
                    lastMotionStateChangeTimeMs = timestampMs
                    settledStartTimeMs = 0L
                }
            }
        }

        return MotionStatus(
            state = currentState,
            angularSpeedDegPerSec = totalAngularSpeed,
            yawDeltaDeg = accumulatedYawDelta,
            pitchDeltaDeg = accumulatedPitchDelta
        )
    }

    fun resetRotationAccumulator(): Pair<Float, Float> {
        val res = Pair(accumulatedYawDelta, accumulatedPitchDelta)
        accumulatedYawDelta = 0f
        accumulatedPitchDelta = 0f
        return res
    }
}
