package com.shizington.tv.ui.sport

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.shizington.tv.AppContainer
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.PlaybackRequest
import com.shizington.tv.data.model.Playable
import com.shizington.tv.data.sports.Fixture
import com.shizington.tv.data.sports.SportListing
import com.shizington.tv.data.sports.SportTopic
import com.shizington.tv.data.sports.SportsFeed
import com.shizington.tv.data.sports.TeamCard
import com.shizington.tv.ui.browse.ListState
import com.shizington.tv.ui.components.CardBadge
import com.shizington.tv.ui.components.ErrorMessage
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.RowSection
import com.shizington.tv.ui.components.RowStartPadding
import com.shizington.tv.ui.components.SportCard
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.main.TopBarHeight
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SportViewModel(private val c: AppContainer) : ViewModel() {
    var feed by mutableStateOf<ListState<SportsFeed>>(c.sports.peek()?.let { ListState.Ready(it) } ?: ListState.Loading)
        private set
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    init {
        load()
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                now = System.currentTimeMillis()
            }
        }
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            feed = try {
                ListState.Ready(c.sports.feed(force))
            } catch (e: Exception) {
                if (feed is ListState.Ready) feed else ListState.Failed(e.message ?: "Couldn't load sport")
            }
        }
    }

    fun playLive(l: SportListing, navigator: AppNavigator) =
        navigator.play(PlaybackRequest(listOf(l.channel.toPlayable(c.credentialsStore.preferredQuality)), 0))

    fun playCatchUp(l: SportListing, navigator: AppNavigator) {
        val stream = l.channel.variants.firstOrNull { it.archiveDays > 0 } ?: return
        navigator.play(
            PlaybackRequest(
                listOf(
                    Playable(
                        type = ContentType.LIVE, id = stream.id, name = l.channel.name, icon = l.channel.icon, extension = null,
                        archiveStartMs = l.entry.startMs, archiveMinutes = l.entry.durationMinutes, programme = l.entry.title,
                    ),
                ),
                0,
            ),
        )
    }

    fun playChannel(feed: SportsFeed, card: TeamCard, navigator: AppNavigator) {
        val target = card.nextOnTv?.channel ?: if (card.topic == SportTopic.MAN_UTD) feed.mutv else null
        target?.let { navigator.play(PlaybackRequest(listOf(it.toPlayable(c.credentialsStore.preferredQuality)), 0)) }
    }
}

@Composable
fun SportScreen(navigator: AppNavigator) {
    val vm = appViewModel(key = "sport") { SportViewModel(it) }
    LaunchedEffect(Unit) { vm.load() }
    when (val state = vm.feed) {
        ListState.Loading -> Column(Modifier.fillMaxSize().padding(start = RowStartPadding, top = TopBarHeight + 40.dp)) {
            Loader(Modifier.size(48.dp))
            Text("Checking fixtures and the sports channels…", color = AppColors.Muted)
        }
        is ListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorMessage(state.message, onRetry = { vm.load(true) }) }
        is ListState.Ready -> SportContent(state.value, vm, navigator)
    }
}

