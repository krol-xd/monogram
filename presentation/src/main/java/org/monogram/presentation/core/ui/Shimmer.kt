package org.monogram.presentation.core.ui

import androidx.compose.animation.core.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

@Composable
fun rememberShimmerBrush(
    durationMillis: Int = 1200,
    shimmerWidthPx: Float = 340f,
    initialOffset: Float = -600f,
    targetOffset: Float = 1200f
): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val transition = rememberInfiniteTransition(label = "shared_shimmer")
    val offset by transition.animateFloat(
        initialValue = initialOffset,
        targetValue = targetOffset,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shared_shimmer_offset"
    )

    return Brush.linearGradient(
        colors = listOf(base, base.copy(alpha = 0.18f), base),
        start = Offset(offset, 0f),
        end = Offset(offset + shimmerWidthPx, 0f)
    )
}
