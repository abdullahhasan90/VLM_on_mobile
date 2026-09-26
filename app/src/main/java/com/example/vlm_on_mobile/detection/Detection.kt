package com.example.vlm_on_mobile.detection

data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class Detection(
    /** Normalized 0..1 in SOURCE IMAGE space. */
    val box: BoundingBox,
    val labelIndex: Int,
    val score: Float,
)

data class FrameResult(
    val detections: List<Detection>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inferenceTimeMs: Long,
)
