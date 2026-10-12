package com.shizington.tv.ui.browse

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shizington.tv.AppContainer
import com.shizington.tv.data.db.ProgressEntity
import com.shizington.tv.data.model.Category
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.PlaybackRequest
import com.shizington.tv.data.model.Stream
import com.shizington.tv.data.model.formatRating
import com.shizington.tv.data.model.toPlayable
import com.shizington.tv.data.toPlayable
import com.shizington.tv.ui.components.ErrorMessage
import com.shizington.tv.ui.components.HeroBanner
import com.shizington.tv.ui.components.HeroData
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.ProgressCard
import com.shizington.tv.ui.components.RowContent
import com.shizington.tv.ui.components.RowSection
import com.shizington.tv.ui.components.StreamRow
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.components.tryRequestFocus
import com.shizington.tv.ui.navigation.AppNavigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BrowseMode { HOME, MOVIES, SERIES }

data class RowSpec(val key: String, val title: String, val type: ContentType, val categoryId: String)

sealed interface ListState<out T> {
    data object Loading : ListState<Nothing>
    data class Ready<T>(val value: T) : ListState<T>
    data class Failed(val message: String) : ListState<Nothing>
}

class BrowseViewModel(private val mode: BrowseMode, private val c: AppContainer) : ViewModel() {
    var rows by mutableStateOf<ListState<List<RowSpec>>>(ListState.Loading)
        private set
    val rowContent = mutableStateMapOf<String, RowContent>()
    var hero by mutableStateOf<HeroData?>(null)
        private set
    var restoreKey by mutableStateOf<String?>(null)
        private set
    private var lastFocusKey: String? = null
    private var heroJob: Job? = null

    val continueWatching = c.library.continueWatching
        .map { list -> if (mode == BrowseMode.SERIES) list.filter { it.seriesId != null } else if (mode == BrowseMode.MOVIES) list.filter { it.seriesId == null } else list }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favourites = c.library.favourites
        .map { list ->
            when (mode) {
                BrowseMode.HOME -> list.filter { it.type != ContentType.LIVE }
                BrowseMode.MOVIES -> list.filter { it.type == ContentType.MOVIE }
                BrowseMode.SERIES -> list.filter { it.type == ContentType.SERIES }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { loadRows() }

    fun loadRows() {
        rows = ListState.Loading
        viewModelScope.launch {
            rows = try {
                val movies = if (mode != BrowseMode.SERIES) async { runCatching { c.xtream.categories(ContentType.MOVIE) } } else null
                val series = if (mode != BrowseMode.MOVIES) async { runCatching { c.xtream.categories(ContentType.SERIES) } } else null
                val m = movies?.await()
                val s = series?.await()
                val failure = listOfNotNull(m, s).firstOrNull { it.isFailure }?.exceptionOrNull()
                val movieRows = m?.getOrNull().orEmpty().map { it.toRow(ContentType.MOVIE, "Movies") }
                val seriesRows = s?.getOrNull().orEmpty().map { it.toRow(ContentType.SERIES, "Series") }
                if (movieRows.isEmpty() && seriesRows.isEmpty() && failure != null) throw failure
                ListState.Ready(if (mode == BrowseMode.HOME) interleave(movieRows, seriesRows) else movieRows + seriesRows)
            } catch (e: Exception) {
                ListState.Failed(e.message ?: "Couldn't load the catalogue")
            }
        }
    }

    private fun Category.toRow(type: ContentType, prefix: String) = RowSpec(
        key = "${type.name}:$id",
        title = if (mode == BrowseMode.HOME) "$prefix · $name" else name,
        type = type,
        categoryId = id,
    )

    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        val out = ArrayList<T>(a.size + b.size)
        for (i in 0 until maxOf(a.size, b.size)) {
            if (i < a.size) out += a[i]
            if (i < b.size) out += b[i]
        }
        return out
    }

    fun loadRow(spec: RowSpec) {
        val current = rowContent[spec.key]
        if (current is RowContent.Ready || current == RowContent.Loading) return
        rowContent[spec.key] = RowContent.Loading
        viewModelScope.launch {
            rowContent[spec.key] = try {
                val items = c.xtream.streams(spec.type, spec.categoryId)
                if (hero == null) items.firstOrNull()?.let { showHero(it) }
                RowContent.Ready(items)
            } catch (e: Exception) {
                RowContent.Failed(e.message ?: "Couldn't load")
            }
        }
    }

    fun onFocused(rowKey: String, stream: Stream) {
        lastFocusKey = "$rowKey/${stream.key}"
        showHero(stream)
    }

    fun onProgressFocused(p: ProgressEntity) {
        lastFocusKey = "continue/${p.type}:${p.contentId}"
        heroJob?.cancel()
        if (p.seriesId != null) {
            hero = HeroData(lastFocusKey!!, "Continue watching", p.seriesName ?: p.name, p.seriesCover ?: p.icon, listOf("S${p.season}E${p.episode}"), p.name)
        } else {
            showHero(Stream(ContentType.MOVIE, p.contentId, p.name, p.icon, null, p.extension), label = "Continue watching")
        }
    }

    private fun showHero(stream: Stream, label: String? = null) {
        heroJob?.cancel()
        hero = when (stream.type) {
            ContentType.SERIES -> HeroData(
                stream.key, label ?: "Series", stream.name, stream.backdrop ?: stream.icon,
                listOfNotNull(stream.year, formatRating(stream.rating), stream.genre), stream.plot,
            )
            ContentType.LIVE -> HeroData(stream.key, label ?: "Live", stream.name, stream.icon)
            else -> {
                val cached = c.xtream.cachedMovieInfo(stream.id)
                HeroData(
                    stream.key, label ?: "Movie", stream.name,
                    cached?.backdrop ?: cached?.poster ?: stream.icon,
                    listOfNotNull(cached?.releaseDate?.take(4), cached?.duration, formatRating(cached?.rating ?: stream.rating)),
                    cached?.plot,
                )
            }
        }
        if (stream.type == ContentType.MOVIE && c.xtream.cachedMovieInfo(stream.id) == null) {
            // Debounced so scrolling quickly through a row doesn't fire a request per card.
            heroJob = viewModelScope.launch {
                delay(650)
                val info = runCatching { c.xtream.movieInfo(stream.id) }.getOrNull() ?: return@launch
                if (hero?.key == stream.key) {
                    hero = hero?.copy(
                        image = info.backdrop ?: info.poster ?: stream.icon,
                        meta = listOfNotNull(info.releaseDate?.take(4), info.duration, formatRating(info.rating ?: stream.rating), info.genre?.substringBefore(",")),
                        plot = info.plot,
                    )
                }
            }
        }
    }

    private var pendingRestore: String? = null

    /** Remember what was focused so it can be restored when the user comes back. */
    fun rememberFocus() { pendingRestore = lastFocusKey }
    fun beginRestore() { restoreKey = pendingRestore; pendingRestore = null }
    fun focusRestored() { restoreKey = null }
}

@Composable
fun BrowseScreen(mode: BrowseMode, navigator: AppNavigator) {
    val vm = appViewModel(key = "browse_${mode.name}") { BrowseViewModel(mode, it) }
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        vm.beginRestore()
        delay(1500)
        vm.focusRestored()
    }