@Composable
private fun SportContent(f: SportsFeed, vm: SportViewModel, navigator: AppNavigator) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(44.dp)) {
        // Offset the list itself below the top bar: padding inside the list sits under the tabs and stops D-pad down from reaching the rows.
        LazyColumn(Modifier.fillMaxSize().padding(top = TopBarHeight), contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)) {
            item(key = "teams") {
                RowSection("Your teams") {
                    items(f.teams, key = { it.topic.name }) { card ->
                        TeamCardView(card, vm.now, onClick = { vm.playChannel(f, card, navigator) })
                    }
                    f.mutv?.let { mutv ->
                        item(key = "mutv") {
                            SportCard(
                                title = "MUTV",
                                channel = "Manchester United's own channel",
                                icon = mutv.icon,
                                topic = "Man Utd",
                                entry = com.shizington.tv.data.model.EpgEntry("MUTV", null, vm.now - 1, vm.now + 3_600_000),
                                now = vm.now,
                                badge = "CHANNEL",
                                badgeColor = Color(0xFFDA291C),
                                onClick = { navigator.play(PlaybackRequest(listOf(mutv.toPlayable("best")), 0)) },
                            )
                        }
                    }
                }
            }
            if (f.onNow.isNotEmpty()) item(key = "now") { ListingRow("On now", f.onNow.take(20), vm, navigator) }
            if (f.upcoming.isNotEmpty()) item(key = "next") { ListingRow("Next 36 hours", f.upcoming.filter { it.isEvent }.take(25), vm, navigator) }
            items(f.byTopic.entries.filter { it.key !in SportTopic.teams }.toList(), key = { it.key.name }) { (topic, list) ->
                ListingRow(topic.label, list.take(20), vm, navigator)
            }
            if (f.missed.isNotEmpty()) item(key = "missed") {
                RowSection("Missed it? Catch up") {
                    items(f.missed, key = { "${it.channel.key}/${it.entry.startMs}" }) { l ->
                        SportCard(
                            title = l.entry.title, channel = l.channel.name, icon = l.channel.icon,
                            topic = l.topics.first().label, entry = l.entry, now = vm.now,
                            badge = "⟲ " + dayTime(l.entry.startMs), badgeColor = Color(0xFF2F5D8A),
                            onClick = { vm.playCatchUp(l, navigator) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListingRow(title: String, list: List<SportListing>, vm: SportViewModel, navigator: AppNavigator) {
    if (list.isEmpty()) return
    RowSection(title) {
        items(list, key = { "${it.channel.key}/${it.entry.startMs}" }) { l ->
            SportCard(
                title = l.entry.title, channel = l.channel.name, icon = l.channel.icon,
                topic = l.topics.first().label, entry = l.entry, now = vm.now,
                badge = if (l.entry.startMs > vm.now) dayTime(l.entry.startMs) else null,
                badgeColor = Color(0xFF3A4A5E),
                onClick = { vm.playLive(l, navigator) },
            )
        }
    }
}

@Composable
private fun TeamCardView(card: TeamCard, now: Long, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(520.dp).height(220.dp),
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        scale = CardDefaults.scale(focusedScale = 1.03f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Focus), shape = RoundedCornerShape(14.dp))),
        colors = CardDefaults.colors(containerColor = Color(0xFF14181F)),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (card.fanart != null) {
                AsyncImage(model = card.fanart, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20B0B0F), Color(0xCC0B0B0F), Color(0x660B0B0F)))))
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (card.badge != null) AsyncImage(model = card.badge, contentDescription = null, modifier = Modifier.size(44.dp))
                    Text(card.name, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                }
                val next = card.next
                if (next != null) {
                    val live = now in next.kickoffMs..(next.kickoffMs + 115 * 60_000L)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CardBadge(if (live) "LIVE NOW" else countdown(next.kickoffMs, now), color = if (live) AppColors.Accent else Color(0xFF2F3A4A))
                        next.league?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = AppColors.Muted, maxLines = 1) }
                    }
                    FixtureLine(next)
                    Text(dayTime(next.kickoffMs) + (next.venue?.let { "  ·  $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = AppColors.OnBackground.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        card.nextOnTv?.let { "📺  ${it.channel.name}" } ?: if (card.topic == SportTopic.MAN_UTD) "Channel appears closer to kick-off · OK for MUTV" else "Channel appears closer to kick-off",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (card.nextOnTv != null) Color.White else AppColors.Muted,
                    )
                } else {
                    Text("No fixture scheduled", color = AppColors.Muted)
                }
                Spacer(Modifier.weight(1f))
                card.last?.let { last ->
                    Text("Last: ${last.home} ${last.homeScore ?: "-"}–${last.awayScore ?: "-"} ${last.away}", style = MaterialTheme.typography.labelMedium, color = AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FixtureLine(f: Fixture) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (f.homeBadge != null) AsyncImage(model = f.homeBadge, contentDescription = null, modifier = Modifier.size(26.dp))
        Text(f.home, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("v", style = MaterialTheme.typography.titleMedium, color = AppColors.Muted)
        if (f.awayBadge != null) AsyncImage(model = f.awayBadge, contentDescription = null, modifier = Modifier.size(26.dp))
        Text(f.away, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun dayTime(ms: Long): String {
    val day = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val today = java.util.Calendar.getInstance()
    val sameDay = day.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) && day.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
    today.add(java.util.Calendar.DAY_OF_YEAR, 1)
    val tomorrow = day.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
    today.add(java.util.Calendar.DAY_OF_YEAR, -2)
    val yesterday = day.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    return when {
        sameDay -> "Today $time"
        tomorrow -> "Tomorrow $time"
        yesterday -> "Yesterday $time"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms)) + " $time"
    }
}

private fun countdown(kickoff: Long, now: Long): String {
    val mins = (kickoff - now) / 60_000
    return when {
        mins < 0 -> "FULL TIME"
        mins < 60 -> "IN $mins MIN"
        mins < 24 * 60 -> "IN ${mins / 60}H ${mins % 60}M"
        mins < 48 * 60 -> "IN ${mins / 60} HOURS"
        else -> "IN ${mins / (24 * 60)} DAYS"
    }
}
