package com.shizington.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.shizington.tv.ui.theme.AppColors

data class HeroData(
    val key: String,
    val label: String,
    val title: String,
    val image: String?,
    val meta: List<String> = emptyList(),
    val plot: String? = null,
    /** Transparent title artwork (TMDB); shown instead of the plain title when present. */
    val logo: String? = null,
    /** Why this is being suggested ("Because you watched …"). */
    val reason: String? = null,
)

/** Large backdrop banner that follows the focused item. */
@Composable
fun HeroBanner(
    hero: HeroData?,
    modifier: Modifier = Modifier,
    contentBottomPadding: androidx.compose.ui.unit.Dp = 12.dp,
    plotLines: Int = 3,
    logoHeight: androidx.compose.ui.unit.Dp = 96.dp,
) {
    Box(modifier.fillMaxWidth()) {
        Crossfade(targetState = hero?.image, animationSpec = tween(400), label = "hero-image", modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.72f)
            .align(Alignment.TopEnd)) { image ->
            if (image != null) {
                AsyncImage(model = backdropRequest(image), contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().background(StartScrim))
        Box(Modifier.fillMaxSize().background(BottomScrim))
        if (hero != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = RowStartPadding, bottom = contentBottomPadding)
                    .width(440.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(hero.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = AppColors.Accent)
                TitleOrLogo(hero.title, hero.logo, maxHeight = logoHeight)
                hero.reason?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = AppColors.OnBackground) }
                if (hero.meta.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        hero.meta.forEach { m ->
                            Text(m, style = MaterialTheme.typography.labelLarge, color = if (m.startsWith("★")) AppColors.Rating else AppColors.Muted)
                        }
                    }
                }
                if (!hero.plot.isNullOrBlank()) {
                    Text(hero.plot, style = MaterialTheme.typography.bodyMedium, color = AppColors.OnBackground.copy(alpha = 0.85f), maxLines = plotLines, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Shows the TMDB title logo when we have one (falls back to text while it loads or if it fails). */
@Composable
fun TitleOrLogo(title: String, logo: String?, maxWidth: androidx.compose.ui.unit.Dp = 380.dp, maxHeight: androidx.compose.ui.unit.Dp = 96.dp) {
    var failed by remember(logo) { mutableStateOf(logo == null) }
    if (!failed && logo != null) {
        AsyncImage(
            model = logo,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { failed = true },
            modifier = Modifier.width(maxWidth).heightIn(max = maxHeight),
        )
    } else {
        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
