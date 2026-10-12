package com.shizington.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.shizington.tv.data.model.EpgEntry
import com.shizington.tv.data.model.Stream
import com.shizington.tv.ui.theme.AppColors

/** Netflix-style Top 10 tile: a huge outlined rank number tucked behind the poster. */
@Composable
fun Top10Card(
    rank: Int,
    stream: Stream,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    Box(Modifier.width(196.dp).height(170.dp)) {
        Text(
            rank.toString(),
            style = TextStyle(
                fontSize = 150.sp,
                fontWeight = FontWeight.Black,
                color = AppColors.Background,
                shadow = Shadow(Color(0xFF8A8A92), blurRadius = 2f),
                letterSpacing = (-10).sp,
            ),
            modifier = Modifier.align(Alignment.BottomStart).offset(x = (-6).dp, y = 26.dp),
        )
        Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp)) {
            PosterCard(stream, onClick = onClick, modifier = modifier, width = 104.dp, onFocused = onFocused, onLongClick = onLongClick)
        }
    }
}

/** A live or upcoming sport programme on one of the household's channels. */
@Composable
fun SportCard(
    title: String,
    channel: String,
    icon: String?,
    topic: String,
    entry: EpgEntry,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeColor: Color = AppColors.Accent,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val live = entry.startMs <= now && entry.endMs > now
    Card(
        onClick = onClick,
        modifier = modifier.width(300.dp).height(150.dp).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Focus), shape = RoundedCornerShape(10.dp))),
        colors = CardDefaults.colors(containerColor = AppColors.SurfaceHigh, focusedContainerColor = AppColors.SurfaceHigh),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1E2A3A), Color(0xFF101418))))) {
            RemoteImage(
                icon, channel.substringAfter(":").trim().take(4),
                Modifier.align(Alignment.TopEnd).padding(12.dp).size(width = 72.dp, height = 44.dp),
                contentScale = ContentScale.Fit, fallbackBackground = Color.Transparent,
            )
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardBadge(badge ?: if (live) "LIVE" else formatClock(entry.startMs), color = if (live && badge == null) AppColors.Accent else badgeColor)
                    CardBadge(topic, color = Color(0xFF2F3A4A))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
                Text(channel, style = MaterialTheme.typography.labelMedium, color = AppColors.Muted, maxLines = 1)
                if (live) ProgressLine(entry.progress(now), Modifier.width(200.dp))
            }
        }
    }
}

data class MenuAction(val label: String, val onClick: () -> Unit)

/**
 * Long-press menu for a title: teaches the recommender ("More like this" / "Not for me") and manages
 * My List. Focus is trapped inside; Back closes it.
 */
@Composable
fun CardMenu(title: String, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(460.dp)
                .focusProperties { exit = { Cancel } }
                .focusGroup()
                .background(AppColors.Surface, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            actions.forEachIndexed { i, a ->
                Surface(
                    onClick = { a.onClick(); onDismiss() },
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = AppColors.SurfaceHigh,
                        contentColor = AppColors.OnBackground,
                        focusedContainerColor = AppColors.OnBackground,
                        focusedContentColor = Color.Black,
                    ),
                ) {
                    Text(a.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
                }
            }
        }
    }
}

/** Primary/secondary pill buttons used on the billboard. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, onFocused: () -> Unit = {}) {
    Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        colors = if (primary) {
            ButtonDefaults.colors(containerColor = Color.White, contentColor = Color.Black, focusedContainerColor = AppColors.Accent, focusedContentColor = Color.White)
        } else {
            ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.18f), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black)
        },
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}
