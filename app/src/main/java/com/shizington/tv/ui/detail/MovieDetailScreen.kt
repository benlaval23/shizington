package com.shizington.tv.ui.detail

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
import com.shizington.tv.ui.components.ProgressLine
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

    init { load() }

    fun load() {
        info = ListState.Loading
        viewModelScope.launch {
            info = try {
                ListState.Ready(c.xtream.movieInfo(id))
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
        ListState.Loading -> Loader(Modifier.fillMaxSize())
        is ListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorMessage(state.message, vm::load) }
        is ListState.Ready -> MovieDetailContent(state.value, vm, navigator)
    }
}

@Composable
private fun MovieDetailContent(movie: MovieInfo, vm: MovieDetailViewModel, navigator: AppNavigator) {
    val favourite by vm.isFavourite.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { playFocus.tryRequestFocus() }
    val resumable = progress?.takeIf { it.isResumable }

    Box(Modifier.fillMaxSize()) {
        Backdrop(movie.backdrop ?: movie.poster)
        Column(Modifier.padding(start = 48.dp, top = 56.dp).width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MOVIE", style = MaterialTheme.typography.labelMedium, color = AppColors.Accent)
            Text(movie.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetaRow(listOfNotNull(movie.releaseDate?.take(4), movie.duration, formatRating(movie.rating), movie.genre))
            movie.plot?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis, color = AppColors.OnBackground.copy(alpha = 0.9f))
            }
            movie.director?.let { Text("Director: $it", style = MaterialTheme.typography.bodySmall, color = AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            movie.cast?.let { Text("Cast: $it", style = MaterialTheme.typography.bodySmall, color = AppColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(8.dp))
            if (resumable != null) {
                Column(Modifier.width(280.dp)) {
                    ProgressLine(resumable.fraction)
                    Text(
                        "${formatDuration(resumable.durationMs - resumable.positionMs)} left",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
                ActionButton(
                    text = if (favourite) "In favourites" else "Add to favourites",
                    icon = if (favourite) ActionIcon.Favourite else ActionIcon.NotFavourite,
                    onClick = { vm.toggleFavourite(movie) },
                )
            }
        }
    }
}
