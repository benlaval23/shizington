package com.shizington.tv.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.Stream
import com.shizington.tv.data.reco.DetailExtras
import com.shizington.tv.data.tmdb.TmdbPerson
import com.shizington.tv.ui.components.PosterCard
import com.shizington.tv.ui.components.RemoteImage
import com.shizington.tv.ui.components.RowSection
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors

/** The title as artwork when TMDB has a logo, plain text otherwise. */
@Composable
fun DetailTitle(name: String, logo: String?, height: Dp = 96.dp) {
    var failed by remember(logo) { mutableStateOf(logo == null) }
    if (!failed) {
        AsyncImage(
            model = logo,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = Modifier.height(height).fillMaxWidth(0.9f),
        )
    } else {
        Text(name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Cast, franchise and recommendation rows shared by the movie and series pages. */
fun LazyListScope.extrasRows(extras: DetailExtras?, navigator: AppNavigator, fallbackSimilar: List<Stream>) {
    val open: (Stream) -> Unit = { s -> if (s.type == ContentType.SERIES) navigator.openSeries(s.id) else navigator.openMovie(s.id) }
    val cast = extras?.details?.cast.orEmpty()
    if (cast.isNotEmpty()) item(key = "cast") {
        RowSection("Cast") {
            items(cast, key = { it.id }) { p -> PersonCard(p, onClick = { navigator.openPerson(p.id) }) }
        }
    }
    val franchise = extras?.franchise.orEmpty()
    if (franchise.isNotEmpty()) item(key = "franchise") {
        RowSection(extras?.franchiseName ?: "The whole collection") {
            items(franchise, key = { it.stream.key }) { f -> PosterCard(f.stream, onClick = { open(f.stream) }, width = 84.dp) }
        }
    }
    val more = extras?.moreLikeThis?.map { it.stream }?.takeIf { it.size >= 4 } ?: fallbackSimilar
    if (more.isNotEmpty()) item(key = "more") {
        RowSection("More like this") {
            items(more, key = { it.key }) { s -> PosterCard(s, onClick = { open(s) }, width = 84.dp) }
        }
    }
}

/** Same rows as [extrasRows], for pages that lay out in a plain scrolling Column. */
@Composable
fun ExtrasSections(extras: DetailExtras?, navigator: AppNavigator, fallbackSimilar: List<Stream>) {
    val open: (Stream) -> Unit = { s -> if (s.type == ContentType.SERIES) navigator.openSeries(s.id) else navigator.openMovie(s.id) }
    val cast = extras?.details?.cast.orEmpty()
    if (cast.isNotEmpty()) RowSection("Cast") {
        items(cast, key = { it.id }) { p -> PersonCard(p, onClick = { navigator.openPerson(p.id) }) }
    }
    val more = extras?.moreLikeThis?.map { it.stream }?.takeIf { it.size >= 4 } ?: fallbackSimilar
    if (more.isNotEmpty()) RowSection("More like this") {
        items(more, key = { it.key }) { s -> PosterCard(s, onClick = { open(s) }, width = 84.dp) }
    }
}

@Composable
fun PersonCard(p: TmdbPerson, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.size(84.dp).onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(CircleShape),
            scale = CardDefaults.scale(focusedScale = 1.1f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Focus), shape = CircleShape)),
            colors = CardDefaults.colors(containerColor = AppColors.SurfaceHigh),
        ) {
            RemoteImage(p.photo, initials(p.name), Modifier.size(84.dp))
        }
        Text(
            p.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (focused) AppColors.OnBackground else AppColors.OnBackground.copy(alpha = 0.85f),
        )
        p.role?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = 14.dp))
        }
    }
}

private fun initials(name: String) = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }

/** UK certification shown as a small boxed badge, e.g. [15]. */
@Composable
fun CertBadge(cert: String) {
    Box(Modifier.border(1.dp, AppColors.Muted, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(cert, style = MaterialTheme.typography.labelMedium, color = AppColors.OnBackground)
    }
}

fun trailerUrl(key: String?) = key?.let { "https://www.youtube.com/watch?v=$it" }
