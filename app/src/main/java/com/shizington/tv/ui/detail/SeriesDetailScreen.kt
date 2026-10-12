package com.shizington.tv.ui.detail

import com.shizington.tv.ui.components.SkeletonDetail
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.shizington.tv.AppContainer
import com.shizington.tv.data.db.ProgressEntity
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.Episode
import com.shizington.tv.data.model.PlaybackRequest
import com.shizington.tv.data.model.Playable
import com.shizington.tv.data.model.SeriesInfo
import com.shizington.tv.data.reco.DetailExtras
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.shizington.tv.data.model.Stream
import com.shizington.tv.data.model.formatRating
import com.shizington.tv.ui.browse.ListState
import com.shizington.tv.ui.components.ErrorMessage
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.ProgressCard
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.components.tryRequestFocus
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where "Play" should start: the in-progress episode, the one after the last finished, or S1E1. */
data class NextUp(val episode: Episode, val positionMs: Long, val label: String)

fun computeNextUp(info: SeriesInfo, progress: List<ProgressEntity>): NextUp? {
    val all = info.seasons.flatMap { it.episodes }
    val first = all.firstOrNull() ?: return null
    val latest = progress.maxByOrNull { it.updatedAt }
        ?: return NextUp(first, 0, "Play S${first.season}E${first.episodeNum}")
    val idx = all.indexOfFirst { it.id == latest.contentId }
    if (idx < 0) return NextUp(first, 0, "Play S${first.season}E${first.episodeNum}")
    val ep = all[idx]
    return when {
        latest.isResumable -> NextUp(ep, latest.positionMs, "Resume S${ep.season}E${ep.episodeNum}")
        latest.isCompleted && idx + 1 < all.size -> all[idx + 1].let { NextUp(it, 0, "Play S${it.season}E${it.episodeNum}") }
        else -> NextUp(ep, 0, "Play S${ep.season}E${ep.episodeNum}")
    }
}

fun Episode.toPlayable(info: SeriesInfo) = Playable(
    type = ContentType.EPISODE,
    id = id,
    name = title,
    icon = image ?: info.cover,
    extension = extension,
    seriesId = info.id,
    seriesName = info.name,
    seriesCover = info.cover,
    season = season,
    episode = episodeNum,
)

class SeriesDetailViewModel(private val id: Long, private val c: AppContainer) : ViewModel() {
    var info by mutableStateOf<ListState<SeriesInfo>>(ListState.Loading)
        private set
    var selectedSeason by mutableIntStateOf(0)
    val isFavourite = c.library.isFavourite(ContentType.SERIES, id).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val progress = c.library.seriesProgress(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var lastPlayedEpisode: Long? = null
    var extras by mutableStateOf<DetailExtras?>(null)
        private set
    var similar by mutableStateOf<List<Stream>>(emptyList())
        private set

    init { load() }

    fun load() {
        info = ListState.Loading
        viewModelScope.launch {
            info = try {
                val s = c.xtream.seriesInfo(id)
                // Open on the season the viewer is in.
                val nextUp = computeNextUp(s, progress.value)
                if (nextUp != null) selectedSeason = s.seasons.indexOfFirst { it.number == nextUp.episode.season }.coerceAtLeast(0)
                launch {
                    extras = runCatching { c.recommendations.detailExtras(ContentType.SERIES, id, s.name, s.releaseDate?.take(4)?.toIntOrNull(), null) }.getOrNull()
                    if (extras?.moreLikeThis.orEmpty().size < 4) {
                        similar = runCatching { c.xtream.similar(ContentType.SERIES, id, s.genre) }.getOrDefault(emptyList())
                    }
                }
                ListState.Ready(s)
            } catch (e: Exception) {
                ListState.Failed(e.message ?: "Couldn't load this series")
            }
        }
    }

    fun toggleFavourite(s: SeriesInfo) {
        viewModelScope.launch {
            c.library.setFavourite(Stream(ContentType.SERIES, s.id, s.name, s.cover, null, rating = s.rating, plot = s.plot, backdrop = s.backdrop), !isFavourite.value)
        }
    }
}

@Composable
fun SeriesDetailScreen(id: Long, navigator: AppNavigator) {
    val vm = appViewModel(key = "series_$id") { SeriesDetailViewModel(id, it) }
    when (val state = vm.info) {
        ListState.Loading -> SkeletonDetail()
        is ListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorMessage(state.message, vm::load) }
        is ListState.Ready -> SeriesDetailContent(state.value, vm, navigator)
    }
}

