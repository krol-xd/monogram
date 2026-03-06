package org.monogram.presentation.features.viewers.components

import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.stickers.ui.menu.MenuToggleRow

@Composable
fun VideoPlayerControls(
    visible: Boolean,
    isPlaying: Boolean,
    isEnded: Boolean,
    currentPosition: Long,
    totalDuration: Long,
    currentTime: String,
    isSettingsOpen: Boolean,
    caption: String? = null,
    downloadProgress: Float = 0f,
    onPlayPauseToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRewind: () -> Unit,
    onSettingsToggle: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentPosition, totalDuration, isDragging) {
        if (!isDragging && totalDuration > 0) {
            sliderPosition = currentPosition.toFloat() / totalDuration.toFloat()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ViewerTopBar(
                onBack = onBack,
                onActionClick = onSettingsToggle,
                isActionActive = isSettingsOpen
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRewind, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Replay10, "-10s", tint = Color.White, modifier = Modifier.fillMaxSize())
                }

                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(targetValue = if (isPressed) 0.9f else 1f, label = "scale")

                Box(
                    modifier = Modifier
                        .scale(scale)
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f))
                        .clickable(interactionSource = interactionSource, indication = null) { onPlayPauseToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isEnded -> Icons.Rounded.Replay
                            isPlaying -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        },
                        contentDescription = when {
                            isEnded -> "Restart"
                            isPlaying -> "Pause"
                            else -> "Play"
                        },
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp)
                    )
                }

                IconButton(onClick = onForward, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Forward10, "+10s", tint = Color.White, modifier = Modifier.fillMaxSize())
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.5f),
                                Color.Black.copy(alpha = 0.9f)
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp)
                    .padding(top = 32.dp, bottom = 24.dp)
            ) {
                if (!caption.isNullOrBlank()) {
                    ViewerCaption(caption = caption, showGradient = false)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${formatDuration(if (isDragging) (sliderPosition * totalDuration).toLong() else currentPosition)} / ${
                            formatDuration(
                                totalDuration
                            )
                        }",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )

                    Text(
                        text = currentTime,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (downloadProgress < 1f) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .padding(horizontal = 2.dp),
                            color = Color.White.copy(alpha = 0.2f),
                            trackColor = Color.Transparent,
                            strokeCap = StrokeCap.Round
                        )
                    }

                    Slider(
                        value = sliderPosition,
                        onValueChange = { isDragging = true; sliderPosition = it },
                        onValueChangeFinished = {
                            isDragging = false; onSeek((sliderPosition * totalDuration).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private enum class SettingsScreen {
    MAIN, SPEED, SCREENSHOT
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSettingsMenu(
    playbackSpeed: Float,
    repeatMode: Int,
    resizeMode: Int,
    isMuted: Boolean,
    isZoomEnabled: Boolean,
    onSpeedSelected: (Float) -> Unit,
    onRepeatToggle: () -> Unit,
    onResizeToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onLockToggle: () -> Unit,
    onRotationToggle: () -> Unit,
    onEnterPip: () -> Unit,
    onDownload: () -> Unit,
    onScreenshot: (Boolean) -> Unit,
    onCopyLink: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
    onForward: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    onSaveGif: (() -> Unit)? = null
) {
    var currentScreen by remember { mutableStateOf(SettingsScreen.MAIN) }

    ViewerSettingsDropdown {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState != SettingsScreen.MAIN) {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> -width } + fadeOut()
                } else {
                    slideInHorizontally { width -> -width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> width } + fadeOut()
                }
            },
            label = "SettingsNavigation"
        ) { screen ->
            when (screen) {
                SettingsScreen.MAIN -> {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )

                        MenuOptionRow(
                            icon = Icons.Rounded.Speed,
                            title = "Playback Speed",
                            value = "${playbackSpeed}x",
                            onClick = { currentScreen = SettingsScreen.SPEED },
                            trailingIcon = Icons.AutoMirrored.Rounded.KeyboardArrowRight
                        )

                        if (isZoomEnabled) {
                            MenuOptionRow(
                                icon = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) Icons.Rounded.AspectRatio else Icons.Rounded.FitScreen,
                                title = "Scale Mode",
                                value = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) "Fit" else "Zoom",
                                onClick = onResizeToggle
                            )
                        }

                        MenuOptionRow(
                            icon = Icons.Rounded.ScreenRotation,
                            title = "Rotate Screen",
                            onClick = onRotationToggle
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            MenuOptionRow(
                                icon = Icons.Rounded.PictureInPicture,
                                title = "Picture in Picture",
                                onClick = onEnterPip
                            )
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            MenuOptionRow(
                                icon = Icons.Rounded.Camera,
                                title = "Screenshot",
                                onClick = { currentScreen = SettingsScreen.SCREENSHOT },
                                trailingIcon = Icons.AutoMirrored.Rounded.KeyboardArrowRight
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )

                        MenuToggleRow(
                            icon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            title = "Loop Video",
                            isChecked = repeatMode == Player.REPEAT_MODE_ONE,
                            onCheckedChange = { onRepeatToggle() }
                        )

                        MenuToggleRow(
                            icon = if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            title = "Mute Audio",
                            isChecked = isMuted,
                            onCheckedChange = { onMuteToggle() }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )

                        MenuOptionRow(
                            icon = Icons.Rounded.Download,
                            title = "Download Video",
                            onClick = onDownload
                        )

                        if (onSaveGif != null) {
                            MenuOptionRow(
                                icon = Icons.Rounded.Gif,
                                title = "Save to GIFs",
                                onClick = onSaveGif
                            )
                        }

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

                        MenuOptionRow(
                            icon = Icons.Rounded.Lock,
                            title = "Lock Controls",
                            onClick = onLockToggle,
                            iconTint = MaterialTheme.colorScheme.primary,
                            textColor = MaterialTheme.colorScheme.primary
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

                SettingsScreen.SPEED -> {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentScreen = SettingsScreen.MAIN }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                "Back",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Playback Speed",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                        speeds.forEach { speed ->
                            SpeedSelectionRow(
                                speed = speed,
                                isSelected = playbackSpeed == speed,
                                onClick = { onSpeedSelected(speed) })
                        }
                    }
                }

                SettingsScreen.SCREENSHOT -> {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentScreen = SettingsScreen.MAIN }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                "Back",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Screenshot",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        MenuOptionRow(
                            icon = Icons.Rounded.Download,
                            title = "Save to Gallery",
                            onClick = { onScreenshot(false); currentScreen = SettingsScreen.MAIN })
                        MenuOptionRow(
                            icon = Icons.Rounded.ContentPaste,
                            title = "Copy to Clipboard",
                            onClick = { onScreenshot(true); currentScreen = SettingsScreen.MAIN })
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedSelectionRow(speed: Float, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (speed == 1f) "Normal" else "${speed}x",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        if (isSelected) {
            Icon(
                Icons.Rounded.Check,
                "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
