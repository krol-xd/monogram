package org.monogram.presentation.features.chats.currentChat.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

@Composable
fun MessageListShimmer() {
    val shimmer = rememberMessageShimmerBrush()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        repeat(9) { index ->
            val isOutgoing = index % 3 == 0
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (isOutgoing) 0.58f else 0.72f)
                        .height(if (index % 2 == 0) 38.dp else 52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(shimmer)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun rememberMessageShimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val transition = rememberInfiniteTransition(label = "message_shimmer")
    val offset by transition.animateFloat(
        initialValue = -600f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "message_shimmer_offset"
    )

    return Brush.linearGradient(
        colors = listOf(base, base.copy(alpha = 0.15f), base),
        start = Offset(offset, 0f),
        end = Offset(offset + 360f, 0f)
    )
}
