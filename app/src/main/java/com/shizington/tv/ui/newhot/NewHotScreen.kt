package com.shizington.tv.ui.newhot

import com.shizington.tv.ui.components.SkeletonRows
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shizington.tv.AppContainer
import com.shizington.tv.data.db.WatchlistEntity
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.Stream
import com.shizington.tv.data.reco.FeedItem
import com.shizington.tv.data.reco.NewHotFeed
import com.shizington.tv.data.tmdb.TmdbTitle
import com.shizington.tv.ui.browse.ListState
import com.shizington.tv.ui.components.CardBadge
import com.shizington.tv.ui.components.ErrorMessage
import com.shizington.tv.ui.components.FocusHero
import com.shizington.tv.ui.components.HeroBanner
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.PosterCard
import com.shizington.tv.ui.components.RemoteImage
import com.shizington.tv.ui.components.RowSection
import com.shizington.tv.ui.components.RowStartPadding
import com.shizington.tv.ui.components.Top10Card
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NewHotViewModel(private val c: AppContainer) : ViewModel() {
    var feed by mutableStateOf<ListState<NewHotFeed>>(ListState.Loading)
        private set
    val hero = FocusHero(c, viewModelScope)
    val watchlistKeys = c.library.watchlist.map { list -> list.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private var loadedAt = 0L

    init { load() }

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load(force: Boolean = false) {
        if (!force && feed is ListState.Ready && System.currentTimeMillis() - loadedAt < 30 * 60_000L) return
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            feed = try {
                ListState.Ready(c.recommendations.newAndHot()).also { loadedAt = System.currentTimeMillis() }
            } catch (e: Exception) {
                ListState.Failed(e.message ?: "Couldn't load what's new")
            }
            (feed as? ListState.Ready)?.value?.outNow?.firstOrNull()?.let { hero.show(it.stream, it.reason, it.tmdbId, "Out now") }
        }
    }

    fun toggleWatchlist(t: TmdbTitle) {
        val key = "${if (t.type == ContentType.SERIES) "tv" else "movie"}:${t.id}"
        viewModelScope.launch {
            if (key in watchlistKeys.value) c.library.removeFromWatchlist(key)
            else c.library.addToWatchlist(
                WatchlistEntity(key, t.id, if (t.type == ContentType.SERIES) "tv" else "movie", t.title, t.year, t.poster, t.releaseDate, System.currentTimeMillis()),
            )
        }
    }
}

@Composable
fun NewHotScreen(navigator: AppNavigator) {
    val vm = appViewModel(key = "newhot") { NewHotViewModel(it) }
    val onList by vm.watchlistKeys.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    val open: (Stream) -> Unit = { s ->
        when (s.type) {
            ContentType.SERIES -> navigator.openSeries(s.id)
            else -> navigator.openMovie(s.id)
        }
    }

    Column(Modifier.fillMaxSize()) {
        HeroBanner(vm.hero.hero, Modifier.fillMaxWidth().height(268.dp), logoHeight = 64.dp)
        when (val state = vm.feed) {
            ListState.Loading -> SkeletonRows(rows = 3, message = "Checking what's new in the UK…")
            is ListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorMessage(state.message, onRetry = { vm.load(true) }) }
            is ListState.Ready -> CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(44.dp)) {
                val f = state.value
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                    if (f.outNow.isNotEmpty()) item(key = "out") { ItemRow("Out now in the UK  ·  Just released on digital", f.outNow, vm, open) }
                    if (f.top10.size >= 5) item(key = "top10") {
                        RowSection("Top 10 in the UK today") {
                            items(f.top10, key = { it.stream.key }) { item ->
                                Top10Card(item.rank ?: 0, item.stream, onClick = { open(item.stream) }, onFocused = { vm.hero.show(item.stream, item.reason, item.tmdbId, "Top 10") })
                            }
                        }
                    }
                    if (f.newEpisodes.isNotEmpty()) item(key = "eps") { ItemRow("New episodes of your shows", f.newEpisodes, vm, open) }
                    if (f.trendingSeries.isNotEmpty()) item(key = "tv") { ItemRow("Trending series this week", f.trendingSeries, vm, open) }
                    if (f.comingSoon.isNotEmpty()) item(key = "soon") {
                        RowSection("Coming soon  ·  Add to My List and it appears on Home when it lands") {
                            items(f.comingSoon, key = { it.id }) { t ->
                                val key = "movie:${t.id}"
                                ComingSoonCard(t, key in onList, onClick = { vm.toggleWatchlist(t) }, onFocused = {
                                    vm.hero.show(t, "Coming soon", if (key in onList) "On your list · press OK to remove" else "Press OK to add to My List")
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(title: String, items: List<FeedItem>, vm: NewHotViewModel, open: (Stream) -> Unit) {
    RowSection(title) {
        items(items, key = { it.stream.key }) { item ->
            PosterCard(item.stream, onClick = { open(item.stream) }, badge = item.badge, onFocused = { vm.hero.show(item.stream, item.reason, item.tmdbId) })
        }
    }
}

@Composable
internal fun ComingSoonCard(t: TmdbTitle, onList: Boolean, onClick: () -> Unit, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(108.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Focus), shape = RoundedCornerShape(8.dp))),
            colors = CardDefaults.colors(containerColor = AppColors.Surface),
        ) {
            Box(Modifier.fillMaxSize()) {
                RemoteImage(t.poster, t.title, Modifier.fillMaxSize())
                CardBadge(
                    if (onList) "✓ ON MY LIST" else (t.releaseDate?.let { comingLabel(it) } ?: "SOON"),
                    Modifier.align(Alignment.TopStart).padding(6.dp),
                    color = if (onList) androidx.compose.ui.graphics.Color(0xFF1F8A4C) else AppColors.Accent,
                )
            }
        }
        Text(t.title, style = MaterialTheme.typography.labelMedium, color = if (focused) AppColors.OnBackground else AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun comingLabel(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM")).uppercase()
}.getOrDefault("SOON")
