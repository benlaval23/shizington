@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shizington.tv.ui.player

import android.app.Activity
import android.app.Application
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import android.view.View
import com.shizington.tv.LocalVideoView
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.EpgEntry
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.ProgressLine
import com.shizington.tv.ui.components.RemoteImage
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.formatClock
import com.shizington.tv.ui.components.formatDuration
import com.shizington.tv.ui.components.rememberPivotSpec
import com.shizington.tv.ui.components.tryRequestFocus
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

private const val SEEK_STEP_MS = 10_000L
private const val HIDE_AFTER_MS = 5_000L

private enum class Overlay { NONE, CONTROLS, CHANNELS }

@Composable
fun PlayerScreen(navigator: AppNavigator) {
    val container = com.shizington.tv.ui.components.appContainer()
    val request = remember { container.pendingPlayback }
    if (request == null || request.items.isEmpty()) {
        LaunchedEffect(Unit) { navigator.back() }
        return
    }
    val app = LocalContext.current.applicationContext as Application
    val vm = appViewModel { PlayerViewModel(app, it, request) }
    PlayerContent(
        vm,
        onExit = { navigator.back() },
        onCatchUp = {
            val p = vm.current
            p.archiveStreamId?.let { navigator.openCatchUp(it, p.name, p.icon) }
        },
    )
}

@Composable
private fun PlayerContent(vm: PlayerViewModel, onExit: () -> Unit, onCatchUp: () -> Unit) {
    var overlay by remember { mutableStateOf(Overlay.CONTROLS) }
    var trackType by remember { mutableStateOf<Int?>(null) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }

    // The video plays on a hardware layer behind the window. On older Fire OS the window stays opaque,
    // so the hole punched for the video comes out black; a translucent window lets it show through.
    val activity = LocalContext.current as? Activity
    val container = com.shizington.tv.ui.components.appContainer()
    DisposableEffect(activity) {
        val window = activity?.window
        window?.setFormat(PixelFormat.TRANSLUCENT)
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        container.playerVisible.value = true
        onDispose {
            container.playerVisible.value = false
            window?.setBackgroundDrawable(ColorDrawable(AppColors.Background.toArgb()))
            window?.setFormat(PixelFormat.OPAQUE)
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.onBackground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Auto-hide overlays after a period without key presses.
    LaunchedEffect(overlay, lastInteraction, trackType) {
        if (overlay != Overlay.NONE && trackType == null) {
            delay(HIDE_AFTER_MS)
            if (vm.isPlaying || vm.isLive) overlay = Overlay.NONE
        }
    }
    LaunchedEffect(overlay, trackType) {
        if (overlay == Overlay.NONE && trackType == null) rootFocus.tryRequestFocus()
    }
    // Briefly show channel info after zapping.
    LaunchedEffect(vm.index) { if (vm.isLive && overlay == Overlay.NONE) overlay = Overlay.CONTROLS }

    // One press leaves the player; only the audio/subtitle picker and channel list close first.
    fun onBack() {
        when {
            trackType != null -> trackType = null
            overlay == Overlay.CHANNELS -> overlay = Overlay.NONE
            else -> onExit()
        }
    }
    BackHandler { onBack() }

    fun show(o: Overlay) {
        overlay = o
        lastInteraction = System.currentTimeMillis()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                lastInteraction = System.currentTimeMillis()
                val code = event.nativeKeyEvent.keyCode
                val down = event.type == KeyEventType.KeyDown
                // Compose spends Back on clearing focus from the control buttons before any BackHandler sees it,
                // which made leaving the player take two presses. Handle it here, on release, instead.
                if (code == AndroidKeyEvent.KEYCODE_BACK) {
                    if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) onBack()
                    return@onPreviewKeyEvent true
                }
                // Media keys work in every state.
                when (code) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (down) { vm.togglePlayPause(); if (!vm.isLive) show(Overlay.CONTROLS) }
                        return@onPreviewKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { vm.seekBy(SEEK_STEP_MS * 3); show(Overlay.CONTROLS) }; return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { vm.seekBy(-SEEK_STEP_MS * 3); show(Overlay.CONTROLS) }; return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP, AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (vm.isLive) { if (down) vm.changeChannel(+1); return@onPreviewKeyEvent true }
                    }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (vm.isLive) { if (down) vm.changeChannel(-1); return@onPreviewKeyEvent true }
                    }
                    AndroidKeyEvent.KEYCODE_MENU -> { if (down) { trackType = C.TRACK_TYPE_AUDIO }; return@onPreviewKeyEvent true }
                }
                // The live info bar only has a row of buttons, so up/down keep zapping channels while it's open.
                if (overlay == Overlay.CONTROLS && trackType == null && vm.isLive &&
                    (code == AndroidKeyEvent.KEYCODE_DPAD_UP || code == AndroidKeyEvent.KEYCODE_DPAD_DOWN)
                ) {
                    if (down) vm.changeChannel(if (code == AndroidKeyEvent.KEYCODE_DPAD_UP) +1 else -1)
                    return@onPreviewKeyEvent true
                }
                if (overlay != Overlay.NONE || trackType != null) return@onPreviewKeyEvent false
                // Nothing on screen: the D-pad drives playback directly.
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (down) show(Overlay.CONTROLS); true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (down) { if (vm.isLive) vm.changeChannel(+1) else show(Overlay.CONTROLS) }; true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) { if (vm.isLive) vm.changeChannel(-1) else show(Overlay.CONTROLS) }; true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (down) { if (vm.isLive) { if (vm.hasQueue) show(Overlay.CHANNELS) } else { vm.seekBy(-SEEK_STEP_MS); show(Overlay.CONTROLS) } }; true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down) { if (vm.isLive) show(Overlay.CONTROLS) else { vm.seekBy(SEEK_STEP_MS); show(Overlay.CONTROLS) } }; true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        // Attach the player to the activity's video view (underneath this UI) for as long as this screen is up.
        val videoView = LocalVideoView.current
        DisposableEffect(videoView, vm.player) {
            videoView?.player = vm.player
            videoView?.visibility = View.VISIBLE
            onDispose {
                videoView?.player = null
                videoView?.visibility = View.GONE
            }
        }

        if (vm.isBuffering && vm.error == null) Loader(Modifier.align(Alignment.Center), size = 56.dp)

        vm.error?.let { message ->
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(message, style = MaterialTheme.typography.titleMedium)
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(message) { retryFocus.tryRequestFocus() }
                Button(onClick = vm::retry, modifier = Modifier.focusRequester(retryFocus)) { Text("Retry") }
            }
        }

        AnimatedVisibility(overlay == Overlay.CONTROLS, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (vm.isLive) {
                LiveInfoOverlay(vm, onTracks = { trackType = it }, onCatchUp = onCatchUp)
            } else {
                VodControls(vm, seekFocus, onTracks = { trackType = it })
            }
        }

        AnimatedVisibility(overlay == Overlay.CHANNELS, enter = fadeIn(), exit = fadeOut()) {
            ChannelListOverlay(vm) { i ->
                vm.jumpToChannel(i)
                overlay = Overlay.CONTROLS
            }
        }

        trackType?.let { type ->
            TrackPicker(vm, type, onDismiss = { trackType = null })
        }
    }
}

