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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
)

/** Large backdrop banner that follows the focused item. */
@Composable
fun HeroBanner(hero: HeroData?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Crossfade(targetState = hero?.image, animationSpec = tween(400), label = "hero-image", modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.72f)
            .align(Alignment.TopEnd)) { image ->
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().background(StartScrim))
        Box(Modifier.fillMaxSize().background(BottomScrim))
        if (hero != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = RowStartPadding, bottom = 12.dp)
                    .width(440.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(hero.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = AppColors.Accent)
                Text(hero.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (hero.meta.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        hero.meta.forEach { m ->
                            Text(m, style = MaterialTheme.typography.labelLarge, color = if (m.startsWith("★")) AppColors.Rating else AppColors.Muted)
                        }
                    }
                }
                if (!hero.plot.isNullOrBlank()) {
                    Text(hero.plot, style = MaterialTheme.typography.bodyMedium, color = AppColors.OnBackground.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
