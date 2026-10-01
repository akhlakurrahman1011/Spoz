package com.raen.spoz.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class ActiveDragMode { NONE, BRIGHTNESS, VOLUME, SEEK }

enum class AspectRatioMode(val label: String, val resizeMode: Int) {
    FIT("Fit (16:9)", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM_FILL("Fill (Crop)", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL)
}

/**
 * High-performance gesture coordinator for mobile video playback:
 * - Tap: Toggle control overlay
 * - Double-Tap: -10s (left) / +10s (right) cumulative seeking
 * - Press & Hold: Temporary 2.0x speed boost with HUD
 * - Left vertical drag: Brightness adjustment HUD
 * - Right vertical drag: Volume adjustment HUD
 * - Horizontal drag: Slide-to-seek HUD (seeks on release)
 * - Pinch-to-zoom: Aspect ratio cycle
 * - Screen Lock: Suppresses gestures & controls
 */
@Composable
fun PlayerGestureSurface(
    isLocked: Boolean,
    onToggleLock: () -> Unit,
    currentPosition: Long,
    duration: Long,
    onTap: () -> Unit,
    onDoubleTapSeek: (isForward: Boolean, newTotalSeconds: Int) -> Unit,
    onHold2xStart: () -> Unit,
    onHold2xEnd: () -> Unit,
    onSeekCommit: (targetPosition: Long) -> Unit,
    onCycleAspectRatio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    // HUD States
    var showBrightnessHud by remember { mutableStateOf(false) }
    var brightnessLevel by remember { mutableFloatStateOf(0.5f) }
    var showVolumeHud by remember { mutableStateOf(false) }
    var volumeLevel by remember { mutableFloatStateOf(0.5f) }
    var showScrubHud by remember { mutableStateOf(false) }
    var scrubTargetPos by remember { mutableLongStateOf(0L) }
    var isHolding2x by remember { mutableStateOf(false) }

    // Double-tap tracking state
    var doubleTapCount by remember { mutableIntStateOf(0) }
    var isDoubleTapForward by remember { mutableStateOf(true) }
    var showDoubleTapOverlay by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var resetDoubleTapJob by remember { mutableStateOf<Job?>(null) }
    var autoHideLockBtnJob by remember { mutableStateOf<Job?>(null) }
    var showUnlockButton by remember { mutableStateOf(false) }

    // If screen is locked, only handle tap to reveal unlock button
    if (isLocked) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        showUnlockButton = true
                        autoHideLockBtnJob?.cancel()
                        autoHideLockBtnJob = coroutineScope.launch {
                            delay(3000)
                            showUnlockButton = false
                        }
                    }
                }
        ) {
            if (showUnlockButton) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.8f))
                        .clickable { onToggleLock() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🔒", fontSize = 22.sp)
                }
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Pinch to Zoom detector
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom > 1.25f || zoom < 0.75f) {
                        onCycleAspectRatio()
                    }
                }
            }
            .pointerInput(currentPosition, duration) {
                val touchSlop = 16f
                var lastTapTime = 0L
                var lastTapX = 0f

                awaitEachGesture {
                    val down: PointerInputChange = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val startY = down.position.y
                    val screenWidth = size.width.toFloat()
                    val screenHeight = size.height.toFloat()

                    var activeDrag = ActiveDragMode.NONE
                    var accumulatedDragX = 0f
                    var accumulatedDragY = 0f
                    var isHoldActive = false

                    // Initial audio & brightness readings
                    val initialVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                    volumeLevel = initialVol
                    val initialBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
                    brightnessLevel = if (initialBrightness < 0f) 0.5f else initialBrightness

                    // Hold-to-2X timer: triggers after 450ms if finger hasn't moved significantly
                    val holdJob = coroutineScope.launch {
                        delay(450)
                        if (activeDrag == ActiveDragMode.NONE) {
                            isHoldActive = true
                            isHolding2x = true
                            onHold2xStart()
                        }
                    }

                    var pointerUp = false
                    while (!pointerUp) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }

                        if (change == null || !change.pressed) {
                            pointerUp = true
                            holdJob.cancel()

                            if (isHoldActive) {
                                isHolding2x = false
                                isHoldActive = false
                                onHold2xEnd()
                            } else if (activeDrag == ActiveDragMode.SEEK) {
                                showScrubHud = false
                                onSeekCommit(scrubTargetPos)
                            } else if (activeDrag == ActiveDragMode.NONE) {
                                // Tap or Double-tap evaluation
                                val now = System.currentTimeMillis()
                                val isDoubleTapCandidate = (now - lastTapTime < 300) && (abs(startX - lastTapX) < 150)

                                if (isDoubleTapCandidate) {
                                    val isForward = startX > screenWidth / 2
                                    if (showDoubleTapOverlay && isDoubleTapForward == isForward) {
                                        doubleTapCount += 10
                                    } else {
                                        doubleTapCount = 10
                                        isDoubleTapForward = isForward
                                    }
                                    showDoubleTapOverlay = true
                                    onDoubleTapSeek(isForward, doubleTapCount)

                                    resetDoubleTapJob?.cancel()
                                    resetDoubleTapJob = coroutineScope.launch {
                                        delay(700)
                                        doubleTapCount = 0
                                    }
                                    lastTapTime = 0L
                                } else {
                                    lastTapTime = now
                                    lastTapX = startX
                                    coroutineScope.launch {
                                        delay(300)
                                        if (System.currentTimeMillis() - lastTapTime >= 300 && doubleTapCount == 0) {
                                            onTap()
                                        }
                                    }
                                }
                            }

                            // Dismiss HUDs on touch release
                            coroutineScope.launch {
                                delay(600)
                                showBrightnessHud = false
                                showVolumeHud = false
                            }
                            break
                        }

                        val posDelta = change.positionChange()
                        accumulatedDragX += posDelta.x
                        accumulatedDragY += posDelta.y

                        // Check if movement exceeds slop to cancel hold & start drag
                        if (activeDrag == ActiveDragMode.NONE) {
                            if (abs(accumulatedDragX) > touchSlop || abs(accumulatedDragY) > touchSlop) {
                                holdJob.cancel()
                                if (isHoldActive) {
                                    isHolding2x = false
                                    isHoldActive = false
                                    onHold2xEnd()
                                }

                                activeDrag = if (abs(accumulatedDragX) > abs(accumulatedDragY)) {
                                    ActiveDragMode.SEEK
                                } else if (startX < screenWidth / 2) {
                                    ActiveDragMode.BRIGHTNESS
                                } else {
                                    ActiveDragMode.VOLUME
                                }
                            }
                        }

                        // Handle active drag updates
                        if (activeDrag != ActiveDragMode.NONE) {
                            change.consume()
                            when (activeDrag) {
                                ActiveDragMode.SEEK -> {
                                    if (duration > 0) {
                                        showScrubHud = true
                                        val scrubFraction = accumulatedDragX / screenWidth
                                        val deltaMs = (scrubFraction * (duration.coerceAtMost(7200000L))).toLong()
                                        scrubTargetPos = (currentPosition + deltaMs).coerceIn(0L, duration)
                                    }
                                }
                                ActiveDragMode.BRIGHTNESS -> {
                                    showBrightnessHud = true
                                    val delta = -posDelta.y / screenHeight
                                    brightnessLevel = (brightnessLevel + delta).coerceIn(0.01f, 1.0f)
                                    activity?.let { act ->
                                        val lp = act.window.attributes
                                        lp.screenBrightness = brightnessLevel
                                        act.window.attributes = lp
                                    }
                                }
                                ActiveDragMode.VOLUME -> {
                                    showVolumeHud = true
                                    val delta = -posDelta.y / screenHeight
                                    volumeLevel = (volumeLevel + delta).coerceIn(0f, 1.0f)
                                    val newVolumeInt = (volumeLevel * maxVolume).toInt().coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolumeInt, 0)
                                }
                                ActiveDragMode.NONE -> Unit
                            }
                        }
                    }
                }
            }
    ) {
        // Floating HUDs overlayed on top of gesture surface

        // 1. Double-Tap Ripple Overlay (Left / Right)
        if (showDoubleTapOverlay) {
            DoubleTapSeekOverlay(
                isForward = isDoubleTapForward,
                seconds = doubleTapCount,
                onAnimationFinished = {
                    showDoubleTapOverlay = false
                },
                modifier = Modifier.align(if (isDoubleTapForward) Alignment.CenterEnd else Alignment.CenterStart)
            )
        }

        // 2. Brightness HUD (Center Left)
        if (showBrightnessHud) {
            VerticalIndicatorHud(
                value = brightnessLevel,
                iconText = "☀️",
                title = "Brightness",
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 3. Volume HUD (Center Right)
        if (showVolumeHud) {
            VerticalIndicatorHud(
                value = volumeLevel,
                iconText = "🔊",
                title = "Volume",
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 4. Slide-to-Seek Scrubbing HUD (Center)
        if (showScrubHud) {
            ScrubbingTimeHud(
                currentPos = currentPosition,
                targetPos = scrubTargetPos,
                duration = duration,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 5. Hold to 2X Speed HUD (Top Center)
        if (isHolding2x) {
            SpeedBoostHud(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
            )
        }
    }
}
