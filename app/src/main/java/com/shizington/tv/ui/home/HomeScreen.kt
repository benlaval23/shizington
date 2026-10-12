package com.shizington.tv.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shizington.tv.AppContainer
import com.shizington.tv.data.db.ProgressEntity
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.PlaybackRequest
import com.shizington.tv.data.model.Stream
import com.shizington.tv.data.model.formatRating
import com.shizington.tv.data.model.toPlayable
import com.shizington.tv.data.reco.BillboardItem
import com.shizington.tv.data.reco.FeedItem
import com.shizington.tv.data.reco.FeedRow
import com.shizington.tv.data.reco.HomeFeed
import com.shizington.tv.data.reco.RowStyle
import com.shizington.tv.data.sports.SportListing
import com.shizington.tv.data.sports.SportTopic
import com.shizington.tv.data.sports.SportsFeed
import com.shizington.tv.data.toPlayable
import com.shizington.tv.ui.browse.ListState
import com.shizington.tv.ui.components.CardMenu
import com.shizington.tv.ui.components.ErrorMessage
import com.shizington.tv.ui.components.HeroBanner
import com.shizington.tv.ui.components.HeroData
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.MenuAction
import com.shizington.tv.ui.components.PillButton
import com.shizington.tv.ui.components.PosterCard
import com.shizington.tv.ui.components.ProgressCard
import com.shizington.tv.ui.components.RowSection
import com.shizington.tv.ui.components.RowStartPadding
import com.shizington.tv.ui.components.SportCard
import com.shizington.tv.ui.components.Top10Card
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.formatClock
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.components.tryRequestFocus
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    var feed by mutableStateOf<ListState<HomeFeed>>(ListState.Loading)
        private set
    var sports by mutableStateOf<SportsFeed?>(c.sports.peek())
        private set
    val continueWatching = c.library.continueWatching.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favouriteKeys = c.library.favourites.map { list -> list.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Titles dismissed with "Not for me" this session, hidden straight away. */
    val hidden = mutableStateListOf<String>()

    var focusedHero by mutableStateOf<HeroData?>(null)
        private set
    var billboardIndex by mutableIntStateOf(0)
    var inBillboard by mutableStateOf(true)
        private set
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    var restoreKey by mutableStateOf<String?>(null)
        private set
    private var lastFocusKey: String? = null
    private var pendingRestore: String? = null
    private var heroJob: Job? = null
    private var loadedAt = 0L
    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                now = System.currentTimeMillis()
            }
        }
    }

    fun load(force: Boolean = false) {
        if (!force && feed is ListState.Ready && System.currentTimeMillis() - loadedAt < 30 * 60_000L) return
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            if (feed !is ListState.Ready) feed = ListState.Loading
            feed = try {
                ListState.Ready(c.recommendations.homeFeed()).also { loadedAt = System.currentTimeMillis() }
            } catch (e: Exception) {
                if (feed is ListState.Ready) feed else ListState.Failed(e.message ?: "Couldn't build your home screen")
            }
            // Sport scans ~100 guide entries, so it runs after the main feed is on screen.
            delay(1_000)
            sports = runCatching { c.sports.feed() }.getOrNull() ?: sports
            // Home has already downloaded the catalogue, so building search indexes now makes the first search instant.
            c.xtream.prewarmSearch()
        }
    }

    fun onBillboardFocused() {
        inBillboard = true
        heroJob?.cancel()
        focusedHero = null
    }

    fun onItemFocused(rowKey: String, item: FeedItem) {
        lastFocusKey = "$rowKey/${item.stream.key}"
        inBillboard = false
        val s = item.stream
        heroJob?.cancel()
        focusedHero = HeroData(
            key = s.key,
            label = if (s.type == ContentType.SERIES) "Series" else "Movie",
            title = s.name,
            image = s.backdrop ?: s.icon,
            meta = listOfNotNull(s.year, s.runtimeMin?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") }, formatRating(s.rating), s.genres.firstOrNull()),
            plot = s.plot,
            reason = item.reason,
        )
        // Upgrade to TMDB art (logo + text-free backdrop + UK rating) once focus settles.
        heroJob = viewModelScope.launch {
            delay(450)
            val type = if (s.type == ContentType.SERIES) ContentType.SERIES else ContentType.MOVIE
            val id = item.tmdbId ?: c.tmdb.findId(type, s.id, s.name, s.year?.toIntOrNull()) ?: return@launch
            val d = c.tmdb.details(type, id) ?: return@launch
            if (focusedHero?.key == s.key) {
                focusedHero = focusedHero?.copy(
                    logo = d.logo,
                    image = d.backdrop ?: focusedHero?.image,
                    meta = listOfNotNull(d.certification) + (focusedHero?.meta ?: emptyList()),
                )
            }
        }
    }

    fun onProgressFocused(p: ProgressEntity) {
        lastFocusKey = "continue/${p.type}:${p.contentId}"
        inBillboard = false
        heroJob?.cancel()
        focusedHero = HeroData(
            key = "continue:${p.contentId}",
            label = "Continue watching",
            title = p.seriesName ?: p.name,
            image = p.seriesCover ?: p.icon,
            meta = listOfNotNull(p.season?.let { "S${it}E${p.episode}" }, "${((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(1)} min left"),
            plot = if (p.seriesName != null) p.name else null,
        )
    }

    fun onSportFocused(l: SportListing) {
        lastFocusKey = "sport/${l.channel.key}/${l.entry.startMs}"
        inBillboard = false
        heroJob?.cancel()
        focusedHero = HeroData(
            key = "sport:${l.channel.key}:${l.entry.startMs}",
            label = l.topics.first().label,
            title = l.entry.title,
            image = c.sports.peek()?.teams?.firstOrNull { it.topic in l.topics }?.fanart,
            meta = listOf(l.channel.name, "${formatClock(l.entry.startMs)}–${formatClock(l.entry.endMs)}"),
            plot = l.entry.description,
            reason = if (SportTopic.MAN_UTD in l.topics) "Man Utd" else if (SportTopic.ENGLAND in l.topics) "England" else null,
        )
    }

    fun feedback(s: Stream, value: Int) {
        if (value < 0) hidden += s.key
        viewModelScope.launch { c.library.setFeedback(s, value) }
    }

    fun toggleFavourite(s: Stream) {
        viewModelScope.launch { c.library.setFavourite(s, s.key !in favouriteKeys.value) }
    }

    fun playMovie(s: Stream, navigator: AppNavigator) {
        viewModelScope.launch {
            val resume = c.library.getProgress(ContentType.MOVIE, s.id)?.takeIf { it.isResumable }?.positionMs ?: 0L
            rememberFocus()
            navigator.play(PlaybackRequest(listOf(s.toPlayable()), 0, resume))
        }
    }

    fun playLive(l: SportListing, navigator: AppNavigator) {
        rememberFocus()
        navigator.play(PlaybackRequest(listOf(l.channel.toPlayable(c.credentialsStore.preferredQuality)), 0))
    }

    fun rememberFocus() { pendingRestore = lastFocusKey }
    fun beginRestore() { restoreKey = pendingRestore; pendingRestore = null }
    fun focusRestored() { restoreKey = null }
}

