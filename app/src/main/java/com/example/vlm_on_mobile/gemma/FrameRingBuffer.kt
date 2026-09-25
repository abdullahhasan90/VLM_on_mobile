package com.example.vlm_on_mobile.gemma

import android.graphics.Bitmap
import kotlin.math.max

data class RingFrame(
    val timestampNs: Long,
    val bitmap: Bitmap,
    val angularSpeedDegPerSec: Float,
    val sharpnessScore: Float,
    val rotationMatrix: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RingFrame
        return timestampNs == other.timestampNs
    }

    override fun hashCode(): Int = timestampNs.hashCode()
}

class FrameRingBuffer(private val maxBufferNs: Long = 1_000_000_000L) {

    private val frames = mutableListOf<RingFrame>()

    companion object {
        const val MIN_SHARPNESS_FLOOR = 15.0f
    }

    @Synchronized
    fun addFrame(
        timestampNs: Long,
        bitmap: Bitmap,
        angularSpeedDegPerSec: Float,
        rotationMatrix: FloatArray
    ) {
        val scaledBmp = Bitmap.createScaledBitmap(bitmap, 512, 512, true)
        val sharpness = calculateSharpness(scaledBmp)

        val frame = RingFrame(
            timestampNs = timestampNs,
            bitmap = scaledBmp,
            angularSpeedDegPerSec = angularSpeedDegPerSec,
            sharpnessScore = sharpness,
            rotationMatrix = rotationMatrix.clone()
        )

        frames.add(frame)

        val cutoff = timestampNs - maxBufferNs
        val iterator = frames.iterator()
        while (iterator.hasNext()) {
            val f = iterator.next()
            if (f.timestampNs < cutoff) {
                f.bitmap.recycle()
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun selectBestFrame(): RingFrame? {
        val validFrames = frames.filter { it.sharpnessScore >= MIN_SHARPNESS_FLOOR }
        if (validFrames.isEmpty()) return frames.minByOrNull { it.angularSpeedDegPerSec }

        // Primary sort: Lowest angular speed, Secondary sort: Highest sharpness score
        return validFrames.minWithOrNull(
            compareBy<RingFrame> { it.angularSpeedDegPerSec }
                .thenByDescending { it.sharpnessScore }
        )
    }

    private fun calculateSharpness(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        var sum = 0.0
        var sqSum = 0.0
        var count = 0

        // 3x3 Laplacian Kernel: [0, 1, 0; 1, -4, 1; 0, 1, 0]
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val lap = (gray[idx - width] + gray[idx + width] + gray[idx - 1] + gray[idx + 1] - 4f * gray[idx]).toDouble()
                sum += lap
                sqSum += lap * lap
                count++
            }
        }

        if (count == 0) return 0f
        val mean = sum / count
        val variance = (sqSum / count) - (mean * mean)
        return max(variance.toFloat(), 0f)
    }

    @Synchronized
    fun clear() {
        for (f in frames) {
            f.bitmap.recycle()
        }
        frames.clear()
    }
}
