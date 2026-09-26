package com.example.vlm_on_mobile.gemma

import android.graphics.Bitmap
import android.util.Log
import com.example.vlm_on_mobile.detection.BoundingBox
import com.example.vlm_on_mobile.events.AppEvent
import com.example.vlm_on_mobile.events.EventBuffer
import com.example.vlm_on_mobile.tracking.MapEntry
import com.example.vlm_on_mobile.tracking.VerificationState
import com.example.vlm_on_mobile.transcript.TranscriptWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class GemmaVerifier(
    private val narrator: GemmaNarrator,
    private val transcriptWriter: TranscriptWriter,
    private val eventBuffer: EventBuffer
) {

    companion object {
        private const val TAG = "GemmaVerifier"
    }

    suspend fun verifyEntry(
        entry: MapEntry,
        fullFrame: Bitmap,
        box: BoundingBox
    ): VerificationState = withContext(Dispatchers.Default) {
        val cropped = cropBoxWithMargin(fullFrame, box, marginFactor = 0.2f)
        val prompt = "Is there a ${entry.label} in this image? Answer only yes or no."

        Log.d(TAG, "Running verification query for entry #${entry.id} (${entry.label})...")
        val result = narrator.generateDescription(prompt, cropped)
        cropped.recycle()

        result.fold(
            onSuccess = { res ->
                val answer = res.text.trim().lowercase()
                when {
                    answer.startsWith("yes") -> {
                        entry.verifiedState = VerificationState.CONFIRMED_BY_GEMMA
                        Log.d(TAG, "Entry #${entry.id} (${entry.label}) VERIFIED YES by Gemma.")
                        VerificationState.CONFIRMED_BY_GEMMA
                    }
                    answer.startsWith("no") -> {
                        entry.verifiedState = VerificationState.CONTRADICTED
                        eventBuffer.emit(AppEvent.ObjectRemoved(entry, "Gemma verification NO"))

                        transcriptWriter.writeRecord(
                            source = "correction",
                            text = "Correction: that was not a ${entry.label}.",
                            azimuth = entry.azimuthDeg,
                            elevation = entry.elevationDeg,
                            objects = listOf(entry.id)
                        )
                        Log.d(TAG, "Entry #${entry.id} (${entry.label}) CONTRADICTED NO by Gemma.")
                        VerificationState.CONTRADICTED
                    }
                    else -> {
                        Log.w(TAG, "Unparseable verification answer from Gemma: '${res.text}'")
                        entry.verifiedState
                    }
                }
            },
            onFailure = { err ->
                Log.e(TAG, "Verification query failed for entry #${entry.id}", err)
                entry.verifiedState
            }
        )
    }

    private fun cropBoxWithMargin(bitmap: Bitmap, box: BoundingBox, marginFactor: Float): Bitmap {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val bw = (box.right - box.left) * w
        val bh = (box.bottom - box.top) * h

        val marginX = bw * marginFactor
        val marginY = bh * marginFactor

        val left = max(0f, box.left * w - marginX).toInt()
        val top = max(0f, box.top * h - marginY).toInt()
        val right = min(w, box.right * w + marginX).toInt()
        val bottom = min(h, box.bottom * h + marginY).toInt()

        val cropW = max(1, right - left)
        val cropH = max(1, bottom - top)

        return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
    }
}
