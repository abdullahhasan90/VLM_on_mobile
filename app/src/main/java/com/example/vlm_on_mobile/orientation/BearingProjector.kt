package com.example.vlm_on_mobile.orientation

import android.hardware.camera2.CameraCharacteristics
import android.util.SizeF
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

data class CameraIntrinsics(
    val fx: Float, // Focal length x in pixels
    val fy: Float, // Focal length y in pixels
    val cx: Float, // Principal point x in pixels
    val cy: Float  // Principal point y in pixels
)

data class BearingResult(
    val azimuthDeg: Float,  // Horizontal angle relative to reference yaw (-180° to 180°)
    val elevationDeg: Float // Vertical angle relative to horizon (-90° to 90°)
)

object BearingProjector {

    /**
     * Extracts or estimates CameraIntrinsics from Camera2 CameraCharacteristics.
     */
    fun getIntrinsics(
        characteristics: CameraCharacteristics,
        imageWidth: Int,
        imageHeight: Int
    ): CameraIntrinsics {
        val calibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        if (calibration != null && calibration.size >= 5 && calibration[0] > 0f) {
            // LENS_INTRINSIC_CALIBRATION: [fx, fy, cx, cy, s]
            val fx = calibration[0]
            val fy = calibration[1]
            val cx = calibration[2]
            val cy = calibration[3]

            // Check if calibration is for sensor size or preview size
            val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            if (pixelArraySize != null && pixelArraySize.width > 0 && pixelArraySize.height > 0) {
                val scaleX = imageWidth.toFloat() / pixelArraySize.width
                val scaleY = imageHeight.toFloat() / pixelArraySize.height
                return CameraIntrinsics(
                    fx = fx * scaleX,
                    fy = fy * scaleY,
                    cx = cx * scaleX,
                    cy = cy * scaleY
                )
            }
            return CameraIntrinsics(fx, fy, cx, cy)
        }

        // Fallback: Estimate from focal length and physical sensor size
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: SizeF(6.4f, 4.8f) // Default fallback sensor size ~1/2.5"

        val focalLengthMm = focalLengths?.firstOrNull() ?: 4.0f
        val fx = (focalLengthMm / sensorSize.width) * imageWidth
        val fy = (focalLengthMm / sensorSize.height) * imageHeight
        val cx = imageWidth / 2f
        val cy = imageHeight / 2f

        return CameraIntrinsics(fx, fy, cx, cy)
    }

    /**
     * Projects pixel coordinate (u, v) in camera frame to world Azimuth and Elevation.
     */
    fun projectPixelToWorldBearing(
        u: Float,
        v: Float,
        intrinsics: CameraIntrinsics,
        rotationMatrix: FloatArray // 3x3 matrix (9 floats)
    ): BearingResult {
        // 1. Ray in camera coordinate system
        val rx = (u - intrinsics.cx) / intrinsics.fx
        val ry = (v - intrinsics.cy) / intrinsics.fy
        val rz = 1.0f

        // Normalize camera ray vector
        val norm = sqrt(rx * rx + ry * ry + rz * rz)
        val dxCam = rx / norm
        val dyCam = ry / norm
        val dzCam = rz / norm

        // 2. Transform ray into world coordinate system via R * d_cam
        // rotationMatrix R: [ R0 R1 R2
        //                    R3 R4 R5
        //                    R6 R7 R8 ]
        val dxWorld = rotationMatrix[0] * dxCam + rotationMatrix[1] * dyCam + rotationMatrix[2] * dzCam
        val dyWorld = rotationMatrix[3] * dxCam + rotationMatrix[4] * dyCam + rotationMatrix[5] * dzCam
        val dzWorld = rotationMatrix[6] * dxCam + rotationMatrix[7] * dyCam + rotationMatrix[8] * dzCam

        // 3. Compute Azimuth (horizontal) and Elevation (vertical)
        val azimuthRad = atan2(dxWorld.toDouble(), dzWorld.toDouble())
        val worldNorm = sqrt(dxWorld * dxWorld + dyWorld * dyWorld + dzWorld * dzWorld)
        val elevationRad = asin((dyWorld / worldNorm).coerceIn(-1.0f, 1.0f).toDouble())

        val azimuthDeg = Math.toDegrees(azimuthRad).toFloat()
        val elevationDeg = Math.toDegrees(elevationRad).toFloat()

        return BearingResult(azimuthDeg, elevationDeg)
    }
}
