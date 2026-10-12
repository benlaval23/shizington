package com.shizington.tv.ui.detail

import com.shizington.tv.ui.components.SkeletonDetail
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shizington.tv.AppContainer
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.MovieInfo
import com.shizington.tv.data.model.PlaybackRequest
import com.shizington.tv.data.model.Playable
import com.shizington.tv.data.model.Stream
import com.shizington.tv.data.model.formatRating
import com.shizington.tv.ui.browse.ListState
import com.shizington.tv.ui.components.ErrorMessage
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.PosterCard
import com.shizington.tv.ui.components.ProgressLine
import com.shizington.tv.ui.components.RowSection
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import com.shizington.tv.data.reco.DetailExtras
import com.shizington.tv.ui.components.CardBadge
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.formatDuration
import com.shizington.tv.ui.components.tryRequestFocus
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MovieDetailViewModel(private val id: Long, private val c: AppContainer) : ViewModel() {
    var info by mutableStateOf<ListState<MovieInfo>>(ListState.Loading)
        private set
    val isFavourite = c.library.isFavourite(ContentType.MOVIE, id).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val progress = c.library.progress(ContentType.MOVIE, id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var similar by mutableStateOf<List<Stream>>(emptyList())
        private set
    var extras by mutableStateOf<DetailExtras?>(null)
        private set

    init { load() }

    fun load() {
        info = ListState.Loading
        viewModelScope.launch {
            info = try {
                val movie = c.xtream.movieInfo(id)
                launch { similar = runCatching { c.xtream.similar(ContentType.MOVIE, id, movie.genre) }.getOrDefault(emptyList()) }
                launch {
                    extras = runCatching {
                        c.recommendations.detailExtras(ContentType.MOVIE, id, movie.name, movie.releaseDate?.take(4)?.toIntOrNull(), movie.tmdbId)
                    }.getOrNull()
                }
                ListState.Ready(movie)
            } catch (e: Exception) {
                ListState.Failed(e.message ?: "Couldn't load this movie")
            }
        }
    }

    fun toggleFavourite(m: MovieInfo) {
        viewModelScope.launch {
            c.library.setFavourite(Stream(ContentType.MOVIE, m.id, m.name, m.poster, m.categoryId, m.extension, m.rating), !isFavourite.value)
        }
    }

    fun playable(m: MovieInfo) = Playable(ContentType.MOVIE, m.id, m.name, m.poster, m.extension)
}

@Composable
fun MovieDetailScreen(id: Long, navigator: AppNavigator) {
    val vm = appViewModel(key = "movie_$id") { MovieDetailViewModel(id, it) }
    when (val state = vm.info) {
        ListState.Loading -> SkeletonDetail()
        is ListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorMessage(state.message, vm::load) }
        is ListState.Ready -> MovieDetailContent(state.value, vm, navigator)
    }
}

@Composable
private fun MovieDetailContent(movie: MovieInfo, vm: MovieDetailViewModel, navigator: AppNavigator) {
    val favourite by vm.isFavourite.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(movie.id) { playFocus.tryRequestFocus() }
    val resumable = progress?.takeIf { it.isResumable }

    val d = vm.extras?.details
    val trailer = movie.trailer?.takeIf { youTubeId(it) != null } ?: trailerUrl(d?.trailerKey)
    // Provider bitrates under ~1.2 Mbps are almost always screener/cam copies.
    val lowQuality = movie.bitrateKbps?.let { it in 1..1200 } == true

    Box(Modifier.fillMaxSize()) {
        Backdrop(d?.backdrop ?: movie.backdrop ?: movie.poster)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(44.dp)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                item(key = "head") {
                    Column(
                        Modifier.padding(start = 48.dp, top = 36.dp, bottom = 12.dp).width(620.dp).heightIn(min = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(listOfNotNull("MOVIE", d?.collectionName?.uppercase()).joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = AppColors.Accent)
                        DetailTitle(movie.name, d?.logo)
                        d?.tagline?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = AppColors.OnBackground.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            (d?.certification ?: movie.ageRating)?.let { CertBadge(it) }
                            MetaRow(
                                listOfNotNull(
                                    movie.releaseDate?.take(4),
                                    movie.duration ?: d?.runtime?.let { formatRuntime(it) },
                                    formatRating(movie.rating),
                                    movie.genre,
                                ),
                            )
                            if (lowQuality) CardBadge("LOW-QUALITY COPY", color = AppColors.Rating.copy(alpha = 0.9f))
                        }
                        movie.plot?.let {
                            Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, color = AppColors.OnBackground.copy(alpha = 0.9f))
                        }
                        val credits = listOfNotNull((d?.director ?: movie.director)?.let { "Director: $it" }, movie.country).joinToString("   ·   ")
                        if (credits.isNotEmpty()) Text(credits, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (d == null) movie.cast?.let { Text("Cast: $it", style = MaterialTheme.typography.bodySmall, color = AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (resumable != null) {
                            Column(Modifier.width(280.dp).padding(top = 4.dp)) {
                                ProgressLine(resumable.fraction)
                                Text(
                                    "${formatDuration(resumable.durationMs - resumable.positionMs)} left",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.Muted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                            ActionButton(
                                text = if (resumable != null) "Resume" else "Play",
                                icon = ActionIcon.Play,
                                primary = true,
                                modifier = Modifier.focusRequester(playFocus),
                                onClick = { navigator.play(PlaybackRequest(listOf(vm.playable(movie)), 0, resumable?.positionMs ?: 0L)) },
                            )
                            if (resumable != null) {
                                ActionButton("From start", ActionIcon.Restart, onClick = { navigator.play(PlaybackRequest(listOf(vm.playable(movie)), 0, 0L)) })
                            }
                            if (trailer != null) {
                                ActionButton("Trailer", ActionIcon.Trailer, onClick = { openTrailer(context, trailer) })
                            }
                            ActionButton(
                                text = if (favourite) "On My List" else "My List",
                                icon = if (favourite) ActionIcon.Favourite else ActionIcon.NotFavourite,
                                onClick = { vm.toggleFavourite(movie) },
                            )
                        }
                    }
                }
                extrasRows(vm.extras, navigator, vm.similar)
            }
        }
    }
}

internal fun formatRuntime(min: Int) = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
