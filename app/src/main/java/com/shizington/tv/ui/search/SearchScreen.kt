package com.shizington.tv.ui.search

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shizington.tv.AppContainer
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.PlaybackRequest
import com.shizington.tv.data.model.Stream
import com.shizington.tv.data.model.toPlayable
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.RowContent
import com.shizington.tv.ui.components.StreamRow
import com.shizington.tv.ui.components.TvTextField
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.main.TopBarHeight
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class SearchResults(val live: List<Stream>, val movies: List<Stream>, val series: List<Stream>) {
    val isEmpty get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@OptIn(FlowPreview::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    var loading by mutableStateOf(false)
        private set
    var results by mutableStateOf<SearchResults?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var restoreKey by mutableStateOf<String?>(null)
        private set
    private var lastFocus: String? = null
    private var pendingRestore: String? = null

    init {
        viewModelScope.launch {
            query.debounce(400).distinctUntilChanged().collectLatest { q ->
                if (q.trim().length < 2) {
                    results = null; loading = false; return@collectLatest
                }
                loading = true
                error = null
                try {
                    val live = async { runCatching { c.xtream.search(q, ContentType.LIVE) }.getOrDefault(emptyList()) }
                    val movies = async { runCatching { c.xtream.search(q, ContentType.MOVIE) }.getOrDefault(emptyList()) }
                    val series = async { runCatching { c.xtream.search(q, ContentType.SERIES) }.getOrDefault(emptyList()) }
                    results = SearchResults(live.await(), movies.await(), series.await())
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    loading = false
                }
            }
        }
    }

    fun onFocused(rowKey: String, s: Stream) { lastFocus = "$rowKey/${s.key}" }
    fun rememberFocus() { pendingRestore = lastFocus }
    fun beginRestore() { restoreKey = pendingRestore; pendingRestore = null }
    fun focusRestored() { restoreKey = null }
}

@Composable
fun SearchScreen(navigator: AppNavigator) {
    val vm = appViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.beginRestore()
        delay(1500)
        vm.focusRestored()
    }

    val open: (Stream, List<Stream>) -> Unit = { s, siblings ->
        vm.rememberFocus()
        when (s.type) {
            ContentType.MOVIE -> navigator.openMovie(s.id)
            ContentType.SERIES -> navigator.openSeries(s.id)
            else -> navigator.play(PlaybackRequest(siblings.map { it.toPlayable() }, siblings.indexOf(s).coerceAtLeast(0)))
        }
    }

    Column(Modifier.fillMaxSize().padding(top = TopBarHeight + 16.dp)) {
        TvTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            placeholder = "Search channels, movies and series",
            modifier = Modifier.padding(start = 48.dp).width(520.dp),
            imeAction = ImeAction.Search,
        )
        val results = vm.results
        when {
            vm.loading && results == null -> Column(Modifier.padding(48.dp)) {
                Loader(Modifier.fillMaxWidth())
                Text("Searching the catalogue… the first search downloads the full list.", color = AppColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            results == null -> Text(
                "Type at least two characters.",
                color = AppColors.Muted,
                modifier = Modifier.padding(start = 48.dp, top = 24.dp),
            )
            results.isEmpty -> Text("No results for \"$query\".", color = AppColors.Muted, modifier = Modifier.padding(start = 48.dp, top = 24.dp))
            else -> CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(34.dp)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = 48.dp)) {
                    listOf(
                        Triple("search_live", "Live channels", results.live),
                        Triple("search_movies", "Movies", results.movies),
                        Triple("search_series", "Series", results.series),
                    ).forEach { (key, title, items) ->
                        if (items.isNotEmpty()) item(key = key) {
                            StreamRow(
                                rowKey = key,
                                title = "$title (${items.size})",
                                content = RowContent.Ready(items),
                                onLoad = {},
                                onFocused = { vm.onFocused(key, it) },
                                onClick = { open(it, items) },
                                restoreKey = vm.restoreKey,
                                onRestored = vm::focusRestored,
                            )
                        }
                    }
                }
            }
        }
    }
}
