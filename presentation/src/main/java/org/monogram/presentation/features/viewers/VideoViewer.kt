package org.monogram.presentation.features.viewers

import androidx.compose.runtime.Composable
import org.monogram.presentation.core.util.IDownloadUtils

@Composable
fun VideoViewer(
    path: String,
    onDismiss: () -> Unit,
    onForward: (String) -> Unit = {},
    onDelete: ((String) -> Unit)? = null,
    onCopyLink: ((String) -> Unit)? = null,
    onCopyText: ((String) -> Unit)? = null,
    onSaveGif: ((String) -> Unit)? = null,
    caption: String? = null,
    fileId: Int = 0,
    supportsStreaming: Boolean = false,
    downloadUtils: IDownloadUtils,
    isGesturesEnabled: Boolean = true,
    isDoubleTapSeekEnabled: Boolean = true,
    seekDuration: Int = 10,
    isZoomEnabled: Boolean = true
) {
    MediaViewer(
        mediaItems = listOf(path),
        startIndex = 0,
        onDismiss = onDismiss,
        onForward = onForward,
        onDelete = onDelete,
        onCopyLink = onCopyLink,
        onCopyText = onCopyText,
        onSaveGif = onSaveGif,
        captions = listOf(caption),
        fileIds = listOf(fileId),
        supportsStreaming = supportsStreaming,
        downloadUtils = downloadUtils,
        isGesturesEnabled = isGesturesEnabled,
        isDoubleTapSeekEnabled = isDoubleTapSeekEnabled,
        seekDuration = seekDuration,
        isZoomEnabled = isZoomEnabled,
        isAlwaysVideo = true
    )
}
