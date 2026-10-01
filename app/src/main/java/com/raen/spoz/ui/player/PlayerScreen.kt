package com.raen.spoz.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.Player as ExoPlayerInterface
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.raen.spoz.StreamItem
import com.raen.spoz.network.SportzfyClient
import com.raen.spoz.pip.PipBroadcastReceiver
import com.raen.spoz.pip.PipHelper
import com.raen.spoz.ui.main.MainScreenViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class VideoQuality(
    val label: String,
    val isAuto: Boolean,
    val trackGroup: Tracks.Group? = null,
    val trackIndex: Int = -1,
    val height: Int = 0
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    streams: List<StreamItem>,
    startIndex: Int,
    eventName: String,
    onBack: () -> Unit
) {
    if (streams.isEmpty()) {
        onBack()
        return
    }

    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val viewModel: MainScreenViewModel = viewModel()
    val configuration = LocalConfiguration.current
    val isTvDevice = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var currentStreamsList by remember { mutableStateOf(streams) }
    var currentIndex by remember { mutableStateOf(startIndex) }
    var showOverlay by remember { mutableStateOf(false) }
    var isSeekbarFocused by remember { mutableStateOf(false) }
    var rightPanelEvents by remember { mutableStateOf<List<SportzfyClient.Event>?>(null) }
    var activeSourceIndex by remember { mutableStateOf(-1) }
    
    // Mobile player gesture states
    var isLocked by remember { mutableStateOf(false) }
    var currentAspectRatio by remember { mutableStateOf(AspectRatioMode.FIT) }
    var videoSurfaceBounds by remember { mutableStateOf<Rect?>(null) }

    var resolvedUrl by remember(currentIndex, currentStreamsList) { mutableStateOf("") }
    var isResolvingUrl by remember(currentIndex, currentStreamsList) { mutableStateOf(false) }
    var isEmbedWebView by remember(currentIndex, currentStreamsList) { mutableStateOf(false) }
    var autoSwitchEnabled by remember { mutableStateOf(false) }

    // Playback state
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    // Picture-in-Picture State
    val isPipMode by rememberIsInPipMode()

    // Qualities state
    var availableQualities by remember { mutableStateOf<List<VideoQuality>>(emptyList()) }
    var selectedQualityLabel by remember { mutableStateOf("Auto") }

    val currentStream = currentStreamsList.getOrNull(currentIndex) ?: currentStreamsList[0]

    // Focus requesters for TV navigation
    val focusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }

    // Screen On & TV Landscape enforcement
    DisposableEffect(isTvDevice) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (isTvDevice) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (isTvDevice) {
                activity?.requestedOrientation = originalOrientation
            }
        }
    }

    // Edge-to-Edge System Bar hiding in Fullscreen/Landscape
    DisposableEffect(isLandscape, showOverlay, isTvDevice) {
        if (!isTvDevice && activity != null) {
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            if (isLandscape && !showOverlay) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (activity != null) {
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Direct stream switch handler
    fun switchServer(index: Int) {
        val target = index.coerceIn(0, currentStreamsList.lastIndex)
        if (target != currentIndex) {
            currentIndex = target
            showOverlay = false
        }
    }

    // Overlay auto-hide trigger
    var autoHideCounter by remember { mutableIntStateOf(0) }
    fun triggerOverlay() {
        showOverlay = true
        autoHideCounter++
    }

    // Focus target switching
    LaunchedEffect(showOverlay, currentIndex) {
        if (isTvDevice) {
            if (showOverlay) {
                overlayFocusRequester.requestFocus()
            } else {
                focusRequester.requestFocus()
            }
        }
    }

    // Build Live LoadControl optimized for mobile sports streaming
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                /* minBufferMs = */ 10_000,
                /* maxBufferMs = */ 20_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500
            )
            .setBackBuffer(/* backBufferDurationMs = */ 0, /* retainBackBufferFromKeyframe = */ false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()
    }

    // Build AudioAttributes with Audio Focus & Becoming Noisy handling
    val audioAttributes = remember {
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }

    // Initialize ExoPlayer
    val exoPlayer = remember(currentIndex) {
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        val trackSelectionFactory = AdaptiveTrackSelection.Factory(
            /* minDurationForQualityIncreaseMs = */ 8_000,
            /* maxDurationForQualityDecreaseMs = */ 2_000,
            /* minDurationToRetainAfterDiscardMs = */ 4_000,
            /* bandwidthFraction = */ 0.75f
        )
        val trackSelector = DefaultTrackSelector(context, trackSelectionFactory)

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(10_000)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(bandwidthMeter)
        
        val referer = when {
            currentStream.url.contains("streamfree.top") -> "https://streamfree.top/"
            currentStream.url.contains("fwatch.tv") -> "https://fwatch.tv/"
            currentStream.url.contains("ebox.live") -> "https://tv.ebox.live/"
            else -> null
        }
        if (referer != null) {
            httpDataSourceFactory.setDefaultRequestProperties(mapOf(
                "Referer" to referer,
                "Origin" to referer.removeSuffix("/")
            ))
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))

        if (!currentStream.drmKid.isNullOrEmpty() && !currentStream.drmKey.isNullOrEmpty()) {
            val kidBase64 = hexToBase64Url(currentStream.drmKid)
            val keyBase64 = hexToBase64Url(currentStream.drmKey)
            val licenseJson = """{"keys":[{"kty":"oct","k":"$keyBase64","kid":"$kidBase64"}],"type":"temporary"}"""

            val drmSessionManager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(LocalMediaDrmCallback(licenseJson.toByteArray(Charsets.UTF_8)))

            mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
        }
        
        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                playWhenReady = true
            }
    }

    // PiP Broadcast RemoteAction Listener
    DisposableEffect(exoPlayer) {
        PipBroadcastReceiver.onPlayPauseToggle = {
            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        }
        onDispose {
            PipBroadcastReceiver.onPlayPauseToggle = null
        }
    }

    // Apply selected video quality track override
    fun selectQuality(quality: VideoQuality) {
        if (quality.isAuto) {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
            selectedQualityLabel = "Auto"
        } else {
            val group = quality.trackGroup ?: return
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, quality.trackIndex)
                )
                .build()
            selectedQualityLabel = quality.label
        }
    }

    // Player events listener
    DisposableEffect(exoPlayer) {
        var retryCount = 0
        val listener = object : ExoPlayerInterface.Listener {
            override fun onEvents(player: ExoPlayerInterface, events: ExoPlayerInterface.Events) {
                isPlaying = player.isPlaying
                duration = player.duration.coerceAtLeast(0L)
                currentPosition = player.currentPosition.coerceAtLeast(0L)
                isLoading = player.playbackState == ExoPlayerInterface.STATE_BUFFERING || player.playbackState == ExoPlayerInterface.STATE_IDLE

                // Update PiP parameters
                activity?.let { act ->
                    PipHelper.updatePipParams(
                        activity = act,
                        isPlaying = isPlaying,
                        videoWidth = player.videoSize.width,
                        videoHeight = player.videoSize.height,
                        sourceRect = videoSurfaceBounds
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == ExoPlayerInterface.STATE_READY) {
                    retryCount = 0
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val qualitiesList = mutableListOf<VideoQuality>()
                qualitiesList.add(VideoQuality(label = "Auto", isAuto = true))

                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_VIDEO) {
                        for (i in 0 until group.length) {
                            if (group.isTrackSupported(i)) {
                                val format = group.getTrackFormat(i)
                                val height = format.height
                                if (height > 0) {
                                    val label = "${height}p"
                                    val q = VideoQuality(
                                        label = label,
                                        isAuto = false,
                                        trackGroup = group,
                                        trackIndex = i,
                                        height = height
                                    )
                                    if (qualitiesList.none { it.height == height }) {
                                        qualitiesList.add(q)
                                    }
                                }
                            }
                        }
                    }
                }
                availableQualities = qualitiesList.sortedWith(
                    compareByDescending<VideoQuality> { it.isAuto }.thenByDescending { it.height }
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("Spoz", "Playback error on server ${currentIndex + 1}: ${error.message}")
                if (retryCount < 3) {
                    retryCount++
                    val backoffMs = (1000L * (1 shl (retryCount - 1))).coerceAtMost(3000L)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        exoPlayer.seekToDefaultPosition()
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }, backoffMs)
                } else if (autoSwitchEnabled && currentStreamsList.size > 1) {
                    retryCount = 0
                    switchServer((currentIndex + 1) % currentStreamsList.size)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Position update ticker
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(0L)
            delay(1000)
        }
    }

    // Playback source preparation
    LaunchedEffect(currentStream) {
        val stream = currentStream
        val liveConfig = MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(5_000L)
            .setMinOffsetMs(2_000L)
            .setMaxOffsetMs(15_000L)
            .setMinPlaybackSpeed(0.97f)
            .setMaxPlaybackSpeed(1.08f)
            .build()

        if (stream.type.lowercase() == "embed") {
            isResolvingUrl = true
            isEmbedWebView = false
            val resolver: (String) -> Unit = { resolved ->
                isResolvingUrl = false
                resolvedUrl = resolved
                val lower = resolved.lowercase()
                if (lower.contains(".m3u8") || lower.contains(".mpd")) {
                    isEmbedWebView = false
                    val mediaItemBuilder = MediaItem.Builder()
                        .setUri(resolved)
                        .setLiveConfiguration(liveConfig)

                    if (lower.contains(".mpd")) {
                        mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                    } else {
                        mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    }
                    exoPlayer.setMediaItem(mediaItemBuilder.build())
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                } else {
                    isEmbedWebView = true
                    exoPlayer.stop()
                    isLoading = false
                }
            }
            if (stream.url.contains("streamed.pk")) {
                com.raen.spoz.network.StreamedPkClient.resolveDirectStreamUrl(stream.url, resolver)
            } else if (stream.url.contains("streamfree.top")) {
                com.raen.spoz.network.StreamFreeClient.resolveDirectStreamUrl(stream.url, resolver)
            } else {
                resolver(stream.url)
            }
        } else {
            isResolvingUrl = false
            isEmbedWebView = false
            resolvedUrl = stream.url
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(stream.url)
                .setLiveConfiguration(liveConfig)

            if (stream.type.lowercase() == "dash") {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            } else {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            if (!stream.drmKid.isNullOrEmpty() && !stream.drmKey.isNullOrEmpty()) {
                mediaItemBuilder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build()
                )
            }
            exoPlayer.setMediaItem(mediaItemBuilder.build())
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    // Auto-hide overlay timer
    LaunchedEffect(showOverlay, autoHideCounter) {
        if (showOverlay) {
            delay(4500)
            showOverlay = false
        }
    }

    // Cycle Aspect Ratio mode
    fun cycleAspectRatio() {
        currentAspectRatio = when (currentAspectRatio) {
            AspectRatioMode.FIT -> AspectRatioMode.ZOOM_FILL
            AspectRatioMode.ZOOM_FILL -> AspectRatioMode.STRETCH
            AspectRatioMode.STRETCH -> AspectRatioMode.FIT
        }
        Toast.makeText(context, currentAspectRatio.label, Toast.LENGTH_SHORT).show()
    }

    // Toggle Screen Orientation on Mobile
    fun toggleOrientation() {
        if (activity != null && !isTvDevice) {
            activity.requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    // Back handling
    BackHandler {
        if (showOverlay) {
            showOverlay = false
        } else if (isLandscape && !isTvDevice) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            onBack()
        }
    }

    // Main UI Layout Container
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (showOverlay) {
                    triggerOverlay()
                }

                val nativeCode = keyEvent.nativeKeyEvent.keyCode
                if (nativeCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    val targetIdx = nativeCode - KeyEvent.KEYCODE_1
                    if (targetIdx in currentStreamsList.indices) {
                        switchServer(targetIdx)
                        return@onPreviewKeyEvent true
                    }
                }

                if (showOverlay) {
                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            if (isSeekbarFocused) {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 30000).coerceAtLeast(0L))
                                triggerOverlay()
                                return@onPreviewKeyEvent true
                            }
                        }
                        Key.DirectionRight -> {
                            if (isSeekbarFocused) {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 30000).coerceAtMost(duration))
                                triggerOverlay()
                                return@onPreviewKeyEvent true
                            }
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            if (isSeekbarFocused) {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                triggerOverlay()
                                return@onPreviewKeyEvent true
                            }
                        }
                        Key.Back -> {
                            showOverlay = false
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            }
    ) {
        // Video Viewport (Fullscreen in Landscape/TV, 16:9 anchored in Portrait)
        val videoBoxModifier = if (isLandscape || isTvDevice) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        }

        Box(modifier = videoBoxModifier.background(Color.Black)) {
            if (isEmbedWebView) {
                EnhancedStreamWebView(
                    url = resolvedUrl,
                    onPlaybackStateChange = { isPlaying = it },
                    onProgressUpdate = { pos, dur ->
                        currentPosition = pos
                        duration = dur
                    },
                    onError = { err ->
                        android.util.Log.w("Spoz", "WebView error: $err")
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = exoPlayer
                            useController = false
                            resizeMode = currentAspectRatio.resizeMode
                            focusable = View.NOT_FOCUSABLE
                            isFocusable = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { view ->
                        if (view.player != exoPlayer) {
                            view.player = exoPlayer
                        }
                        view.resizeMode = currentAspectRatio.resizeMode
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coords ->
                            val bounds = coords.boundsInWindow()
                            videoSurfaceBounds = Rect(
                                bounds.left.toInt(),
                                bounds.top.toInt(),
                                bounds.right.toInt(),
                                bounds.bottom.toInt()
                            )
                        }
                )
            }

            // Touch Gestures Surface (Mobile only, hidden in PiP)
            if (!isTvDevice && !isPipMode) {
                PlayerGestureSurface(
                    isLocked = isLocked,
                    onToggleLock = { isLocked = !isLocked },
                    currentPosition = currentPosition,
                    duration = duration,
                    onTap = { triggerOverlay() },
                    onDoubleTapSeek = { isForward, seconds ->
                        val delta = (seconds * 1000L)
                        val target = if (isForward) {
                            (exoPlayer.currentPosition + delta).coerceAtMost(duration)
                        } else {
                            (exoPlayer.currentPosition - delta).coerceAtLeast(0L)
                        }
                        exoPlayer.seekTo(target)
                    },
                    onHold2xStart = {
                        exoPlayer.setPlaybackSpeed(2.0f)
                    },
                    onHold2xEnd = {
                        exoPlayer.setPlaybackSpeed(1.0f)
                    },
                    onSeekCommit = { targetPos ->
                        exoPlayer.seekTo(targetPos)
                    },
                    onCycleAspectRatio = { cycleAspectRatio() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Hidden D-Pad key focus placeholder for Android TV
            if (isTvDevice && !showOverlay) {
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (keyEvent.key) {
                                Key.DirectionLeft -> {
                                    exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0L))
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionRight -> {
                                    exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(duration))
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter -> {
                                    triggerOverlay()
                                    return@onPreviewKeyEvent true
                                }
                                Key.Back -> {
                                    onBack()
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        }
                )
            }

            // Loading Spinner
            if (isLoading || isResolvingUrl) {
                CircularProgressIndicator(
                    color = Color(0xFFEF4444),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Fullscreen Overlay Controls (Landscape & TV)
            if (showOverlay && !isPipMode && (isLandscape || isTvDevice)) {
                PlayerOverlayUi(
                    currentStreamLabel = currentStream.label,
                    eventName = eventName,
                    currentStreamsList = currentStreamsList,
                    currentIndex = currentIndex,
                    availableQualities = availableQualities,
                    selectedQualityLabel = selectedQualityLabel,
                    currentPosition = currentPosition,
                    duration = duration,
                    isPlaying = isPlaying,
                    autoSwitchEnabled = autoSwitchEnabled,
                    isEmbedWebView = isEmbedWebView,
                    isLocked = isLocked,
                    isMobile = !isTvDevice,
                    currentAspectRatio = currentAspectRatio,
                    rightPanelEvents = rightPanelEvents,
                    activeSourceIndex = activeSourceIndex,
                    overlayFocusRequester = overlayFocusRequester,
                    onSwitchServer = { switchServer(it) },
                    onSelectQuality = { selectQuality(it) },
                    onToggleAutoSwitch = { autoSwitchEnabled = !autoSwitchEnabled },
                    onToggleLock = { isLocked = !isLocked },
                    onCycleAspectRatio = { cycleAspectRatio() },
                    onToggleOrientation = { toggleOrientation() },
                    onEnterPip = {
                        activity?.let { act ->
                            PipHelper.enterPip(
                                activity = act,
                                isPlaying = isPlaying,
                                videoWidth = exoPlayer.videoSize.width,
                                videoHeight = exoPlayer.videoSize.height,
                                sourceRect = videoSurfaceBounds
                            )
                        }
                    },
                    onTogglePlay = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    onSeek = { exoPlayer.seekTo(it) },
                    onSelectSource = { src ->
                        activeSourceIndex = src
                        Toast.makeText(context, "Loading Source $src guide...", Toast.LENGTH_SHORT).show()
                        viewModel.fetchEventsForSource(src) { events, error ->
                            if (error != null) {
                                Toast.makeText(context, error.localizedMessage ?: "Failed to fetch source matches", Toast.LENGTH_SHORT).show()
                            } else if (events != null) {
                                rightPanelEvents = events
                                triggerOverlay()
                            }
                        }
                    },
                    onSelectEvent = { event ->
                        Toast.makeText(context, "Fetching streams for ${event.teamAName}...", Toast.LENGTH_SHORT).show()
                        viewModel.fetchPlaybackForEvent(event) { resolvedStreams, err ->
                            if (err != null) {
                                Toast.makeText(context, err.localizedMessage ?: "Error loading streams", Toast.LENGTH_SHORT).show()
                            } else if (resolvedStreams != null) {
                                currentStreamsList = resolvedStreams.map {
                                    StreamItem(
                                        url = it.streamUrl,
                                        type = it.streamType,
                                        drmKid = it.drmKid,
                                        drmKey = it.drmKey,
                                        label = it.label
                                    )
                                }
                                currentIndex = 0
                                triggerOverlay()
                            }
                        }
                    },
                    onBack = {
                        if (isLandscape && !isTvDevice) {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            onBack()
                        }
                    },
                    onSeekbarFocusChange = { isSeekbarFocused = it },
                    onDismissOverlay = { showOverlay = false }
                )
            }
        }

        // Mobile Portrait Mode: Details & Server List below video
        if (!isLandscape && !isTvDevice) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = (configuration.screenWidthDp * 9 / 16).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Title and Quick Controls Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = eventName,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Stream: ${currentStream.label}",
                            color = Color(0xFFEF4444),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // PiP Button
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable {
                                    activity?.let { act ->
                                        PipHelper.enterPip(
                                            activity = act,
                                            isPlaying = isPlaying,
                                            videoWidth = exoPlayer.videoSize.width,
                                            videoHeight = exoPlayer.videoSize.height,
                                            sourceRect = videoSurfaceBounds
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "🪟", fontSize = 16.sp)
                        }

                        // Fullscreen Button
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                                .clickable { toggleOrientation() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "⛶", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Server Selector Header
                Text(
                    text = "Select Stream Server",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Servers Grid/Row
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(currentStreamsList) { idx, stream ->
                        val isSelected = idx == currentIndex
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Color(0xFFEF4444) else Color.White.copy(alpha = 0.08f))
                                .border(
                                    BorderStroke(1.dp, if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.15f)),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { switchServer(idx) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${idx + 1}. ${stream.label}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Sources Row
                Text(
                    text = "Switch Source Provider",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 2, 3, 4, 5, 6).forEach { src ->
                        val isSelected = activeSourceIndex == src
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFFEF4444) else Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    activeSourceIndex = src
                                    Toast.makeText(context, "Loading Source $src matches...", Toast.LENGTH_SHORT).show()
                                    viewModel.fetchEventsForSource(src) { events, error ->
                                        if (error != null) {
                                            Toast.makeText(context, error.localizedMessage ?: "Failed to fetch source matches", Toast.LENGTH_SHORT).show()
                                        } else if (events != null) {
                                            rightPanelEvents = events
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "Src $src", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Match Guide List if loaded
                if (!rightPanelEvents.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Source $activeSourceIndex Live Matches",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    rightPanelEvents!!.forEach { ev ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    Toast.makeText(context, "Fetching streams for ${ev.teamAName}...", Toast.LENGTH_SHORT).show()
                                    viewModel.fetchPlaybackForEvent(ev) { resolvedStreams, err ->
                                        if (err != null) {
                                            Toast.makeText(context, err.localizedMessage ?: "Error loading streams", Toast.LENGTH_SHORT).show()
                                        } else if (resolvedStreams != null) {
                                            currentStreamsList = resolvedStreams.map {
                                                StreamItem(
                                                    url = it.streamUrl,
                                                    type = it.streamType,
                                                    drmKid = it.drmKid,
                                                    drmKey = it.drmKey,
                                                    label = it.label
                                                )
                                            }
                                            currentIndex = 0
                                        }
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            Text(text = ev.teamAName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fullscreen Interactive Controls Overlay
 */
@Composable
fun PlayerOverlayUi(
    currentStreamLabel: String,
    eventName: String,
    currentStreamsList: List<StreamItem>,
    currentIndex: Int,
    availableQualities: List<VideoQuality>,
    selectedQualityLabel: String,
    currentPosition: Long,
    duration: Long,
    isPlaying: Boolean,
    autoSwitchEnabled: Boolean,
    isEmbedWebView: Boolean,
    isLocked: Boolean,
    isMobile: Boolean,
    currentAspectRatio: AspectRatioMode,
    rightPanelEvents: List<SportzfyClient.Event>?,
    activeSourceIndex: Int,
    overlayFocusRequester: FocusRequester,
    onSwitchServer: (Int) -> Unit,
    onSelectQuality: (VideoQuality) -> Unit,
    onToggleAutoSwitch: () -> Unit,
    onToggleLock: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleOrientation: () -> Unit,
    onEnterPip: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectSource: (Int) -> Unit,
    onSelectEvent: (SportzfyClient.Event) -> Unit,
    onBack: () -> Unit,
    onSeekbarFocusChange: (Boolean) -> Unit,
    onDismissOverlay: () -> Unit
) {
    val overlayPadding = if (isMobile) 14.dp else 24.dp
    val headerFontSize = if (isMobile) 13.sp else 18.sp
    val smallTextSize = if (isMobile) 10.sp else 13.sp
    val bodyTextSize = if (isMobile) 11.sp else 15.sp
    val buttonPaddingH = if (isMobile) 10.dp else 16.dp
    val buttonPaddingV = if (isMobile) 4.dp else 8.dp
    val sidebarWidth = if (isMobile) 180.dp else 260.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onDismissOverlay() }
            .padding(overlayPadding)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Top Toolbar: Back + Title + Quick Action Icons
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Back Button
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .clickable { onBack() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "←", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }

                            Column {
                                Text(
                                    text = eventName,
                                    color = Color.White,
                                    fontSize = headerFontSize,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = "Playing: $currentStreamLabel",
                                    color = Color(0xFFEF4444),
                                    fontSize = smallTextSize
                                )
                            }
                        }

                        // Right-hand utility actions: Aspect Ratio, Lock, PiP, Fullscreen
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Aspect Ratio Chip
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)), RoundedCornerShape(8.dp))
                                    .clickable { onCycleAspectRatio() }
                                    .padding(horizontal = buttonPaddingH, vertical = buttonPaddingV),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = currentAspectRatio.label, color = Color.White, fontSize = smallTextSize)
                            }

                            if (isMobile) {
                                // Screen Lock Toggle
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.15f))
                                        .clickable { onToggleLock() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = if (isLocked) "🔒" else "🔓", fontSize = 14.sp)
                                }

                                // PiP Toggle
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.15f))
                                        .clickable { onEnterPip() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = "🪟", fontSize = 14.sp)
                                }

                                // Orientation Toggle
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.15f))
                                        .clickable { onToggleOrientation() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = "⛶", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Sources Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.focusGroup()
                        ) {
                            Text("Source: ", color = Color.Gray, fontSize = smallTextSize)
                            listOf(1, 2, 3, 4, 5, 6).forEach { src ->
                                var isSrcFocused by remember { mutableStateOf(false) }
                                val isSelected = activeSourceIndex == src
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when {
                                                isSelected -> Color(0xFFEF4444)
                                                isSrcFocused -> Color.White.copy(alpha = 0.2f)
                                                else -> Color.Black.copy(alpha = 0.6f)
                                            }
                                        )
                                        .border(
                                            BorderStroke(1.dp, if (isSrcFocused) Color.White else Color.White.copy(alpha = 0.15f)),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .onFocusChanged { isSrcFocused = it.isFocused }
                                        .clickable { onSelectSource(src) }
                                        .padding(horizontal = if (isMobile) 10.dp else 14.dp, vertical = if (isMobile) 4.dp else 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = src.toString(), color = Color.White, fontSize = smallTextSize, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Auto-switch toggle
                        var isToggleFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (autoSwitchEnabled) Color(0xFFEF4444)
                                    else if (isToggleFocused) Color.White.copy(alpha = 0.2f)
                                    else Color.Black.copy(alpha = 0.6f)
                                )
                                .border(BorderStroke(1.dp, if (isToggleFocused) Color.White else Color.Transparent), shape = RoundedCornerShape(8.dp))
                                .onFocusChanged { isToggleFocused = it.isFocused }
                                .clickable { onToggleAutoSwitch() }
                                .padding(horizontal = buttonPaddingH, vertical = buttonPaddingV),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (autoSwitchEnabled) "Auto-Switch: ON" else "Auto-Switch: OFF",
                                color = Color.White,
                                fontSize = smallTextSize
                            )
                        }
                    }

                    // Servers Carousel Row
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Servers List",
                            color = Color.LightGray,
                            fontSize = smallTextSize,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(currentStreamsList) { idx, stream ->
                                var isFocused by remember { mutableStateOf(false) }
                                val isSelected = idx == currentIndex
                                val chipModifier = if (isSelected) Modifier.focusRequester(overlayFocusRequester) else Modifier

                                Box(
                                    modifier = chipModifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) Color(0xFFEF4444)
                                            else if (isFocused) Color.White.copy(alpha = 0.2f)
                                            else Color.Black.copy(alpha = 0.6f)
                                        )
                                        .border(BorderStroke(1.dp, if (isFocused) Color.White else Color.Transparent), shape = RoundedCornerShape(8.dp))
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .clickable { onSwitchServer(idx) }
                                        .padding(horizontal = buttonPaddingH, vertical = buttonPaddingV),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = "${idx + 1}. ${stream.label}", color = Color.White, fontSize = bodyTextSize)
                                }
                            }
                        }
                    }

                    // Video Qualities Row
                    if (!isEmbedWebView && availableQualities.size > 1) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Video Quality",
                                color = Color.LightGray,
                                fontSize = smallTextSize,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(availableQualities) { _, quality ->
                                    var isFocused by remember { mutableStateOf(false) }
                                    val isSelected = quality.label == selectedQualityLabel
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) Color(0xFFEF4444)
                                                else if (isFocused) Color.White.copy(alpha = 0.2f)
                                                else Color.Black.copy(alpha = 0.6f)
                                            )
                                            .border(BorderStroke(1.dp, if (isFocused) Color.White else Color.Transparent), shape = RoundedCornerShape(8.dp))
                                            .onFocusChanged { isFocused = it.isFocused }
                                            .clickable { onSelectQuality(quality) }
                                            .padding(horizontal = buttonPaddingH, vertical = buttonPaddingV),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = quality.label, color = Color.White, fontSize = bodyTextSize)
                                    }
                                }
                            }
                        }
                    }
                }

                // Center Play/Pause Button
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.4f)), CircleShape)
                        .clickable { onTogglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isPlaying) "⏸" else "▶",
                        color = Color.White,
                        fontSize = 24.sp
                    )
                }

                // Bottom Seekbar & Live Indicator
                if (!isEmbedWebView) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = if (isMobile) 2.dp else 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E))
                                )
                                Text(
                                    text = if (isPlaying) "LIVE" else "PAUSED",
                                    color = Color.White,
                                    fontSize = bodyTextSize,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "${formatTimeMs(currentPosition)} / ${formatTimeMs(duration)}",
                                color = Color.White,
                                fontSize = bodyTextSize
                            )
                        }

                        Slider(
                            value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                            onValueChange = { percent ->
                                onSeek((percent * duration).toLong())
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { onSeekbarFocusChange(it.isFocused) }
                                .focusable(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFEF4444),
                                activeTrackColor = Color(0xFFEF4444),
                                inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }

            // Right sidebar live events guide
            if (!rightPanelEvents.isNullOrEmpty()) {
                Spacer(modifier = Modifier.width(if (isMobile) 8.dp else 16.dp))
                Column(
                    modifier = Modifier
                        .width(sidebarWidth)
                        .fillMaxHeight()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(if (isMobile) 8.dp else 12.dp)
                ) {
                    Text(
                        text = "Live Events",
                        color = Color.LightGray,
                        fontSize = bodyTextSize,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(if (isMobile) 4.dp else 8.dp)
                    ) {
                        items(rightPanelEvents) { event ->
                            var isEvFocused by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isEvFocused) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                                    .border(BorderStroke(1.dp, if (isEvFocused) Color.White else Color.Transparent), shape = RoundedCornerShape(6.dp))
                                    .onFocusChanged { isEvFocused = it.isFocused }
                                    .clickable { onSelectEvent(event) }
                                    .padding(if (isMobile) 6.dp else 8.dp)
                            ) {
                                Text(
                                    text = event.teamAName,
                                    color = Color.White,
                                    fontSize = if (isMobile) 10.sp else 12.sp,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberIsInPipMode(): State<Boolean> {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val pipState = remember { mutableStateOf(activity?.isInPictureInPictureMode ?: false) }

    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose {}
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            pipState.value = info.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            activity.removeOnPictureInPictureModeChangedListener(listener)
        }
    }
    return pipState
}

private fun hexToBase64Url(hex: String): String {
    val clean = hex.trim().replace(":", "").replace("-", "")
    val bytes = ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return Base64.encodeToString(bytes, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
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
