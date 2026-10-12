package com.shizington.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.shizington.tv.ui.components.BottomScrim
import com.shizington.tv.ui.components.StartScrim
import com.shizington.tv.ui.components.backdropRequest
import com.shizington.tv.ui.theme.AppColors

@Composable
fun Backdrop(image: String?) {
    Box(Modifier.fillMaxSize()) {
        if (image != null) {
            AsyncImage(
                model = backdropRequest(image),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxWidth(0.75f).fillMaxHeight(0.8f).align(Alignment.TopEnd),
            )
        }
        Box(Modifier.fillMaxSize().background(StartScrim))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f).background(BottomScrim))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.2f).align(Alignment.BottomCenter).background(AppColors.Background))
    }
}

@Composable
fun MetaRow(items: List<String>) {
    if (items.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach {
            Text(it, style = MaterialTheme.typography.labelLarge, color = if (it.startsWith("★")) AppColors.Rating else AppColors.Muted)
        }
    }
}

enum class ActionIcon(val vector: ImageVector) {
    Play(Icons.Filled.PlayArrow), Restart(Icons.Filled.Refresh), Favourite(Icons.Filled.Favorite), NotFavourite(Icons.Filled.FavoriteBorder),
    Trailer(Icons.Outlined.PlayArrow)
}

@Composable
fun ActionButton(text: String, icon: ActionIcon, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = if (primary) {
            ButtonDefaults.colors(
                containerColor = AppColors.Accent,
                contentColor = AppColors.OnBackground,
                focusedContainerColor = AppColors.OnBackground,
                focusedContentColor = AppColors.Background,
            )
        } else {
            ButtonDefaults.colors(
                containerColor = AppColors.SurfaceHigh,
                contentColor = AppColors.OnBackground,
                focusedContainerColor = AppColors.OnBackground,
                focusedContentColor = AppColors.Background,
            )
        },
    ) {
        Icon(icon.vector, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text, modifier = Modifier.padding(start = 8.dp))
    }
}
