package com.shizington.tv.ui.favourites

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.shizington.tv.ui.components.RowContent
import com.shizington.tv.ui.components.StreamRow
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.main.TopBarHeight
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class FavouritesViewModel(c: AppContainer) : ViewModel() {
    val favourites = c.library.favourites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    var restoreKey by mutableStateOf<String?>(null)
        private set
    private var lastFocus: String? = null
    private var pendingRestore: String? = null

    fun onFocused(rowKey: String, s: Stream) { lastFocus = "$rowKey/${s.key}" }
    fun rememberFocus() { pendingRestore = lastFocus }
    fun beginRestore() { restoreKey = pendingRestore; pendingRestore = null }
    fun focusRestored() { restoreKey = null }
}

@Composable
fun FavouritesScreen(navigator: AppNavigator) {
    val vm = appViewModel { FavouritesViewModel(it) }
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.beginRestore()
        delay(1500)
        vm.focusRestored()
    }
    val list = favourites ?: return

    if (list.isEmpty()) {
        Column(Modifier.padding(start = 48.dp, top = TopBarHeight + 32.dp)) {
            Text("No favourites yet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Add movies and series from their detail page, or hold OK on a live channel.",
                color = AppColors.Muted,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    val groups = listOf(
        Triple("fav_live", "Live channels", list.filter { it.type == ContentType.LIVE }),
        Triple("fav_movies", "Movies", list.filter { it.type == ContentType.MOVIE }),
        Triple("fav_series", "Series", list.filter { it.type == ContentType.SERIES }),
    )
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(34.dp)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = TopBarHeight + 16.dp, bottom = 48.dp)) {
            groups.forEach { (key, title, items) ->
                if (items.isNotEmpty()) item(key = key) {
                    StreamRow(
                        rowKey = key,
                        title = title,
                        content = RowContent.Ready(items),
                        onLoad = {},
                        onFocused = { vm.onFocused(key, it) },
                        onClick = { s ->
                            vm.rememberFocus()
                            when (s.type) {
                                ContentType.MOVIE -> navigator.openMovie(s.id)
                                ContentType.SERIES -> navigator.openSeries(s.id)
                                else -> navigator.play(PlaybackRequest(items.map { it.toPlayable() }, items.indexOf(s)))
                            }
                        },
                        restoreKey = vm.restoreKey,
                        onRestored = vm::focusRestored,
                    )
                }
            }
        }
    }
}
