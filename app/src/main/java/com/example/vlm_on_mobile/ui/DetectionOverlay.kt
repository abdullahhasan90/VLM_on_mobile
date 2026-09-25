package com.example.vlm_on_mobile.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vlm_on_mobile.tracking.MapEntry
import com.example.vlm_on_mobile.tracking.MapEntryState
import com.example.vlm_on_mobile.tracking.TrackedObject
import java.util.Locale

data class DetectedBoxOverlay(
    val track: TrackedObject,
    val azimuthDeg: Float,
    val elevationDeg: Float
)

@Composable
fun DetectionOverlay(
    boxes: List<DetectedBoxOverlay>,
    mapEntries: List<MapEntry>,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {

        // Canvas for bounding box drawings
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            val boxPaint = Paint().apply {
                color = android.graphics.Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = 6f
                isAntiAlias = true
            }

            val textPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 36f
                isAntiAlias = true
                setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            }

            val bgPaint = Paint().apply {
                color = android.graphics.Color.argb(180, 0, 0, 0)
                style = Paint.Style.FILL
            }

            for (item in boxes) {
                val box = item.track.currentBox
                // Map normalized 0..1 bounding box coordinates to Canvas pixel coordinates
                val left = box.left * canvasWidth
                val top = box.top * canvasHeight
                val right = box.right * canvasWidth
                val bottom = box.bottom * canvasHeight

                drawContext.canvas.nativeCanvas.drawRect(
                    RectF(left, top, right, bottom),
                    boxPaint
                )

                val labelText = String.format(
                    Locale.US,
                    "#%d %s (%.2f) [Az: %+.0f°, El: %+.0f°]",
                    item.track.trackId,
                    item.track.label,
                    item.track.confidence,
                    item.azimuthDeg,
                    item.elevationDeg
                )

                val textWidth = textPaint.measureText(labelText)
                val textTop = (top - 36f).coerceAtLeast(40f)

                drawContext.canvas.nativeCanvas.drawRect(
                    left,
                    textTop - 30f,
                    left + textWidth + 16f,
                    textTop + 10f,
                    bgPaint
                )

                drawContext.canvas.nativeCanvas.drawText(
                    labelText,
                    left + 8f,
                    textTop,
                    textPaint
                )
            }
        }

        // Spatial Direction Map List HUD
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .width(240.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = "Spatial Direction Map",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                if (mapEntries.isEmpty()) {
                    Text("No objects mapped yet", color = Color.Gray, fontSize = 12.sp)
                } else {
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(mapEntries) { entry ->
                            MapEntryRow(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapEntryRow(entry: MapEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val stateColor = when (entry.state) {
            MapEntryState.IN_VIEW -> Color.Green
            MapEntryState.OUT_OF_VIEW -> Color.Yellow
            MapEntryState.REMOVED -> Color.Red
        }

        Text(
            text = "•",
            color = stateColor,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.width(6.dp))

        Column {
            Text(
                text = "${entry.label} (#${entry.id})",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
            Text(
                text = String.format(Locale.US, "Az: %+.0f° | El: %+.0f°", entry.azimuthDeg, entry.elevationDeg),
                color = stateColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}