@Composable
private fun OverlayButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) { Text(text) }
}

private val BottomShade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))
private val TopShade = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent))

@Composable
private fun VodControls(vm: PlayerViewModel, seekFocus: FocusRequester, onTracks: (Int) -> Unit) {
    LaunchedEffect(Unit) { seekFocus.tryRequestFocus() }
    val p = vm.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(120.dp).background(TopShade).padding(horizontal = 48.dp, vertical = 28.dp)) {
            Column {
                Text(p.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.type == ContentType.EPISODE) Text(p.name, style = MaterialTheme.typography.bodyMedium, color = AppColors.Muted)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(BottomShade).padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SeekBar(vm, seekFocus)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OverlayButton(if (vm.isPlaying) "❚❚  Pause" else "▶  Play", vm::togglePlayPause)
                OverlayButton("−10s", { vm.seekBy(-SEEK_STEP_MS) })
                OverlayButton("+10s", { vm.seekBy(SEEK_STEP_MS) })
                OverlayButton("Audio", { onTracks(C.TRACK_TYPE_AUDIO) })
                OverlayButton("Subtitles", { onTracks(C.TRACK_TYPE_TEXT) })
                if (vm.canGoLive) OverlayButton("● Go live", vm::goLive)
                vm.nextEpisode?.let { next ->
                    OverlayButton("Next: S${next.season}E${next.episode}", { vm.playEpisode(next) })
                }
            }
        }
    }
}

/** Focusable progress bar: left/right seek while it holds focus. */
@Composable
private fun SeekBar(vm: PlayerViewModel, focus: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    val duration = vm.durationMs
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { vm.seekBy(-SEEK_STEP_MS * (1 + e.nativeKeyEvent.repeatCount / 5)); true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { vm.seekBy(SEEK_STEP_MS * (1 + e.nativeKeyEvent.repeatCount / 5)); true }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> { vm.togglePlayPause(); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            ProgressLine(
                if (duration > 0) vm.positionMs.toFloat() / duration else 0f,
                Modifier.then(if (focused) Modifier.border(BorderStroke(1.dp, Color.White), RoundedCornerShape(4.dp)) else Modifier),
                height = if (focused) 8.dp else 4.dp,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(formatDuration(vm.positionMs), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) "-" + formatDuration(duration - vm.positionMs) else "", style = MaterialTheme.typography.labelMedium, color = AppColors.Muted)
        }
    }
}

