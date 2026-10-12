package com.shizington.tv.ui.detail

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shizington.tv.AppContainer
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.reco.FeedItem
import com.shizington.tv.data.tmdb.TmdbPersonDetails
import com.shizington.tv.ui.browse.ListState
import com.shizington.tv.ui.components.ErrorMessage
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.PosterCard
import com.shizington.tv.ui.components.RemoteImage
import com.shizington.tv.ui.components.RowSection
import com.shizington.tv.ui.components.RowStartPadding
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.launch

data class PersonPage(val person: TmdbPersonDetails, val movies: List<FeedItem>, val series: List<FeedItem>, val directed: List<FeedItem>)

class PersonViewModel(private val id: Long, private val c: AppContainer) : ViewModel() {
    var page by mutableStateOf<ListState<PersonPage>>(ListState.Loading)
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            page = try {
                val p = c.tmdb.person(id) ?: error("Couldn't find this person")
                // Talk shows, news and reality guest spots aren't what anyone means by "their work".
                val credits = p.credits.filterNot { it.type == ContentType.SERIES && it.genreIds.any { g -> g in setOf(10763, 10764, 10767) } }
                val available = c.recommendations.onProvider(credits)
                val directedIds = p.directedIds
                ListState.Ready(
                    PersonPage(
                        p,
                        movies = available.filter { it.stream.type == ContentType.MOVIE && it.tmdbId !in directedIds },
                        series = available.filter { it.stream.type == ContentType.SERIES },
                        directed = available.filter { it.tmdbId in directedIds },
                    ),
                )
            } catch (e: Exception) {
                ListState.Failed(e.message ?: "Couldn't load this person")
            }
        }
    }
}

@Composable
fun PersonScreen(id: Long, navigator: AppNavigator) {
    val vm = appViewModel(key = "person_$id") { PersonViewModel(id, it) }
    when (val state = vm.page) {
        ListState.Loading -> Loader(Modifier.fillMaxSize())
        is ListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorMessage(state.message, vm::load) }
        is ListState.Ready -> PersonContent(state.value, navigator)
    }
}

@Composable
private fun PersonContent(page: PersonPage, navigator: AppNavigator) {
    val p = page.person
    val open: (FeedItem) -> Unit = { f -> if (f.stream.type == ContentType.SERIES) navigator.openSeries(f.stream.id) else navigator.openMovie(f.stream.id) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(44.dp)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 40.dp, bottom = 48.dp)) {
            item(key = "head") {
                Row(Modifier.padding(start = RowStartPadding, end = 48.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    RemoteImage(p.photo, p.name, Modifier.size(width = 150.dp, height = 225.dp).clip(RoundedCornerShape(10.dp)))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(640.dp)) {
                        p.knownFor?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = AppColors.Accent) }
                        Text(p.name, style = MaterialTheme.typography.displaySmall)
                        val count = page.movies.size + page.series.size + page.directed.size
                        Text(
                            if (count == 0) "Nothing of theirs on ALICE TV right now" else "$count titles on ALICE TV",
                            style = MaterialTheme.typography.titleMedium, color = AppColors.OnBackground.copy(alpha = 0.85f),
                        )
                        p.biography?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.Muted, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            listOf("Directed" to page.directed, "Films" to page.movies, "Series" to page.series).forEach { (title, list) ->
                if (list.isNotEmpty()) item(key = title) {
                    RowSection(title) {
                        items(list, key = { it.stream.key }) { f -> PosterCard(f.stream, onClick = { open(f) }) }
                    }
                }
            }
        }
    }
}
