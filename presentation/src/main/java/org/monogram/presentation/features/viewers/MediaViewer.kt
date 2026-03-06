package org.monogram.presentation.features.viewers

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.repository.PlayerDataSourceFactory
import org.monogram.domain.repository.StreamingRepository
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.getMimeType
import org.monogram.presentation.features.viewers.components.*
import kotlin.math.max

@OptIn(ExperimentalFoundationApi::class, UnstableApi::class)
@Composable
fun MediaViewer(
    mediaItems: List<String>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
    autoDownload: Boolean = true,
    onPageChanged: ((Int) -> Unit)? = null,
    onForward: (String) -> Unit = {},
    onDelete: ((String) -> Unit)? = null,
    onCopyLink: ((String) -> Unit)? = null,
    onCopyText: ((String) -> Unit)? = null,
    onSaveGif: ((String) -> Unit)? = null,
    captions: List<String?> = emptyList(),
    fileIds: List<Int> = emptyList(),
    supportsStreaming: Boolean = false,
    downloadUtils: IDownloadUtils,
    showImageNumber: Boolean = true,
    isGesturesEnabled: Boolean = true,
    isDoubleTapSeekEnabled: Boolean = true,
    seekDuration: Int = 10,
    isZoomEnabled: Boolean = true,
    isAlwaysVideo: Boolean = false
) {
    require(mediaItems.isNotEmpty()) { "mediaItems can't be empty" }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { mediaItems.size }
    )

    val rootState = rememberDismissRootState()
    val zoomState = rememberZoomState()

    var showControls by remember { mutableStateOf(true) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var currentVideoInPipMode by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
    val dismissDistancePx = with(density) { 160.dp.toPx() }
    val dismissVelocityThreshold = with(density) { 1000.dp.toPx() }

    LaunchedEffect(Unit) {
        if (startIndex in mediaItems.indices) {
            pagerState.scrollToPage(startIndex)
        }
        launch {
            rootState.scale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium))
        }
        launch {
            rootState.backgroundAlpha.animateTo(1f, tween(150))
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged?.invoke(pagerState.currentPage)
        zoomState.resetInstant(scope)
        rootState.resetInstant(scope)
        showSettingsMenu = false
        currentVideoInPipMode = false
    }

    BackHandler {
        if (showSettingsMenu) {
            showSettingsMenu = false
        } else {
            if (currentVideoInPipMode) {
                context.findActivity()?.finishAndRemoveTask()
            } else {
                onDismiss()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = rootState.backgroundAlpha.value))
            .graphicsLayer {
                translationY = rootState.offsetY.value
                scaleX = rootState.scale.value
                scaleY = rootState.scale.value
            }
    ) {
        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fill,
            pageSpacing = 0.dp,
            beyondViewportPageCount = if (autoDownload) 1 else 0,
            userScrollEnabled = zoomState.scale.value == 1f && rootState.offsetY.value == 0f
        ) { page ->
            val path = mediaItems.getOrNull(page) ?: return@HorizontalPager
            val mimeType = getMimeType(path)
            val isVideo = isAlwaysVideo || mimeType?.startsWith("video/") == true ||
                    path.endsWith(".mp4", ignoreCase = true) ||
                    path.endsWith(".mkv", ignoreCase = true) ||
                    path.endsWith(".mov", ignoreCase = true) ||
                    path.endsWith(".webm", ignoreCase = true) ||
                    path.endsWith(".avi", ignoreCase = true) ||
                    path.endsWith(".3gp", ignoreCase = true) ||
                    path.endsWith(".m4v", ignoreCase = true)

            if (isVideo) {
                VideoPage(
                    path = path,
                    fileId = fileIds.getOrNull(page) ?: 0,
                    caption = captions.getOrNull(page),
                    supportsStreaming = supportsStreaming,
                    downloadUtils = downloadUtils,
                    onDismiss = onDismiss,
                    showControls = showControls,
                    onToggleControls = { showControls = !showControls },
                    onForward = onForward,
                    onDelete = onDelete,
                    onCopyLink = onCopyLink,
                    onCopyText = onCopyText,
                    onSaveGif = onSaveGif,
                    showSettingsMenu = showSettingsMenu,
                    onToggleSettings = { showSettingsMenu = !showSettingsMenu },
                    isGesturesEnabled = isGesturesEnabled,
                    isDoubleTapSeekEnabled = isDoubleTapSeekEnabled,
                    seekDuration = seekDuration,
                    isZoomEnabled = isZoomEnabled,
                    isActive = pagerState.currentPage == page,
                    onCurrentVideoPipModeChanged = { inPip ->
                        if (pagerState.currentPage == page) {
                            currentVideoInPipMode = inPip
                        }
                    }
                )
            } else {
                ImagePage(
                    path = path,
                    zoomState = zoomState,
                    rootState = rootState,
                    screenHeightPx = screenHeightPx,
                    dismissDistancePx = dismissDistancePx,
                    dismissVelocityThreshold = dismissVelocityThreshold,
                    onDismiss = onDismiss,
                    showControls = showControls,
                    onToggleControls = { showControls = !showControls },
                    pageIndex = page,
                    pagerIndex = pagerState.currentPage
                )
            }
        }

        val currentPath = mediaItems.getOrNull(pagerState.currentPage) ?: ""
        val currentMimeType = getMimeType(currentPath)
        val isCurrentVideo = isAlwaysVideo || currentMimeType?.startsWith("video/") == true ||
                currentPath.endsWith(".mp4", ignoreCase = true) ||
                currentPath.endsWith(".mkv", ignoreCase = true) ||
                currentPath.endsWith(".mov", ignoreCase = true) ||
                currentPath.endsWith(".webm", ignoreCase = true) ||
                currentPath.endsWith(".avi", ignoreCase = true) ||
                currentPath.endsWith(".3gp", ignoreCase = true) ||
                currentPath.endsWith(".m4v", ignoreCase = true)

        if (!isCurrentVideo) {
            ImageOverlay(
                showControls = showControls,
                rootState = rootState,
                pagerState = pagerState,
                mediaItems = mediaItems,
                captions = captions,
                showImageNumber = showImageNumber,
                onDismiss = onDismiss,
                showSettingsMenu = showSettingsMenu,
                onToggleSettings = { showSettingsMenu = !showSettingsMenu },
                downloadUtils = downloadUtils,
                onForward = onForward,
                onDelete = onDelete,
                onCopyLink = onCopyLink,
                onCopyText = onCopyText
            )
        }
    }
}