private fun greeting(): Pair<String, String> {
    val c = Calendar.getInstance()
    val h = c.get(Calendar.HOUR_OF_DAY)
    val day = c.get(Calendar.DAY_OF_WEEK)
    val hello = when (h) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    val mood = when {
        day == Calendar.FRIDAY && h >= 17 -> "It's Friday night. Something to settle into?"
        day == Calendar.SATURDAY || day == Calendar.SUNDAY -> if (h < 12) "A slow weekend morning." else "Weekend viewing, sorted."
        h >= 22 || h < 5 -> "Late one? Here's something short and good."
        h >= 17 -> "Here's what's good tonight."
        else -> "Here's what's new and worth watching."
    }
    return hello to mood
}

@Composable
fun HomeScreen(navigator: AppNavigator) {
    val vm = appViewModel(key = "home") { HomeViewModel(it) }
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val favouriteKeys by vm.favouriteKeys.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<Stream?>(null) }
    LaunchedEffect(Unit) {
        vm.load()
        vm.beginRestore()
        delay(1500)
        vm.focusRestored()
    }

    val open: (Stream) -> Unit = { s ->
        vm.rememberFocus()
        when (s.type) {
            ContentType.MOVIE -> navigator.openMovie(s.id)
            ContentType.SERIES -> navigator.openSeries(s.id)
            else -> navigator.play(PlaybackRequest(listOf(s.toPlayable()), 0))
        }
    }

    val feed = (vm.feed as? ListState.Ready)?.value
    val billboard = feed?.billboard.orEmpty()

    // Rotate the billboard while focus is on it (or on the tabs).
    LaunchedEffect(billboard.size, vm.inBillboard) {
        while (billboard.size > 1 && vm.inBillboard) {
            delay(9_000)
            vm.billboardIndex = (vm.billboardIndex + 1) % billboard.size
        }
    }
    val current = billboard.getOrNull(vm.billboardIndex % billboard.size.coerceAtLeast(1))
    val hero = if (!vm.inBillboard && vm.focusedHero != null) vm.focusedHero else current?.toHero()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Tall and cinematic on the billboard; shrinks to make room once you're browsing rows.
            val bannerHeight by animateDpAsState(if (vm.inBillboard) 400.dp else 268.dp, tween(350), label = "banner")
            Box(Modifier.fillMaxWidth().height(bannerHeight)) {
                HeroBanner(
                    hero, Modifier.fillMaxSize(),
                    contentBottomPadding = if (vm.inBillboard) 76.dp else 14.dp,
                    plotLines = if (vm.inBillboard) 2 else 3,
                    logoHeight = if (vm.inBillboard) 88.dp else 64.dp,
                )
                if (vm.inBillboard) {
                    val (hello, mood) = remember(vm.now / 600_000) { greeting() }
                    Column(Modifier.align(Alignment.TopStart).padding(start = RowStartPadding, top = 66.dp)) {
                        Text(hello, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(mood, style = MaterialTheme.typography.bodyMedium, color = AppColors.Muted)
                        if (vm.feed is ListState.Loading) {
                            Row(Modifier.padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Loader(Modifier.size(28.dp))
                                Text("Lining up tonight's picks…", style = MaterialTheme.typography.titleMedium, color = AppColors.OnBackground)
                            }
                        }
                    }
                    if (current != null) {
                        Row(
                            Modifier.align(Alignment.BottomStart).padding(start = RowStartPadding, bottom = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val s = current.item.stream
                            PillButton("▶  Play", primary = true, onFocused = vm::onBillboardFocused, onClick = {
                                if (s.type == ContentType.MOVIE) vm.playMovie(s, navigator) else open(s)
                            })
                            PillButton("More info", onFocused = vm::onBillboardFocused, onClick = { open(s) })
                            PillButton("···", onFocused = vm::onBillboardFocused, onClick = { menuFor = s })
                            Row(Modifier.padding(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                billboard.indices.forEach { i ->
                                    Box(
                                        Modifier.size(if (i == vm.billboardIndex % billboard.size) 10.dp else 7.dp)
                                            .background(if (i == vm.billboardIndex % billboard.size) Color.White else Color.White.copy(alpha = 0.35f), CircleShape),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Keep the vertical rhythm when the billboard collapses into the focused-title banner.
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp))
                }
            }

            when (val state = vm.feed) {
                ListState.Loading -> HomeSkeleton(continueWatching, vm, navigator)
                is ListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorMessage(state.message, onRetry = { vm.load(true) }) }
                is ListState.Ready -> HomeRows(state.value, continueWatching, vm, navigator, open, onMenu = { menuFor = it })
            }
        }

        menuFor?.let { s ->
            val fav = s.key in favouriteKeys
            CardMenu(
                title = s.name,
                actions = listOf(
                    MenuAction("👍  More like this") { vm.feedback(s, 1) },
                    MenuAction("👎  Not for me") { vm.feedback(s, -1) },
                    MenuAction(if (fav) "Remove from My List" else "Add to My List") { vm.toggleFavourite(s) },
                    MenuAction("Details") { open(s) },
                ),
                onDismiss = { menuFor = null },
            )
        }
    }
}

private fun BillboardItem.toHero(): HeroData {
    val s = item.stream
    return HeroData(
        key = "billboard:${s.key}",
        label = label,
        title = s.name,
        image = details?.backdrop ?: s.backdrop ?: s.icon,
        meta = listOfNotNull(details?.certification, s.year, s.runtimeMin?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") }, formatRating(s.rating), s.genres.firstOrNull()),
        plot = s.plot,
        logo = details?.logo,
        reason = item.reason,
    )
}

@Composable
private fun HomeSkeleton(continueWatching: List<ProgressEntity>, vm: HomeViewModel, navigator: AppNavigator) {
    Column(Modifier.fillMaxSize()) {
        if (continueWatching.isNotEmpty()) ContinueRow(continueWatching, vm, navigator)
        Column(Modifier.padding(start = RowStartPadding, top = 8.dp)) {
            Loader(Modifier.size(40.dp))
            Text("Putting together picks for you both…", color = AppColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HomeRows(
    feed: HomeFeed,
    continueWatching: List<ProgressEntity>,
    vm: HomeViewModel,
    navigator: AppNavigator,
    open: (Stream) -> Unit,
    onMenu: (Stream) -> Unit,
) {
    val listState = rememberLazyListState()
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(44.dp)) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                if (continueWatching.isNotEmpty()) item(key = "continue") { ContinueRow(continueWatching, vm, navigator) }
                val sport = vm.sports?.let { sportForYou(it, vm.now) }.orEmpty()
                if (sport.isNotEmpty()) item(key = "sport") { SportRow(sport, vm, navigator) }
                items(feed.rows, key = { it.key }) { row ->
                    FeedRowView(row, vm, open, onMenu)
                }
            }
            if (listState.canScrollBackward) {
                Box(Modifier.fillMaxWidth().height(20.dp).background(Brush.verticalGradient(listOf(AppColors.Background, AppColors.Background.copy(alpha = 0f)))))
            }
        }
    }
}

/** Live and soon-to-start events for Man Utd, England and the big competitions. */
private fun sportForYou(f: SportsFeed, now: Long): List<SportListing> {
    val soon = now + 6 * 3_600_000L
    return (f.onNow + f.upcoming.filter { it.entry.startMs < soon })
        .filter { it.isEvent || SportTopic.MAN_UTD in it.topics }
        .distinctBy { it.entry.title.lowercase() + it.entry.startMs }
        .sortedWith(compareByDescending<SportListing> { (if (SportTopic.MAN_UTD in it.topics) 4 else 0) + (if (SportTopic.ENGLAND in it.topics) 3 else 0) + (if (it.entry.startMs <= now) 2 else 0) }.thenBy { it.entry.startMs })
        .take(14)
}

@Composable
private fun ContinueRow(items: List<ProgressEntity>, vm: HomeViewModel, navigator: AppNavigator) {
    RowSection("Continue watching") {
        items(items, key = { "${it.type}:${it.contentId}" }) { p ->
            val key = "continue/${p.type}:${p.contentId}"
            val requester = remember { FocusRequester() }
            if (vm.restoreKey == key) LaunchedEffect(Unit) { requester.tryRequestFocus(); vm.focusRestored() }
            ProgressCard(
                title = if (p.seriesName != null) "${p.seriesName} · S${p.season}E${p.episode}" else p.name,
                image = p.icon ?: p.seriesCover,
                progress = p.fraction,
                onClick = {
                    vm.rememberFocus()
                    navigator.play(PlaybackRequest(listOf(p.toPlayable()), 0, p.positionMs))
                },
                modifier = Modifier.focusRequester(requester),
                onFocused = { vm.onProgressFocused(p) },
            )
        }
    }
}

@Composable
private fun SportRow(items: List<SportListing>, vm: HomeViewModel, navigator: AppNavigator) {
    RowSection("Live sport for you") {
        items(items, key = { "${it.channel.key}/${it.entry.startMs}" }) { l ->
            SportCard(
                title = l.entry.title,
                channel = l.channel.name,
                icon = l.channel.icon,
                topic = l.topics.first().label,
                entry = l.entry,
                now = vm.now,
                onClick = { vm.playLive(l, navigator) },
                onFocused = { vm.onSportFocused(l) },
            )
        }
    }
}

@Composable
private fun FeedRowView(row: FeedRow, vm: HomeViewModel, open: (Stream) -> Unit, onMenu: (Stream) -> Unit) {
    val items = row.items.filter { it.stream.key !in vm.hidden }
    if (items.isEmpty()) return
    Column {
        RowSection(row.title + (row.subtitle?.let { "  ·  $it" } ?: "")) {
            items(items, key = { it.stream.key }) { item ->
                val key = "${row.key}/${item.stream.key}"
                val requester = remember { FocusRequester() }
                if (vm.restoreKey == key) LaunchedEffect(Unit) { requester.tryRequestFocus(); vm.focusRestored() }
                val m = Modifier.focusRequester(requester)
                if (row.style == RowStyle.TOP10 && item.rank != null) {
                    Top10Card(item.rank, item.stream, onClick = { open(item.stream) }, modifier = m,
                        onFocused = { vm.onItemFocused(row.key, item) }, onLongClick = { onMenu(item.stream) })
                } else {
                    PosterCard(item.stream, onClick = { open(item.stream) }, modifier = m, badge = item.badge,
                        onFocused = { vm.onItemFocused(row.key, item) }, onLongClick = { onMenu(item.stream) })
                }
            }
        }
    }
}