@Composable
private fun SeriesDetailContent(series: SeriesInfo, vm: SeriesDetailViewModel, navigator: AppNavigator) {
    val favourite by vm.isFavourite.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val progressById = remember(progress) { progress.associateBy { it.contentId } }
    val nextUp = remember(series, progress) { computeNextUp(series, progress) }
    var focusedEpisode by remember { mutableStateOf<Episode?>(null) }
    val playFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val restoreEpisode = remember { vm.lastPlayedEpisode }
    LaunchedEffect(Unit) {
        val season = series.seasons.getOrNull(vm.selectedSeason)
        if (restoreEpisode == null || season?.episodes?.none { it.id == restoreEpisode } != false) playFocus.tryRequestFocus()
    }

    fun playEpisode(ep: Episode, positionMs: Long) {
        vm.lastPlayedEpisode = ep.id
        navigator.play(PlaybackRequest(listOf(ep.toPlayable(series)), 0, positionMs))
    }

    val d = vm.extras?.details
    val trailer = series.trailer?.takeIf { youTubeId(it) != null } ?: trailerUrl(d?.trailerKey)

    Box(Modifier.fillMaxSize()) {
        Backdrop(d?.backdrop ?: series.backdrop ?: series.cover)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 36.dp)) {
            Column(Modifier.padding(start = 48.dp).width(600.dp).heightIn(min = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(listOfNotNull("SERIES", d?.network?.uppercase()).joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = AppColors.Accent)
                DetailTitle(series.name, d?.logo, height = 80.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    d?.certification?.let { CertBadge(it) }
                    MetaRow(
                        listOfNotNull(
                            series.releaseDate?.take(4),
                            formatRating(series.rating),
                            "${series.seasons.size} season" + if (series.seasons.size == 1) "" else "s",
                            series.genre,
                        ),
                    )
                }
                seriesStatus(d)?.let { (text, highlight) ->
                    Text(text, style = MaterialTheme.typography.labelLarge, color = if (highlight) AppColors.Rating else AppColors.Muted)
                }
                val ep = focusedEpisode
                if (ep != null) {
                    Text("S${ep.season}E${ep.episodeNum} · ${ep.title}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ep.plot ?: series.plot.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = AppColors.OnBackground.copy(alpha = 0.85f))
                } else {
                    Text(series.plot.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis, color = AppColors.OnBackground.copy(alpha = 0.9f))
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (nextUp != null) {
                        ActionButton(nextUp.label, ActionIcon.Play, primary = true, modifier = Modifier.focusRequester(playFocus), onClick = {
                            playEpisode(nextUp.episode, nextUp.positionMs)
                        })
                    }
                    if (trailer != null) {
                        ActionButton("Trailer", ActionIcon.Trailer, onClick = { openTrailer(context, trailer) })
                    }
                    ActionButton(
                        text = if (favourite) "On My List" else "My List",
                        icon = if (favourite) ActionIcon.Favourite else ActionIcon.NotFavourite,
                        onClick = { vm.toggleFavourite(series) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (series.seasons.isEmpty()) {
                Text("No episodes available.", color = AppColors.Muted, modifier = Modifier.padding(start = 48.dp, bottom = 48.dp))
            } else {
                SeasonTabs(series, vm.selectedSeason) { vm.selectedSeason = it }
                val season = series.seasons[vm.selectedSeason.coerceIn(0, series.seasons.lastIndex)]
                val episodesState = rememberLazyListState(
                    initialFirstVisibleItemIndex = season.episodes.indexOfFirst { it.id == restoreEpisode }.coerceAtLeast(0),
                )
                LaunchedEffect(season.number) { if (season.episodes.none { it.id == restoreEpisode }) episodesState.scrollToItem(0) }
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(48.dp)) {
                    LazyRow(
                        state = episodesState,
                        modifier = Modifier.fillMaxWidth().focusRestorer(),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(season.episodes, key = { it.id }) { ep ->
                            val p = progressById[ep.id]
                            val requester = remember { FocusRequester() }
                            if (ep.id == restoreEpisode) LaunchedEffect(Unit) {
                                if (!requester.tryRequestFocus()) playFocus.tryRequestFocus()
                                vm.lastPlayedEpisode = null
                            }
                            ProgressCard(
                                title = "${ep.episodeNum}. ${ep.title}",
                                image = ep.image ?: season.cover ?: series.cover,
                                progress = p?.fraction,
                                subtitle = ep.durationSecs?.takeIf { it > 0 }?.let { "${(it + 59) / 60} min" },
                                badge = if (p?.isCompleted == true) "Watched" else null,
                                onClick = { playEpisode(ep, p?.takeIf { it.isResumable }?.positionMs ?: 0L) },
                                modifier = Modifier.focusRequester(requester),
                                onFocused = { focusedEpisode = ep },
                                width = 200.dp,
                            )
                        }
                    }
                }
            }
            ExtrasSections(vm.extras, navigator, vm.similar)
        }
    }
}

/** "New episode Fri 17 Oct", "Returning series", "Ended" from TMDB. */
private fun seriesStatus(d: com.shizington.tv.data.tmdb.TmdbDetails?): Pair<String, Boolean>? {
    d ?: return null
    d.nextEpisodeDate?.let { date ->
        val pretty = runCatching {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)!!
            java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.UK).format(day)
        }.getOrDefault(date)
        return "Next episode $pretty" + (d.nextEpisodeLabel?.let { " · $it" } ?: "") to true
    }
    return when (d.status) {
        "Ended" -> "Ended" + (d.lastAirDate?.take(4)?.let { " in $it" } ?: "") to false
        "Canceled" -> "Cancelled" to false
        "Returning Series" -> "Returning series" to false
        else -> null
    }
}

@Composable
private fun SeasonTabs(series: SeriesInfo, selected: Int, onSelect: (Int) -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(48.dp)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(series.seasons.size) { index ->
                val isSelected = index == selected
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onSelect(index) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) AppColors.SurfaceHigh else Color.Transparent,
                        contentColor = if (isSelected) AppColors.OnBackground else AppColors.Muted,
                        focusedContainerColor = AppColors.OnBackground,
                        focusedContentColor = Color.Black,
                    ),
                ) {
                    val season = series.seasons[index]
                    val year = season.airDate?.take(4)?.takeIf { it.all(Char::isDigit) }
                    Text(season.name + (year?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}
