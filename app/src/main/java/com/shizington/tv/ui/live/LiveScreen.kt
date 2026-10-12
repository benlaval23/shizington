package com.shizington.tv.ui.live

import com.shizington.tv.ui.components.SkeletonList
import android.view.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.shizington.tv.data.model.ChannelGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.shizington.tv.AppContainer
import com.shizington.tv.data.model.Category
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.EpgEntry
import com.shizington.tv.data.model.PlaybackRequest
import com.shizington.tv.data.model.Stream
import com.shizington.tv.data.model.toPlayable
import com.shizington.tv.ui.browse.ListState
import com.shizington.tv.ui.components.ErrorMessage
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.ProgressLine
import com.shizington.tv.ui.components.RemoteImage
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.formatClock
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.components.tryRequestFocus
import com.shizington.tv.ui.main.TopBarHeight
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val FAVOURITES_ID = "__favourites"
private const val ALL_ID = "__all"

class LiveViewModel(private val c: AppContainer) : ViewModel() {
    var categories by mutableStateOf<ListState<List<Category>>>(ListState.Loading)
        private set
    var selectedCategory by mutableStateOf<String?>(null)
        private set
    var channels by mutableStateOf<ListState<List<ChannelGroup>>>(ListState.Loading)
        private set
    val epg = mutableStateMapOf<Long, List<EpgEntry>>()
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set
    var restoreChannelId by mutableStateOf<Long?>(null)
        private set

    private val favouriteChannels = c.library.favourites.map { list -> list.filter { it.type == ContentType.LIVE } }
    val favouriteIds = favouriteChannels.map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var selectJob: Job? = null
    private var channelsJob: Job? = null
    private var pendingRestore = false

    init {
        loadCategories()
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                now = System.currentTimeMillis()
            }
        }
    }

    fun loadCategories() {
        categories = ListState.Loading
        viewModelScope.launch {
            categories = try {
                val real = c.xtream.categories(ContentType.LIVE)
                val list = listOf(Category(FAVOURITES_ID, "★  Favourites"), Category(ALL_ID, "All channels")) + real
                if (selectedCategory == null) {
                    val hasFavourites = favouriteChannels.first().isNotEmpty()
                    showCategory(if (hasFavourites) FAVOURITES_ID else real.firstOrNull()?.id ?: ALL_ID)
                }
                ListState.Ready(list)
            } catch (e: Exception) {
                ListState.Failed(e.message ?: "Couldn't load channels")
            }
        }
    }

    /** Called as focus moves over categories; debounced so fast scrolling stays cheap. */
    fun onCategoryFocused(id: String) {
        if (id == selectedCategory) return
        selectJob?.cancel()
        selectJob = viewModelScope.launch {
            delay(250)
            showCategory(id)
        }
    }

    fun showCategory(id: String) {
        selectedCategory = id
        channelsJob?.cancel()
        channels = ListState.Loading
        channelsJob = viewModelScope.launch {
            if (id == FAVOURITES_ID) {
                favouriteChannels.collect { channels = ListState.Ready(ChannelGroup.group(it)) }
                return@launch
            }
            channels = try {
                val streams = if (id == ALL_ID) c.xtream.allStreams(ContentType.LIVE) else c.xtream.streams(ContentType.LIVE, id)
                ListState.Ready(withContext(Dispatchers.Default) { ChannelGroup.group(streams) })
            } catch (e: Exception) {
                ListState.Failed(e.message ?: "Couldn't load channels")
            }
        }
    }

    fun requestEpg(group: ChannelGroup) = requestEpg(group.epgStream)

    private fun requestEpg(stream: Stream) {
        val current = epg[stream.id]
        if (current != null && (current.firstOrNull()?.endMs ?: Long.MAX_VALUE) > System.currentTimeMillis()) return
        c.xtream.cachedEpg(stream.id)?.let { epg[stream.id] = it }
        viewModelScope.launch { epg[stream.id] = c.xtream.shortEpg(stream.id) }
    }

    fun isFavourite(group: ChannelGroup, ids: Set<Long>) = group.variants.any { it.id in ids }

    /** Favouriting a channel stores the variant you'd play; unfavouriting clears every variant. */
    fun toggleFavourite(group: ChannelGroup) {
        viewModelScope.launch {
            val existing = group.variants.filter { it.id in favouriteIds.value }
            if (existing.isEmpty()) c.library.setFavourite(group.preferred(c.credentialsStore.preferredQuality), true)
            else existing.forEach { c.library.setFavourite(it, false) }
        }
    }

    fun play(channels: List<ChannelGroup>, index: Int, navigator: AppNavigator) {
        pendingRestore = true
        val quality = c.credentialsStore.preferredQuality
        val queue = channels.map { it.toPlayable(quality) }
        c.lastPlayedLiveId = queue[index].id
        navigator.play(PlaybackRequest(queue, index))
    }

    fun openCatchUp(group: ChannelGroup, navigator: AppNavigator) {
        if (group.archiveDays <= 0) return
        pendingRestore = true
        val stream = group.variants.firstOrNull { it.archiveDays > 0 && it.epgChannelId != null }
            ?: group.variants.first { it.archiveDays > 0 }
        c.lastPlayedLiveId = stream.id
        navigator.openCatchUp(stream.id, group.name, group.icon)
    }

    fun beginRestore() {
        if (pendingRestore) restoreChannelId = c.lastPlayedLiveId
        pendingRestore = false
    }

    fun focusRestored() { restoreChannelId = null }
}

