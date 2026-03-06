package org.monogram.presentation.features.viewers.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.sign

@Composable
fun ImageSettingsMenu(
    onDownload: () -> Unit,
    onCopyImage: () -> Unit,
    onCopyLink: (() -> Unit)?,
    onCopyText: (() -> Unit)? = null,
    onForward: () -> Unit,
    onDelete: (() -> Unit)?
) {
    ViewerSettingsDropdown {
        MenuOptionRow(
            icon = Icons.Rounded.Download,
            title = "Download",
            onClick = onDownload
        )
        MenuOptionRow(
            icon = Icons.Rounded.ContentCopy,
            title = "Copy Image",
            onClick = onCopyImage
        )
        if (onCopyText != null) {
            MenuOptionRow(
                icon = Icons.Rounded.ContentCopy,
                title = "Copy Text",
                onClick = onCopyText
            )
        }
        if (onCopyLink != null) {
            MenuOptionRow(
                icon = Icons.Rounded.Link,
                title = "Copy Link",
                onClick = onCopyLink
            )
        }
        MenuOptionRow(
            icon = Icons.AutoMirrored.Rounded.Forward,
            title = "Forward",
            onClick = onForward
        )
        if (onDelete != null) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            MenuOptionRow(
                icon = Icons.Rounded.Delete,
                title = "Delete",
                onClick = onDelete,
                iconTint = MaterialTheme.colorScheme.error,
                textColor = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun ThumbnailStrip(
    images: List<Any>,
    pagerState: PagerState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    thumbnailSize: Dp = 60.dp,
    thumbnailSpacing: Dp = 8.dp
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val context = LocalContext.current

    LaunchedEffect(pagerState.currentPage) {
        val viewportWidth = listState.layoutInfo.viewportSize.width
        if (viewportWidth > 0) {
            val itemSizePx = with(density) { thumbnailSize.toPx() }
            val centerOffset = (viewportWidth / 2) - (itemSizePx / 2)

            listState.animateScrollToItem(
                index = pagerState.currentPage,
                scrollOffset = -centerOffset.toInt()
            )
        } else {
            listState.animateScrollToItem(pagerState.currentPage)
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .wrapContentWidth()
            .height(thumbnailSize + 24.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(thumbnailSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(
            images,
            key = { _, item -> item.hashCode() }
        ) { index, image ->
            val isSelected = pagerState.currentPage == index

            val scale by animateFloatAsState(targetValue = if (isSelected) 1.1f else 0.9f, label = "scale")
            val alpha by animateFloatAsState(targetValue = if (isSelected) 1f else 0.5f, label = "alpha")
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "border"
            )
            val borderWidth by animateDpAsState(targetValue = if (isSelected) 2.dp else 0.dp, label = "width")

            val request = remember(image) {
                ImageRequest.Builder(context)
                    .data(image)
                    .crossfade(true)
                    .allowHardware(true)
                    .build()
            }

            Box(
                modifier = Modifier
                    .size(thumbnailSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Gray.copy(alpha = 0.2f))
                    .clickable {
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    }
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = "Thumbnail $index",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(12.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun PageIndicator(modifier: Modifier = Modifier, current: Int, total: Int) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape
    ) {
        Text(
            text = "$current / $total",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun ZoomableImage(
    data: Any,
    zoomState: ZoomState,
    pageIndex: Int,
    pagerIndex: Int
) {
    val applyTransforms = pageIndex == pagerIndex
    val context = LocalContext.current

    var isHighResLoading by remember(data) { mutableStateOf(true) }

    val thumbnailRequest = remember(data) {
        ImageRequest.Builder(context)
            .data(data)
            .size(100, 100)
            .crossfade(true)
            .build()
    }

    val fullRequest = remember(data) {
        ImageRequest.Builder(context)
            .data(data)
            .size(Size.ORIGINAL)
            .precision(Precision.EXACT)
            .crossfade(true)
            .build()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = thumbnailRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (applyTransforms) {
                        translationX = zoomState.offsetX.value
                        translationY = zoomState.offsetY.value
                        scaleX = zoomState.scale.value
                        scaleY = zoomState.scale.value
                    }
                }
        )

        AsyncImage(
            model = fullRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (applyTransforms) {
                        translationX = zoomState.offsetX.value
                        translationY = zoomState.offsetY.value
                        scaleX = zoomState.scale.value
                        scaleY = zoomState.scale.value
                    }
                },
            onState = { state ->
                isHighResLoading = state is AsyncImagePainter.State.Loading
            }
        )

        if (isHighResLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color.White,
                strokeWidth = 3.dp
            )
        }
    }
}

suspend fun PointerInputScope.detectZoomAndDismissGestures(
    zoomState: ZoomState,
    rootState: DismissRootState,
    screenHeightPx: Float,
    dismissThreshold: Float,
    dismissVelocityThreshold: Float,
    onDismiss: () -> Unit,
    scope: CoroutineScope
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)

        val touchSlop = viewConfiguration.touchSlop
        var pan = Offset.Zero
        var isZooming = false
        var isVerticalDrag = false

        while (true) {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (canceled) break

            val pointerCount = event.changes.size
            event.changes.forEach { tracker.addPointerInputChange(it) }

            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()

            if (pointerCount > 1) isZooming = true

            if (!isZooming && !isVerticalDrag && zoomState.scale.value == 1f && pointerCount == 1) {
                pan += panChange
                val totalPan = pan.getDistance()
                if (totalPan > touchSlop) {
                    if (abs(pan.y) > abs(pan.x) * 2f) {
                        isVerticalDrag = true
                    } else if (abs(pan.x) > touchSlop) {
                        return@awaitEachGesture
                    }
                }
            }

            if (isZooming || zoomState.scale.value > 1f) {
                zoomState.onTransform(
                    scope,
                    panChange,
                    zoomChange,
                    IntSize(size.width, size.height),
                    3f
                )
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (isVerticalDrag) {
                val dragY = panChange.y
                scope.launch { rootState.drag(dragY) }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }

            if (!event.changes.any { it.pressed }) break
        }

        val velocity = tracker.calculateVelocity()

        if (zoomState.scale.value > 1f) {
            zoomState.ensureBounds(size.width.toFloat(), size.height.toFloat(), scope)
        } else if (isVerticalDrag) {
            val offsetY = rootState.offsetY.value
            val velocityY = velocity.y
            val shouldDismiss = abs(offsetY) > dismissThreshold || abs(velocityY) > dismissVelocityThreshold

            if (shouldDismiss) {
                scope.launch {
                    rootState.animateExit(screenHeightPx * sign(offsetY))
                    onDismiss()
                }
            } else {
                scope.launch { rootState.animateRestore() }
            }
        }
    }
}

class ZoomState {
    val scale = Animatable(1f)
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)

    fun resetInstant(scope: CoroutineScope) {
        scope.launch {
            scale.snapTo(1f)
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
        }
    }

    fun onDoubleTap(scope: CoroutineScope, tap: Offset, targetScale: Float, size: IntSize) {
        scope.launch {
            if (targetScale == 1f) {
                launch { scale.animateTo(1f, spring()) }
                launch { offsetX.animateTo(0f, spring()) }
                launch { offsetY.animateTo(0f, spring()) }
                return@launch
            }

            val currentScale = scale.value

            val centerX = size.width / 2f
            val centerY = size.height / 2f

            val zoomFactor = targetScale / currentScale

            val tapXFromCenter = tap.x - centerX
            val tapYFromCenter = tap.y - centerY

            val targetOffsetX = (tapXFromCenter * (1 - zoomFactor)) + (offsetX.value * zoomFactor)
            val targetOffsetY = (tapYFromCenter * (1 - zoomFactor)) + (offsetY.value * zoomFactor)

            val maxOffsetX = (size.width * (targetScale - 1f)) / 2f
            val maxOffsetY = (size.height * (targetScale - 1f)) / 2f

            val clampedX = targetOffsetX.coerceIn(-maxOffsetX, maxOffsetX)
            val clampedY = targetOffsetY.coerceIn(-maxOffsetY, maxOffsetY)

            launch { scale.animateTo(targetScale, spring()) }
            launch { offsetX.animateTo(clampedX, spring()) }
            launch { offsetY.animateTo(clampedY, spring()) }
        }
    }

    fun onTransform(scope: CoroutineScope, pan: Offset, zoomFactor: Float, size: IntSize, maxZoom: Float) {
        scope.launch {
            val newScale = (scale.value * zoomFactor).coerceIn(1f, maxZoom)
            scale.snapTo(newScale)

            if (scale.value > 1f) {
                val newX = offsetX.value + pan.x
                val newY = offsetY.value + pan.y

                val maxX = (size.width * (scale.value - 1f)) / 2f
                val maxY = (size.height * (scale.value - 1f)) / 2f

                offsetX.snapTo(newX.coerceIn(-maxX, maxX))
                offsetY.snapTo(newY.coerceIn(-maxY, maxY))
            } else {
                offsetX.snapTo(0f)
                offsetY.snapTo(0f)
            }
        }
    }

    fun ensureBounds(screenW: Float, screenH: Float, scope: CoroutineScope) {
        scope.launch {
            val maxX = (screenW * (scale.value - 1f)) / 2f
            val maxY = (screenH * (scale.value - 1f)) / 2f

            launch { offsetX.animateTo(offsetX.value.coerceIn(-maxX, maxX), spring()) }
            launch { offsetY.animateTo(offsetY.value.coerceIn(-maxY, maxY), spring()) }

            if (scale.value < 1f) {
                launch { scale.animateTo(1f, spring()) }
            }
        }
    }
}

class DismissRootState {
    val offsetY = Animatable(0f)
    val scale = Animatable(0.8f)
    val backgroundAlpha = Animatable(0f)

    fun resetInstant(scope: CoroutineScope) {
        scope.launch {
            offsetY.snapTo(0f)
            scale.snapTo(1f)
            backgroundAlpha.snapTo(1f)
        }
    }

    suspend fun animateExit(targetY: Float) = coroutineScope {
        launch {
            backgroundAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 150, easing = LinearEasing)
            )
        }

        launch {
            offsetY.animateTo(
                targetValue = targetY,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
            )
        }
    }

    suspend fun animateRestore() = coroutineScope {
        launch {
            offsetY.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
        }
        launch {
            scale.animateTo(1f, tween(150))
        }
        launch {
            backgroundAlpha.animateTo(1f, tween(150))
        }
    }

    suspend fun drag(delta: Float) {
        val currentY = offsetY.value
        val targetY = currentY + delta
        offsetY.snapTo(targetY)

        val progress = (targetY / 1000f).absoluteValue.coerceIn(0f, 1f)

        val newScale = 1f - (progress * 0.15f)
        scale.snapTo(newScale)

        val newAlpha = 1f - (progress * 0.8f)
        backgroundAlpha.snapTo(newAlpha)
    }
}

@Composable
fun rememberZoomState(): ZoomState = remember { ZoomState() }

@Composable
fun rememberDismissRootState(): DismissRootState = remember { DismissRootState() }
