package com.shizington.tv.ui.components

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shizington.tv.ui.theme.AppColors

/*
 * Loading placeholders shaped like the content that's coming, so a screen has its layout from the first frame
 * instead of a spinner. One shimmer phase is shared by every block on screen so they sweep together.
 */

private val LocalShimmer = compositionLocalOf<State<Float>?> { null }

private val Base = Color(0xFF1C1F26)
private val Highlight = Color(0xFF2A2E38)

/** Provides the shared shimmer for every [SkeletonBlock] inside. Cheap: one animated float, drawn in the draw phase. */
@Composable
fun SkeletonScope(content: @Composable () -> Unit) {
    val phase = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    CompositionLocalProvider(LocalShimmer provides phase, content = content)
}

@Composable
fun SkeletonBlock(width: Dp, height: Dp, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    val phase = LocalShimmer.current
    Box(
        modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(corner))
            .drawBehind {
                // Read the phase here, not in composition, so the animation only redraws.
                val p = phase?.value ?: 0f
                val x = size.width * p
                drawRect(
                    Brush.linearGradient(
                        listOf(Base, Highlight, Base),
                        start = Offset(x - size.width * 0.6f, 0f),
                        end = Offset(x + size.width * 0.6f, size.height),
                    ),
                )
            },
    )
}

/** A row title and a run of cards, matching [RowSection]'s spacing. */
@Composable
fun SkeletonRow(
    title: String? = null,
    cardWidth: Dp = 108.dp,
    cardHeight: Dp = 162.dp,
    count: Int = 9,
    caption: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.Muted, modifier = Modifier.padding(start = RowStartPadding, bottom = 8.dp))
        } else {
            SkeletonBlock(180.dp, 18.dp, Modifier.padding(start = RowStartPadding, bottom = 12.dp), corner = 6.dp)
        }
        Row(Modifier.padding(start = RowStartPadding, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(count) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(cardWidth, cardHeight)
                    if (caption) SkeletonBlock(cardWidth * 0.7f, 12.dp, corner = 4.dp)
                }
            }
        }
    }
}

/** A few poster rows: the default for catalogue-style screens. */
@Composable
fun SkeletonRows(rows: Int = 3, modifier: Modifier = Modifier, message: String? = null) {
    SkeletonScope {
        Column(modifier) {
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = AppColors.Muted, modifier = Modifier.padding(start = RowStartPadding, bottom = 14.dp))
            }
            repeat(rows) { SkeletonRow() }
        }
    }
}

/** Title, meta line, plot and buttons: what a detail page looks like before its data arrives. */
@Composable
fun SkeletonDetail(modifier: Modifier = Modifier) {
    SkeletonScope {
        Column(modifier.padding(start = RowStartPadding, top = 64.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SkeletonBlock(420.dp, 64.dp, corner = 8.dp)
            SkeletonBlock(300.dp, 18.dp, corner = 6.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(640.dp, 14.dp, corner = 4.dp)
                SkeletonBlock(600.dp, 14.dp, corner = 4.dp)
                SkeletonBlock(480.dp, 14.dp, corner = 4.dp)
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SkeletonBlock(150.dp, 44.dp, corner = 22.dp)
                SkeletonBlock(130.dp, 44.dp, corner = 22.dp)
                SkeletonBlock(130.dp, 44.dp, corner = 22.dp)
            }
            Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(5) { SkeletonBlock(200.dp, 112.dp) }
            }
        }
    }
}

/** Stacked list rows, e.g. the live channel guide. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 8, rowHeight: Dp = 64.dp) {
    SkeletonScope {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(rows) { i ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SkeletonBlock(rowHeight * 1.6f, rowHeight, corner = 8.dp)
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBlock(if (i % 3 == 0) 260.dp else 340.dp, 16.dp, corner = 4.dp)
                        SkeletonBlock(if (i % 2 == 0) 420.dp else 300.dp, 12.dp, corner = 4.dp)
                    }
                }
            }
        }
    }
}
