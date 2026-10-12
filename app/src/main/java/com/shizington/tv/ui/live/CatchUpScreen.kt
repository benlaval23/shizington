package com.shizington.tv.ui.live

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.shizington.tv.AppContainer
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.EpgEntry
import com.shizington.tv.data.model.Playable
import com.shizington.tv.data.model.PlaybackRequest
import com.shizington.tv.ui.browse.ListState
import com.shizington.tv.ui.components.ErrorMessage
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.RemoteImage
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.formatClock
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.components.tryRequestFocus
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CatchUpViewModel(private val streamId: Long, private val c: AppContainer) : ViewModel() {
    var programmes by mutableStateOf<ListState<List<EpgEntry>>>(ListState.Loading)
        private set

    init { load() }

    fun load() {
        programmes = ListState.Loading
        viewModelScope.launch {
            programmes = try {
                ListState.Ready(c.xtream.catchUp(streamId))
            } catch (e: Exception) {
                ListState.Failed(e.message ?: "Couldn't load the programme guide")
            }
        }
    }
}

private val dayFormat = SimpleDateFormat("EEEE d MMMM", Locale.UK)

private fun dayLabel(ms: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(day, today)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(day, today)) return "Yesterday"
    return dayFormat.format(Date(ms))
}

/** Recorded programmes for one channel, newest first, grouped by day. */
@Composable
fun CatchUpScreen(streamId: Long, channelName: String, icon: String?, navigator: AppNavigator) {
    val vm = appViewModel(key = "catchup_$streamId") { CatchUpViewModel(streamId, it) }
    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(
                icon, channelName.take(3),
                Modifier.size(width = 96.dp, height = 56.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(6.dp),
                contentScale = ContentScale.Fit,
                fallbackBackground = Color.Transparent,
            )
            Spacer(Modifier.width(20.dp))
            Column {
                Text("Catch-up", style = MaterialTheme.typography.labelMedium, color = AppColors.Accent)
                Text(channelName, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.size(20.dp))
        when (val state = vm.programmes) {
            ListState.Loading -> Loader(Modifier.fillMaxSize())
            is ListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorMessage(state.message, vm::load) }
            is ListState.Ready -> if (state.value.isEmpty()) {
                Text("No recordings are available for this channel right now.", color = AppColors.Muted)
            } else {
                ProgrammeList(state.value) { entry ->
                    navigator.play(
                        PlaybackRequest(
                            listOf(
                                Playable(
                                    type = ContentType.LIVE,
                                    id = streamId,
                                    name = channelName,
                                    icon = icon,
                                    extension = null,
                                    archiveStartMs = entry.startMs,
                                    archiveMinutes = entry.durationMinutes,
                                    programme = entry.title,
                                ),
                            ),
                            0,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeList(entries: List<EpgEntry>, onPlay: (EpgEntry) -> Unit) {
    val byDay = remember(entries) { entries.groupBy { dayLabel(it.startMs) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (first.tryRequestFocus()) return@LaunchedEffect
            delay(50)
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(120.dp)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val firstStart = entries.first().startMs
            byDay.forEach { (day, list) ->
                item(key = "day_$day") {
                    Text(day, style = MaterialTheme.typography.titleMedium, color = AppColors.Muted, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                items(list, key = { it.startMs }) { entry ->
                    val modifier = if (entry.startMs == firstStart) Modifier.focusRequester(first) else Modifier
                    ProgrammeRow(entry, modifier) { onPlay(entry) }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeRow(entry: EpgEntry, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Surface,
            contentColor = AppColors.OnBackground,
            focusedContainerColor = AppColors.OnBackground,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, AppColors.Accent), shape = RoundedCornerShape(8.dp))),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatClock(entry.startMs)}–${formatClock(entry.endMs)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(130.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                entry.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = AppColors.Muted)
                }
            }
            Text("${entry.durationMinutes} min", style = MaterialTheme.typography.labelSmall, color = AppColors.Muted)
        }
    }
}
