package com.example.vlm_on_mobile.orientation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentSkipListMap

data class OrientationSample(
    val timestampNs: Long,
    val rotationMatrix: FloatArray, // 9 elements (3x3)
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OrientationSample
        return timestampNs == other.timestampNs
    }

    override fun hashCode(): Int = timestampNs.hashCode()
}

class OrientationTracker(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Timestamp-indexed buffer (max 2 seconds of sensor samples ~200 samples at 100Hz)
    private val samples = ConcurrentSkipListMap<Long, OrientationSample>()
    private val maxBufferNs = 2_000_000_000L // 2 seconds

    @Volatile
    var currentSample: OrientationSample? = null
        private set

    @Volatile
    private var yawOffsetRad: Float = 0f

    @Volatile
    var displayRotation: Int = Surface.ROTATION_0

    companion object {
        private const val TAG = "OrientationTracker"
    }

    fun start(samplePeriodUs: Int = SensorManager.SENSOR_DELAY_GAME) {
        if (rotationSensor == null) {
            Log.e(TAG, "No rotation vector sensor available on device!")
            return
        }
        sensorManager.registerListener(this, rotationSensor, samplePeriodUs)
        Log.d(TAG, "OrientationTracker started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        samples.clear()
        Log.d(TAG, "OrientationTracker stopped")
    }

    /**
     * Zeros the current yaw so that facing direction reads 0 degrees.
     */
    fun zeroYaw() {
        val sample = currentSample ?: return
        val rawOrientation = FloatArray(3)
        SensorManager.getOrientation(sample.rotationMatrix, rawOrientation)
        yawOffsetRad = rawOrientation[0]
        Log.d(TAG, "Yaw zeroed to offset: Math.toDegrees($yawOffsetRad.toDouble()) deg")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != rotationSensor?.type) return

        val rotationMatrixRaw = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrixRaw, event.values)

        // Remap coordinate system according to current display rotation
        val remappedMatrix = FloatArray(9)
        when (displayRotation) {
            Surface.ROTATION_90 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrixRaw,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X,
                    remappedMatrix
                )
            }
            Surface.ROTATION_180 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrixRaw,
                    SensorManager.AXIS_MINUS_X,
                    SensorManager.AXIS_MINUS_Y,
                    remappedMatrix
                )
            }
            Surface.ROTATION_270 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrixRaw,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_X,
                    remappedMatrix
                )
            }
            else -> {
                // Surface.ROTATION_0
                SensorManager.remapCoordinateSystem(
                    rotationMatrixRaw,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Y,
                    remappedMatrix
                )
            }
        }

        // Calculate Euler angles (yaw, pitch, roll) in radians
        val orientation = FloatArray(3)
        SensorManager.getOrientation(remappedMatrix, orientation)

        var rawYaw = orientation[0] - yawOffsetRad
        // Normalize rawYaw to [-PI, PI]
        while (rawYaw > Math.PI) rawYaw -= (2 * Math.PI).toFloat()
        while (rawYaw < -Math.PI) rawYaw += (2 * Math.PI).toFloat()

        val pitch = orientation[1]
        val roll = orientation[2]

        val sample = OrientationSample(
            timestampNs = event.timestamp,
            rotationMatrix = remappedMatrix,
            yawDeg = Math.toDegrees(rawYaw.toDouble()).toFloat(),
            pitchDeg = Math.toDegrees(pitch.toDouble()).toFloat(),
            rollDeg = Math.toDegrees(roll.toDouble()).toFloat()
        )

        currentSample = sample
        samples[event.timestamp] = sample

        // Trim old samples outside 2 second window
        val cutoff = event.timestamp - maxBufferNs
        while (samples.isNotEmpty() && samples.firstKey() < cutoff) {
            samples.pollFirstEntry()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Interpolates rotation matrix at camera sensor timestamp for timestamp alignment.
     */
    fun getSampleAtTimestamp(timestampNs: Long): OrientationSample? {
        if (samples.isEmpty()) return currentSample

        val floorKey = samples.floorKey(timestampNs)
        val ceilingKey = samples.ceilingKey(timestampNs)

        return when {
            floorKey != null && ceilingKey != null && floorKey != ceilingKey -> {
                val s1 = samples[floorKey]!!
                val s2 = samples[ceilingKey]!!
                val factor = (timestampNs - floorKey).toFloat() / (ceilingKey - floorKey)
                interpolateSamples(s1, s2, factor)
            }
            floorKey != null -> samples[floorKey]
            ceilingKey != null -> samples[ceilingKey]
            else -> currentSample
        }
    }

    private fun interpolateSamples(
        s1: OrientationSample,
        s2: OrientationSample,
        factor: Float
    ): OrientationSample {
        val interpolatedMatrix = FloatArray(9)
        for (i in 0..8) {
            interpolatedMatrix[i] = s1.rotationMatrix[i] + factor * (s2.rotationMatrix[i] - s1.rotationMatrix[i])
        }

        val yaw = s1.yawDeg + factor * (s2.yawDeg - s1.yawDeg)
        val pitch = s1.pitchDeg + factor * (s2.pitchDeg - s1.pitchDeg)
        val roll = s1.rollDeg + factor * (s2.rollDeg - s1.rollDeg)

        return OrientationSample(
            timestampNs = (s1.timestampNs + factor * (s2.timestampNs - s1.timestampNs)).toLong(),
            rotationMatrix = interpolatedMatrix,
            yawDeg = yaw,
            pitchDeg = pitch,
            rollDeg = roll
        )
    }
}
