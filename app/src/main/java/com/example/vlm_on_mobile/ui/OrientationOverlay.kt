package com.example.vlm_on_mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vlm_on_mobile.orientation.BearingResult
import com.example.vlm_on_mobile.orientation.OrientationSample
import java.util.Locale

@Composable
fun OrientationOverlay(
    currentSample: OrientationSample?,
    centerBearing: BearingResult?,
    onResetYaw: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {

        // Center Reticle / Crosshair Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val lineLength = 30f
            val color = Color.Green

            // Horizontal crosshair
            drawLine(
                color = color,
                start = Offset(cx - lineLength, cy),
                end = Offset(cx + lineLength, cy),
                strokeWidth = 4f
            )
            // Vertical crosshair
            drawLine(
                color = color,
                start = Offset(cx, cy - lineLength),
                end = Offset(cx, cy + lineLength),
                strokeWidth = 4f
            )
            // Center dot
            drawCircle(color = Color.Red, radius = 6f, center = Offset(cx, cy))
        }

        // Top Info Box (Yaw, Pitch, Roll & Center Bearing)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = "Orientation (3D Pose)",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                if (currentSample != null) {
                    Text(
                        text = String.format(Locale.US, "Yaw:   %+.1f°", currentSample.yawDeg),
                        color = Color.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    Text(
                        text = String.format(Locale.US, "Pitch: %+.1f°", currentSample.pitchDeg),
                        color = Color.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    Text(
                        text = String.format(Locale.US, "Roll:  %+.1f°", currentSample.rollDeg),
                        color = Color.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                } else {
                    Text(text = "Waiting for orientation sensor...", color = Color.Yellow)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Reticle Center Ray",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                if (centerBearing != null) {
                    Text(
                        text = String.format(Locale.US, "Azimuth:   %+.1f°", centerBearing.azimuthDeg),
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(Locale.US, "Elevation: %+.1f°", centerBearing.elevationDeg),
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(text = "Waiting for camera intrinsics...", color = Color.Yellow)
                }
            }
        }

        // Bottom Reset Yaw Button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Button(onClick = onResetYaw) {
                Text("Zero Yaw Reference (0°)")
            }
        }
    }
}