    val open: (String, Stream) -> Unit = { _, stream ->
        vm.rememberFocus()
        when (stream.type) {
            ContentType.MOVIE -> navigator.openMovie(stream.id)
            ContentType.SERIES -> navigator.openSeries(stream.id)
            else -> navigator.play(PlaybackRequest(listOf(stream.toPlayable()), 0))
        }
    }

    Column(Modifier.fillMaxSize()) {
        HeroBanner(vm.hero, Modifier.fillMaxWidth().height(268.dp))
        when (val rows = vm.rows) {
            ListState.Loading -> Loader(Modifier.fillMaxSize())
            is ListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorMessage(rows.message, onRetry = vm::loadRows)
            }
            is ListState.Ready -> CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(34.dp)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                    // These slots always exist (1dp when empty, so the list anchors on them) so that local data
                    // arriving after the catalogue grows in place instead of landing above the viewport.
                    item(key = "continue") {
                        Box(Modifier.heightIn(min = 1.dp)) {
                            if (continueWatching.isNotEmpty()) {
                                ContinueWatchingRow(continueWatching, vm.restoreKey, vm::focusRestored, vm::onProgressFocused) { p ->
                                    vm.rememberFocus()
                                    navigator.play(PlaybackRequest(listOf(p.toPlayable()), 0, p.positionMs))
                                }
                            }
                        }
                    }
                    item(key = "favourites") {
                        Box(Modifier.heightIn(min = 1.dp)) {
                            if (favourites.isNotEmpty()) {
                                StreamRow(
                                    rowKey = "favourites",
                                    title = "My favourites",
                                    content = RowContent.Ready(favourites),
                                    onLoad = {},
                                    onFocused = { vm.onFocused("favourites", it) },
                                    onClick = { open("favourites", it) },
                                    restoreKey = vm.restoreKey,
                                    onRestored = vm::focusRestored,
                                )
                            }
                        }
                    }
                    items(rows.value, key = { it.key }) { spec ->
                        StreamRow(
                            rowKey = spec.key,
                            title = spec.title,
                            content = vm.rowContent[spec.key],
                            onLoad = { vm.loadRow(spec) },
                            onFocused = { vm.onFocused(spec.key, it) },
                            onClick = { open(spec.key, it) },
                            restoreKey = vm.restoreKey,
                            onRestored = vm::focusRestored,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<ProgressEntity>,
    restoreKey: String?,
    onRestored: () -> Unit,
    onFocused: (ProgressEntity) -> Unit,
    onClick: (ProgressEntity) -> Unit,
) {
    RowSection("Continue watching") {
        items(items, key = { "${it.type}:${it.contentId}" }) { p ->
            val key = "continue/${p.type}:${p.contentId}"
            val requester = remember { FocusRequester() }
            if (restoreKey == key) LaunchedEffect(Unit) { requester.tryRequestFocus(); onRestored() }
            ProgressCard(
                title = if (p.seriesName != null) "${p.seriesName} · S${p.season}E${p.episode}" else p.name,
                image = p.icon ?: p.seriesCover,
                progress = p.fraction,
                onClick = { onClick(p) },
                modifier = Modifier.focusRequester(requester),
                onFocused = { onFocused(p) },
            )
        }
    }
}