@Composable
private fun LiveInfoOverlay(vm: PlayerViewModel, onTracks: (Int) -> Unit, onCatchUp: () -> Unit) {
    val p = vm.current
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstButton.tryRequestFocus() }
    val now = System.currentTimeMillis()
    val current = vm.epg.firstOrNull { it.startMs <= now && it.endMs > now }
    val next = vm.epg.firstOrNull { it.startMs >= (current?.endMs ?: now) && it !== current }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(BottomShade).padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                p.icon, p.name.take(3),
                Modifier.size(width = 120.dp, height = 72.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(6.dp),
                contentScale = ContentScale.Fit,
                fallbackBackground = Color.Transparent,
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    p.num?.let { Text("$it  ", style = MaterialTheme.typography.headlineSmall, color = AppColors.Accent) }
                    Text(p.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(12.dp))
                    Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.background(AppColors.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                EpgLine("Now", current, now)
                EpgLine("Next", next, null)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    // Whichever button comes first takes focus when the bar opens.
                    var focusTaken = false
                    fun first(): Modifier = if (focusTaken) Modifier else Modifier.focusRequester(firstButton).also { focusTaken = true }
                    if (vm.startOverProgramme != null) OverlayButton("⟲ Start over", vm::startOver, first())
                    if (p.archiveStreamId != null) OverlayButton("Catch-up", onCatchUp, first())
                    if (vm.hasQualityChoice) OverlayButton("Quality: ${p.quality ?: "Auto"}", vm::cycleQuality, first())
                    OverlayButton("Audio", { onTracks(C.TRACK_TYPE_AUDIO) }, first())
                    OverlayButton("Subtitles", { onTracks(C.TRACK_TYPE_TEXT) })
                    Spacer(Modifier.width(12.dp))
                    Text("▲▼ channel   ◀ channel list", style = MaterialTheme.typography.labelSmall, color = AppColors.Muted)
                }
            }
        }
    }
}

@Composable
private fun EpgLine(label: String, entry: EpgEntry?, now: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = AppColors.Muted, modifier = Modifier.width(44.dp))
        if (entry == null) {
            Text("No information", style = MaterialTheme.typography.bodyMedium, color = AppColors.Muted)
        } else {
            Text("${formatClock(entry.startMs)}  ${entry.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (now != null) {
                Spacer(Modifier.width(12.dp))
                ProgressLine(entry.progress(now), Modifier.width(120.dp))
            }
        }
    }
}

@Composable
private fun ChannelListOverlay(vm: PlayerViewModel, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (vm.index - 3).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { currentFocus.tryRequestFocus() }
    Box(Modifier.fillMaxHeight().width(360.dp).background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.6f))))) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(160.dp)) {
            LazyColumn(state = state, contentPadding = PaddingValues(vertical = 24.dp, horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(vm.channels, key = { _, c -> c.id }) { i, ch ->
                    Surface(
                        onClick = { onSelect(i) },
                        modifier = Modifier.fillMaxWidth().then(if (i == vm.index) Modifier.focusRequester(currentFocus) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (i == vm.index) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ch.num?.toString() ?: "", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                            Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Left/right shifts the sound against the picture in 50 ms steps; OK puts it back to zero. */
@Composable
private fun AudioSyncControl(vm: PlayerViewModel) {
    val ms = vm.audioDelayMs
    Surface(
        onClick = { vm.setAudioDelay(0) },
        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            val step = when (e.nativeKeyEvent.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> -50
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 50
                else -> return@onPreviewKeyEvent false
            }
            if (e.type == KeyEventType.KeyDown) vm.nudgeAudio(step)
            true
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.SurfaceHigh,
            contentColor = AppColors.OnBackground,
            focusedContainerColor = AppColors.OnBackground,
            focusedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, AppColors.Accent), shape = RoundedCornerShape(8.dp))),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sound sync", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "◀  " + when {
                        ms == 0 -> "In sync"
                        ms > 0 -> "${ms} ms later"
                        else -> "${-ms} ms earlier"
                    } + "  ▶",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text("◀ if the voice comes after the lips  ·  ▶ if it comes before  ·  OK to reset", style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun TrackPicker(vm: PlayerViewModel, type: Int, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val version = vm.tracksVersion // recompose when tracks change
    val options = remember(vm.tracksVersion, type) { vm.trackOptions(type) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(type) {
        // The target lives in a LazyColumn, so it may not be composed on the first frame.
        repeat(10) {
            if (firstFocus.tryRequestFocus()) return@LaunchedEffect
            delay(50)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxHeight().width(380.dp).background(AppColors.Surface).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            if (type == C.TRACK_TYPE_AUDIO) {
                AudioSyncControl(vm)
                Spacer(Modifier.height(10.dp))
            }
            if (options.isEmpty()) {
                Text("No tracks available", color = AppColors.Muted)
                if (type != C.TRACK_TYPE_AUDIO) LaunchedEffect(Unit) { delay(1500); onDismiss() }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(options) { i, option ->
                    Surface(
                        onClick = { vm.selectTrack(type, option); onDismiss() },
                        modifier = Modifier.fillMaxWidth().then(
                            if ((option.selected && options.none { it.selected && options.indexOf(it) < i }) || (i == 0 && options.none { it.selected })) Modifier.focusRequester(firstFocus) else Modifier,
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = AppColors.SurfaceHigh,
                            contentColor = AppColors.OnBackground,
                            focusedContainerColor = AppColors.OnBackground,
                            focusedContentColor = Color.Black,
                        ),
                        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, AppColors.Accent), shape = RoundedCornerShape(8.dp))),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (option.selected) "✓" else "", modifier = Modifier.width(24.dp))
                            Text(option.label, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}