@Composable
private fun ImagePage(
    path: String,
    zoomState: ZoomState,
    rootState: DismissRootState,
    screenHeightPx: Float,
    dismissDistancePx: Float,
    dismissVelocityThreshold: Float,
    onDismiss: () -> Unit,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    pageIndex: Int,
    pagerIndex: Int
) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val currentScale = zoomState.scale.value
                        val targetScale = if (currentScale > 1.1f) 1f else 3f
                        zoomState.onDoubleTap(scope, offset, targetScale, size)
                    },
                    onTap = { onToggleControls() }
                )
            }
            .pointerInput(pagerIndex) {
                detectZoomAndDismissGestures(
                    zoomState = zoomState,
                    rootState = rootState,
                    screenHeightPx = screenHeightPx,
                    dismissThreshold = dismissDistancePx,
                    dismissVelocityThreshold = dismissVelocityThreshold,
                    onDismiss = onDismiss,
                    scope = scope
                )
            }
    ) {
        ZoomableImage(
            data = path,
            zoomState = zoomState,
            pageIndex = pageIndex,
            pagerIndex = pagerIndex
        )
    }
}

@Composable
private fun ImageOverlay(
    showControls: Boolean,
    rootState: DismissRootState,
    pagerState: androidx.compose.foundation.pager.PagerState,
    mediaItems: List<String>,
    captions: List<String?>,
    showImageNumber: Boolean,
    onDismiss: () -> Unit,
    showSettingsMenu: Boolean,
    onToggleSettings: () -> Unit,
    downloadUtils: IDownloadUtils,
    onForward: (String) -> Unit,
    onDelete: ((String) -> Unit)?,
    onCopyLink: ((String) -> Unit)?,
    onCopyText: ((String) -> Unit)?
) {
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = showControls && rootState.offsetY.value == 0f,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize()) {
            ViewerTopBar(
                onBack = onDismiss,
                onActionClick = onToggleSettings,
                isActionActive = showSettingsMenu,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val currentCaption = captions.getOrNull(pagerState.currentPage)
                if (!currentCaption.isNullOrBlank()) {
                    ViewerCaption(caption = currentCaption, showGradient = false)
                }

                if (mediaItems.size > 1) {
                    ThumbnailStrip(
                        images = mediaItems,
                        pagerState = pagerState,
                        scope = scope
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    if (showImageNumber) {
                        PageIndicator(
                            current = pagerState.currentPage + 1,
                            total = mediaItems.size
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    AnimatedVisibility(
        visible = showSettingsMenu && showControls,
        enter = fadeIn(tween(150)) + scaleIn(
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
            initialScale = 0.8f,
            transformOrigin = TransformOrigin(1f, 0f)
        ),
        exit = fadeOut(tween(150)) + scaleOut(
            animationSpec = tween(150),
            targetScale = 0.9f,
            transformOrigin = TransformOrigin(1f, 0f)
        ),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onToggleSettings()
                }
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 56.dp, end = 16.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            val currentIndex = pagerState.currentPage
            val currentItem = mediaItems.getOrNull(currentIndex)

            if (currentItem != null) {
                val currentCaption = captions.getOrNull(currentIndex)
                ImageSettingsMenu(
                    onDownload = {
                        downloadUtils.saveFileToDownloads(currentItem)
                        onToggleSettings()
                    },
                    onCopyImage = {
                        downloadUtils.copyImageToClipboard(currentItem)
                        onToggleSettings()
                    },
                    onCopyLink = {
                        onCopyLink?.invoke(currentItem)
                        onToggleSettings()
                    },
                    onCopyText = if (!currentCaption.isNullOrBlank()) {
                        {
                            onCopyText?.invoke(currentItem)
                            onToggleSettings()
                        }
                    } else null,
                    onForward = {
                        onForward(currentItem)
                        onToggleSettings()
                    },
                    onDelete = onDelete?.let {
                        {
                            it(currentItem)
                            onToggleSettings()
                        }
                    }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    path: String,
    fileId: Int,
    caption: String?,
    supportsStreaming: Boolean,
    downloadUtils: IDownloadUtils,
    onDismiss: () -> Unit,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onForward: (String) -> Unit,
    onDelete: ((String) -> Unit)?,
    onCopyLink: ((String) -> Unit)?,
    onCopyText: ((String) -> Unit)?,
    onSaveGif: ((String) -> Unit)?,
    showSettingsMenu: Boolean,
    onToggleSettings: () -> Unit,
    isGesturesEnabled: Boolean,
    isDoubleTapSeekEnabled: Boolean,
    seekDuration: Int,
    isZoomEnabled: Boolean,
    isActive: Boolean,
    onCurrentVideoPipModeChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val streamingRepository = koinInject<StreamingRepository>()
    val playerFactory = koinInject<PlayerDataSourceFactory>()
    val seekDurationMs = seekDuration * 1000L

    var isInPipMode by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var isEnded by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var isLocked by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var isMuted by remember { mutableStateOf(false) }

    var userScale by remember { mutableFloatStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }

    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText by remember { mutableStateOf<String?>(null) }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var forwardSeekFeedback by remember { mutableStateOf(false) }
    var rewindSeekFeedback by remember { mutableStateOf(false) }

    val pipId = remember(fileId, path) { if (fileId != 0) fileId else path.hashCode() }

    val downloadProgress by if (fileId != 0) {
        streamingRepository.getDownloadProgress(fileId).collectAsState(initial = 0f)
    } else {
        remember { mutableStateOf(0f) }
    }

    val exoPlayer = remember(path, fileId, supportsStreaming) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

        val playerBuilder = ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)

        val dataSourceFactory = if (supportsStreaming && fileId != 0) {
            playerFactory.createPayload(fileId) as DataSource.Factory
        } else {
            null
        }

        if (dataSourceFactory != null) {
            playerBuilder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
        } else {
            playerBuilder.setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
        }

        playerBuilder.build().apply {
            val mediaItem = MediaItem.Builder()
                .setUri(path)
                .setMimeType(getMimeType(path) ?: MimeTypes.VIDEO_MP4)
                .build()
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = isActive
        }
    }

    LaunchedEffect(isActive, isInPipMode) {
        if (isActive || isInPipMode) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    LaunchedEffect(isInPipMode, isActive) {
        if (isActive) {
            onCurrentVideoPipModeChanged(isInPipMode)
        } else {
            onCurrentVideoPipModeChanged(false)
        }
    }

    PipController(
        isPlaying = isPlaying,
        videoAspectRatio = videoAspectRatio,
        pipId = pipId,
        isActive = isActive,
        onPlay = { exoPlayer.play() },
        onPause = { exoPlayer.pause() },
        onRewind = { exoPlayer.seekTo(max(0, exoPlayer.currentPosition - seekDurationMs)) },
        onForward = { exoPlayer.seekTo(exoPlayer.currentPosition + seekDurationMs) },
        onPipModeChanged = {
            isInPipMode = it
            if (!it) {
                if (!isActive) exoPlayer.pause()
            }
        }
    )

    DisposableEffect(lifecycleOwner) {
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            val isPip = activity?.isInPictureInPictureMode == true

            if (event == Lifecycle.Event.ON_PAUSE && !isPip) exoPlayer.pause()
            if (event == Lifecycle.Event.ON_STOP && !isPip) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
                isPlaying = isPlayingChanged
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                isEnded = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = if ((videoSize.unappliedRotationDegrees / 90) % 2 == 1)
                        videoSize.height.toFloat() / videoSize.width.toFloat()
                    else videoSize.width.toFloat() / videoSize.height.toFloat()

                    if (ratio != videoAspectRatio) {
                        videoAspectRatio = ratio
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            if (totalDuration <= 0L) totalDuration = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    LaunchedEffect(playbackSpeed) { exoPlayer.setPlaybackSpeed(playbackSpeed) }
    LaunchedEffect(repeatMode) { exoPlayer.repeatMode = repeatMode }
    LaunchedEffect(isMuted) { exoPlayer.volume = if (isMuted) 0f else 1f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isLocked, userScale, isInPipMode) {
                if (isInPipMode) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (userScale > 1f) {
                            userScale = 1f; userOffset = Offset.Zero
                        } else if (!isLocked && isDoubleTapSeekEnabled) {
                            val width = size.width
                            if (offset.x < width / 2) {
                                exoPlayer.seekTo(max(0, exoPlayer.currentPosition - seekDurationMs))
                                rewindSeekFeedback = true
                            } else {
                                exoPlayer.seekTo(exoPlayer.currentPosition + seekDurationMs)
                                forwardSeekFeedback = true
                            }
                        }
                    },
                    onTap = { onToggleControls() }
                )
            }
            .pointerInput(isLocked, userScale, isInPipMode) {
                if (isInPipMode) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { if (!isLocked && isGesturesEnabled && userScale == 1f) showGestureOverlay = true },
                    onDragEnd = { showGestureOverlay = false },
                    onDragCancel = { showGestureOverlay = false }
                ) { change, dragAmount ->
                    if (!isLocked && isGesturesEnabled && userScale == 1f) {
                        val width = size.width
                        val x = change.position.x
                        val isLeft = x < width / 2
                        val activity = context.findActivity()

                        if (isLeft && activity != null) {
                            val lp = activity.window.attributes
                            var newBrightness =
                                (lp.screenBrightness.takeIf { it != -1f } ?: 0.5f) - (dragAmount / 1000f)
                            newBrightness = newBrightness.coerceIn(0f, 1f)
                            lp.screenBrightness = newBrightness
                            activity.window.attributes = lp
                            gestureIcon = Icons.Rounded.BrightnessMedium
                            gestureText = "${(newBrightness * 100).toInt()}%"
                        } else {
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val delta = -(dragAmount / 50f)
                            val newVol = (currentVol + delta).coerceIn(0f, maxVol.toFloat())
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol.toInt(), 0)
                            gestureIcon = Icons.AutoMirrored.Rounded.VolumeUp
                            gestureText = "${((newVol / maxVol) * 100).toInt()}%"
                        }
                    }
                }
            }
            .pointerInput(isLocked, isInPipMode) {
                if (isInPipMode) return@pointerInput
                if (!isLocked && isZoomEnabled) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        userScale = (userScale * zoom).coerceIn(1f, 3f)
                        if (userScale > 1f) {
                            val maxTranslationX = size.width * (userScale - 1) / 2
                            val maxTranslationY = size.height * (userScale - 1) / 2
                            userOffset = Offset(
                                x = (userOffset.x + pan.x).coerceIn(-maxTranslationX, maxTranslationX),
                                y = (userOffset.y + pan.y).coerceIn(-maxTranslationY, maxTranslationY)
                            )
                        } else {
                            userOffset = Offset.Zero
                        }
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!isInPipMode) {
                        scaleX = userScale
                        scaleY = userScale
                        translationX = userOffset.x
                        translationY = userOffset.y
                    }
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        setResizeMode(if (isInPipMode) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeMode)
                        playerView = this
                    }
                },
                update = { view ->
                    view.resizeMode = if (isInPipMode) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeMode
                    playerView = view
                },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (!isInPipMode) {
            if (isBuffering) CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )

            SeekFeedback(
                rewindSeekFeedback,
                true,
                seekDuration,
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 64.dp)
            )
            SeekFeedback(
                forwardSeekFeedback,
                false,
                seekDuration,
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 64.dp)
            )
            GestureOverlay(showGestureOverlay, gestureIcon, gestureText, Modifier.align(Alignment.Center))

            if (isLocked) {
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        IconButton(
                            onClick = { isLocked = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(16.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                        ) { Icon(Icons.Rounded.Lock, "Unlock", tint = MaterialTheme.colorScheme.onSurface) }
                    }
                }
            } else {
                VideoPlayerControls(
                    visible = showControls,
                    isPlaying = isPlaying,
                    isEnded = isEnded,
                    currentPosition = currentPosition,
                    totalDuration = totalDuration,
                    currentTime = currentTime(),
                    isSettingsOpen = showSettingsMenu,
                    caption = caption,
                    downloadProgress = downloadProgress,
                    onPlayPauseToggle = {
                        if (isEnded) {
                            exoPlayer.seekTo(0); exoPlayer.play()
                        } else {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }
                    },
                    onSeek = { exoPlayer.seekTo(it) },
                    onBack = onDismiss,
                    onForward = { exoPlayer.seekTo(exoPlayer.currentPosition + seekDurationMs) },
                    onRewind = { exoPlayer.seekTo(max(0, exoPlayer.currentPosition - seekDurationMs)) },
                    onSettingsToggle = onToggleSettings
                )

                AnimatedVisibility(
                    visible = showSettingsMenu && showControls,
                    enter = fadeIn(tween(150)) + scaleIn(
                        animationSpec = spring(
                            dampingRatio = 0.8f,
                            stiffness = Spring.StiffnessMedium
                        ), initialScale = 0.8f, transformOrigin = TransformOrigin(1f, 0f)
                    ),
                    exit = fadeOut(tween(150)) + scaleOut(
                        animationSpec = tween(150),
                        targetScale = 0.9f,
                        transformOrigin = TransformOrigin(1f, 0f)
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 56.dp, end = 16.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                        VideoSettingsMenu(
                            playbackSpeed = playbackSpeed,
                            repeatMode = repeatMode,
                            resizeMode = resizeMode,
                            isMuted = isMuted,
                            isZoomEnabled = isZoomEnabled,
                            onSpeedSelected = { playbackSpeed = it },
                            onRepeatToggle = {
                                repeatMode =
                                    if (repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                            },
                            onResizeToggle = {
                                resizeMode =
                                    if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                            },
                            onMuteToggle = { isMuted = !isMuted },
                            onLockToggle = { isLocked = true; onToggleSettings() },
                            onRotationToggle = {
                                val activity = context.findActivity()
                                activity?.requestedOrientation =
                                    if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            },
                            onEnterPip = {
                                onToggleSettings()
                                enterPipMode(context, isPlaying, videoAspectRatio, pipId)
                            },
                            onDownload = { downloadUtils.saveFileToDownloads(path); onToggleSettings() },
                            onCopyLink = { onCopyLink?.invoke(path); onToggleSettings() },
                            onCopyText = if (!caption.isNullOrBlank()) {
                                { onCopyText?.invoke(path); onToggleSettings() }
                            } else null,
                            onForward = { onForward(path); onToggleSettings() },
                            onDelete = onDelete?.let { { it(path); onToggleSettings() } },
                            onScreenshot = { toClipboard ->
                                val view = playerView ?: return@VideoSettingsMenu
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val surfaceView = view.videoSurfaceView as? SurfaceView ?: return@VideoSettingsMenu
                                    val bitmap = createBitmap(surfaceView.width, surfaceView.height)
                                    PixelCopy.request(surfaceView, bitmap, { result ->
                                        if (result == PixelCopy.SUCCESS) {
                                            if (toClipboard) downloadUtils.copyBitmapToClipboard(bitmap) else downloadUtils.saveBitmapToGallery(
                                                bitmap
                                            )
                                        }
                                    }, Handler(Looper.getMainLooper()))
                                }
                                onToggleSettings()
                            },
                            onSaveGif = onSaveGif?.let { { it(path); onToggleSettings() } }
                        )
                    }
                }
            }
        }
    }
}

private fun currentTime(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
