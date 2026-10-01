package com.raen.spoz.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Floating glassmorphic HUD indicator for Brightness or Volume vertical swipe adjustments.
 */
@Composable
fun VerticalIndicatorHud(
    value: Float, // 0.0f to 1.0f
    iconText: String,
    title: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = iconText,
                fontSize = 18.sp
            )
            Column(modifier = Modifier.width(110.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${(value.coerceIn(0f, 1f) * 100).toInt()}%",
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // Linear Progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(value.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color(0xFFEF4444))
                    )
                }
            }
        }
    }
}

/**
 * Floating HUD during horizontal slide-to-seek showing Target Time, Delta offset, and Total Duration.
 */
@Composable
fun ScrubbingTimeHud(
    currentPos: Long,
    targetPos: Long,
    duration: Long,
    modifier: Modifier = Modifier
) {
    val deltaMs = targetPos - currentPos
    val sign = if (deltaMs >= 0) "+" else "-"
    val absDeltaSec = kotlin.math.abs(deltaMs) / 1000

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${formatTimeMs(targetPos)} / ${formatTimeMs(duration)}",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "[$sign${formatTimeMs(absDeltaSec * 1000)}]",
                color = if (deltaMs >= 0) Color(0xFF22C55E) else Color(0xFFEF4444),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Floating badge indicator displayed when user presses and holds for 2X playback speed.
 */
@Composable
fun SpeedBoostHud(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "2X ⏩",
                color = Color(0xFFEF4444),
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Fast Forwarding",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * YouTube-style Double-Tap Seek Animated Ripple Overlay.
 * Renders on left or right half with animated wave and cumulative seconds.
 */
@Composable
fun DoubleTapSeekOverlay(
    isForward: Boolean,
    seconds: Int,
    onAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alphaAnim = remember { Animatable(0f) }
    val scaleAnim = remember { Animatable(0.75f) }

    LaunchedEffect(seconds) {
        alphaAnim.snapTo(0.85f)
        scaleAnim.snapTo(0.8f)
        launch {
            scaleAnim.animateTo(
                targetValue = 1.05f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            )
        }
        delay(550)
        alphaAnim.animateTo(0f, animationSpec = tween(220))
        onAnimationFinished()
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.42f)
            .graphicsLayer {
                alpha = alphaAnim.value
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
            }
            .clip(
                if (isForward) RoundedCornerShape(topStartPercent = 100, bottomStartPercent = 100)
                else RoundedCornerShape(topEndPercent = 100, bottomEndPercent = 100)
            )
            .background(Color.White.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isForward) "⏩" else "⏪",
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isForward) "+${seconds}s" else "-${seconds}s",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