@Composable
fun LiveScreen(navigator: AppNavigator) {
    val vm = appViewModel { LiveViewModel(it) }
    val favouriteIds by vm.favouriteIds.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.beginRestore()
        delay(1500)
        vm.focusRestored()
    }

    Row(Modifier.fillMaxSize().padding(top = TopBarHeight + 8.dp)) {
        when (val cats = vm.categories) {
            ListState.Loading -> SkeletonList(Modifier.padding(start = 48.dp, top = 16.dp))
            is ListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorMessage(cats.message, onRetry = vm::loadCategories)
            }
            is ListState.Ready -> {
                CategoryList(cats.value, vm.selectedCategory, vm::onCategoryFocused, vm::showCategory)
                Column(Modifier.weight(1f).fillMaxHeight().padding(end = 32.dp)) {
                    GridHeader()
                    when (val ch = vm.channels) {
                        ListState.Loading -> SkeletonList(Modifier.padding(top = 8.dp))
                        is ListState.Failed -> ErrorMessage(ch.message, onRetry = { vm.selectedCategory?.let(vm::showCategory) })
                        is ListState.Ready -> if (ch.value.isEmpty()) {
                            Text(
                                if (vm.selectedCategory == FAVOURITES_ID) "No favourite channels yet. Hold OK on a channel to add it." else "No channels in this category.",
                                color = AppColors.Muted,
                                modifier = Modifier.padding(24.dp),
                            )
                        } else {
                            ChannelGrid(ch.value, vm, favouriteIds, navigator)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryList(categories: List<Category>, selected: String?, onFocused: (String) -> Unit, onClick: (String) -> Unit) {
    val state = rememberLazyListState()
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(140.dp)) {
        LazyColumn(
            state = state,
            modifier = Modifier.width(250.dp).fillMaxHeight().focusRestorer(),
            contentPadding = PaddingValues(start = 32.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(categories, key = { _, c -> c.id }) { _, cat ->
                val isSelected = cat.id == selected
                Surface(
                    onClick = { onClick(cat.id) },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused(cat.id) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) AppColors.SurfaceHigh else Color.Transparent,
                        contentColor = if (isSelected) AppColors.OnBackground else AppColors.Muted,
                        focusedContainerColor = AppColors.OnBackground,
                        focusedContentColor = Color.Black,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                ) {
                    Text(
                        cat.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GridHeader() {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp, top = 4.dp)) {
        Text(
            "Hold OK to favourite  ·  Press ≡ Menu for catch-up",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.Muted,
            modifier = Modifier.align(Alignment.End).padding(bottom = 4.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text("CHANNEL", style = MaterialTheme.typography.labelSmall, color = AppColors.Muted, modifier = Modifier.width(300.dp))
            Text("NOW", style = MaterialTheme.typography.labelSmall, color = AppColors.Muted, modifier = Modifier.weight(1.2f))
            Text("NEXT", style = MaterialTheme.typography.labelSmall, color = AppColors.Muted, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChannelGrid(channels: List<ChannelGroup>, vm: LiveViewModel, favouriteIds: Set<Long>, navigator: AppNavigator) {
    val state = rememberLazyListState()
    val restoreId = vm.restoreChannelId
    LaunchedEffect(restoreId, channels) {
        val idx = channels.indexOfFirst { restoreId != null && restoreId in it.ids }
        if (idx >= 0) state.scrollToItem((idx - 2).coerceAtLeast(0))
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(150.dp)) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(channels, key = { _, g -> g.key }) { index, group ->
                LaunchedEffect(group.key) { vm.requestEpg(group) }
                val requester = remember { FocusRequester() }
                if (restoreId != null && restoreId in group.ids) LaunchedEffect(Unit) { requester.tryRequestFocus(); vm.focusRestored() }
                ChannelRow(
                    group = group,
                    epg = vm.epg[group.epgStream.id],
                    now = vm.now,
                    favourite = vm.isFavourite(group, favouriteIds),
                    onClick = { vm.play(channels, index, navigator) },
                    onLongClick = { vm.toggleFavourite(group) },
                    onMenu = { vm.openCatchUp(group, navigator) },
                    modifier = Modifier.focusRequester(requester),
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    group: ChannelGroup,
    epg: List<EpgEntry>?,
    now: Long,
    favourite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val current = epg?.firstOrNull { it.startMs <= now && it.endMs > now }
    val next = epg?.firstOrNull { it.startMs >= (current?.endMs ?: now) && it !== current }
    val muted = if (focused) Color.Black.copy(alpha = 0.6f) else AppColors.Muted

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                // Menu opens catch-up; OK and long-press OK are taken by play and favourite.
                if (e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU && group.archiveDays > 0) {
                    if (e.type == KeyEventType.KeyUp) onMenu()
                    true
                } else false
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Surface,
            contentColor = AppColors.OnBackground,
            focusedContainerColor = AppColors.OnBackground,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, AppColors.Accent), shape = RoundedCornerShape(8.dp))),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group.num?.toString() ?: "", style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.width(44.dp))
                RemoteImage(
                    group.icon,
                    group.name.substringAfter(": ").take(3),
                    Modifier.size(width = 64.dp, height = 40.dp).background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(4.dp)).padding(3.dp),
                    contentScale = ContentScale.Fit,
                    fallbackBackground = Color.Transparent,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        (if (favourite) "★ " else "") + group.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val tags = listOfNotNull(
                        group.qualities.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                        if (group.archiveDays > 0) "⟲ ${group.archiveDays}-day catch-up" else null,
                    )
                    if (tags.isNotEmpty()) {
                        Text(tags.joinToString("   "), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
                    }
                }
            }
            Column(Modifier.weight(1.2f).padding(end = 16.dp)) {
                if (current != null) {
                    Text(current.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(current.progress(now), Modifier.weight(1f), height = 3.dp)
                        Text(
                            "  ${formatClock(current.startMs)}–${formatClock(current.endMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                        )
                    }
                } else {
                    Text(if (epg == null) "…" else "No programme information", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            Column(Modifier.weight(1f)) {
                if (next != null) {
                    Text(next.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatClock(next.startMs), style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
}
