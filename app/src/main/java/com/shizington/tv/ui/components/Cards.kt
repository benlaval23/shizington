package com.shizington.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shizington.tv.data.model.Stream
import com.shizington.tv.ui.theme.AppColors

private val CardShape = RoundedCornerShape(8.dp)

@Composable
private fun focusBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, AppColors.Focus), shape = CardShape),
)

@Composable
private fun cardColors() = CardDefaults.colors(containerColor = AppColors.Surface, focusedContainerColor = AppColors.Surface)

@Composable
private fun CardCaption(text: String, focused: Boolean, sub: String? = null) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) AppColors.OnBackground else AppColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.labelSmall, color = AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 2:3 artwork card for movies and series. */
@Composable
fun PosterCard(
    stream: Stream,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 108.dp,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
            shape = CardDefaults.shape(CardShape),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = focusBorder(),
            colors = cardColors(),
        ) {
            RemoteImage(tmdbSized(stream.icon, "w342"), stream.name, Modifier.fillMaxSize())
        }
        CardCaption(stream.name, focused)
    }
}

/** 16:9 card with a letterboxed logo, used for live channels. */
@Composable
fun ChannelCard(
    stream: Stream,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 168.dp,
    subtitle: String? = null,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
            shape = CardDefaults.shape(CardShape),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = focusBorder(),
            colors = cardColors(),
        ) {
            RemoteImage(
                stream.icon,
                stream.name,
                Modifier.fillMaxSize().background(AppColors.SurfaceHigh).padding(12.dp),
                contentScale = ContentScale.Fit,
                fallbackBackground = Color.Transparent,
            )
        }
        CardCaption(listOfNotNull(stream.num?.toString(), stream.name).joinToString("  "), focused, subtitle)
    }
}

/** 16:9 card with a progress bar, used for continue watching and episodes. */
@Composable
fun ProgressCard(
    title: String,
    image: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    subtitle: String? = null,
    onFocused: () -> Unit = {},
    badge: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
            shape = CardDefaults.shape(CardShape),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = focusBorder(),
            colors = cardColors(),
        ) {
            Box(Modifier.fillMaxSize()) {
                RemoteImage(tmdbSized(image, "w500"), title, Modifier.fillMaxSize())
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (progress != null && progress > 0f) {
                    ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp))
                }
            }
        }
        CardCaption(title, focused, subtitle)
    }
}
