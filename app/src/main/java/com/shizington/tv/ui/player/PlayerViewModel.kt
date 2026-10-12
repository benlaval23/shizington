@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shizington.tv.ui.player

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.shizington.tv.AppContainer
import com.shizington.tv.USER_AGENT
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.EpgEntry
import com.shizington.tv.data.model.Playable
import com.shizington.tv.data.model.PlaybackRequest
import com.shizington.tv.ui.detail.toPlayable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class TrackOption(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

class PlayerViewModel(private val app: Application, private val c: AppContainer, request: PlaybackRequest) : ViewModel() {

    private var syncSink: AudioSyncSink? = null
    val player: ExoPlayer = buildPlayer()

    /** Sound/picture offset in ms (positive = sound later). Remembered, since it's usually the TV or soundbar's delay. */
    var audioDelayMs by mutableIntStateOf(c.credentialsStore.audioDelayMs)
        private set

    init {
        syncSink?.delayUs = audioDelayMs * 1000L
    }

    fun nudgeAudio(deltaMs: Int) = setAudioDelay(audioDelayMs + deltaMs)

    fun setAudioDelay(ms: Int) {
        audioDelayMs = ms.coerceIn(-MAX_AUDIO_DELAY_MS, MAX_AUDIO_DELAY_MS)
        syncSink?.delayUs = audioDelayMs * 1000L
        c.credentialsStore.audioDelayMs = audioDelayMs
    }

    // Episodes advance by replacing the single queue entry; live keeps the whole channel list.
    private val queue = request.items.toMutableStateList()
    var index by mutableIntStateOf(request.index.coerceIn(0, (request.items.size - 1).coerceAtLeast(0)))
        private set
    val current: Playable get() = queue[index]
    /** Catch-up recordings come from a live channel but play like a film (seekable, with an end). */
    val isLive: Boolean get() = current.type == ContentType.LIVE && !current.isCatchUp

    /** The live channel to return to after "Start over". */
    private var liveOrigin: Playable? = null
    val canGoLive: Boolean get() = current.isCatchUp && liveOrigin != null
    val hasQueue: Boolean get() = queue.size > 1

    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(true)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var tracksVersion by mutableIntStateOf(0)
        private set
    var epg by mutableStateOf<List<EpgEntry>>(emptyList())
        private set
    var nextEpisode by mutableStateOf<Playable?>(null)
        private set

    private var retries = 0

    /** Whether the current stream has shown any video; decoder errors after that are glitches, not unsupported formats. */
    private var renderedFirstFrame = false
    private var epgJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    retries = 0
                    error = null
                    durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                }
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onTracksChanged(tracks: Tracks) { tracksVersion++ }

            override fun onRenderedFirstFrame() { renderedFirstFrame = true }

            override fun onPlayerError(e: PlaybackException) = handleError(e)
        })
        play(current, request.startPositionMs)

        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                positionMs = player.currentPosition
                if (!isLive) durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: durationMs
                if (++ticks % 20 == 0 && player.isPlaying) saveProgress()
            }
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val dataSource = OkHttpDataSource.Factory(c.httpClient).setUserAgent(USER_AGENT)
        // Lenient TS parsing copes with the odd streams IPTV panels restream.
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        val renderers = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)?.let { AudioSyncSink(it).also { s -> syncSink = s } }
        }.setEnableDecoderFallback(true)
            // The stick has no Dolby/DTS decoder and many TVs refuse Dolby over HDMI, which left those films silent.
            // With extensions on, FFmpeg decodes whatever the built-in decoders and HDMI passthrough can't.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_000, 4_000).build()
        return ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                playWhenReady = true
                // Subtitles start off (providers often flag a forced/default text track); turn on from the Subtitles button.
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
    }

    // ---- Viewing signals (what was watched and for how long), recorded when an item ends or is switched ----

    private var sessionItem: Playable? = null
    private var sessionWallStart = 0L

    /** Records the item that was playing before the player moves on (or closes). */
    private fun closeSession() {
        val p = sessionItem ?: return
        sessionItem = null
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = player.currentPosition
        val wallStart = sessionWallStart
        val programme = epg.firstOrNull { it.startMs <= System.currentTimeMillis() && it.endMs > System.currentTimeMillis() }?.title
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch {
            when {
                p.type == ContentType.LIVE -> c.library.logLive(p.name, p.id, p.programme ?: programme, wallStart)
                dur > 0 -> c.library.logView(p, pos, dur)
            }
        }
    }

    private fun play(p: Playable, startMs: Long) {
        closeSession()
        sessionItem = p
        sessionWallStart = System.currentTimeMillis()
        renderedFirstFrame = false
        error = null
        epg = emptyList()
        nextEpisode = null
        durationMs = 0
        positionMs = startMs
        val url = c.xtream.streamUrl(p)
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaId("${p.type}:${p.id}")
            .apply { if (url.endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item, startMs)
        player.prepare()
        player.play()
        if (p.type == ContentType.LIVE && !p.isCatchUp) {
            c.lastPlayedLiveId = p.id
            loadEpg(p)
        } else {
            epgJob?.cancel()
        }
        if (p.type == ContentType.EPISODE) findNextEpisode(p)
    }

    private fun loadEpg(p: Playable) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            while (isActive) {
                epg = c.xtream.shortEpg(p.id)
                delay(60_000)
            }
        }
    }

    private fun findNextEpisode(p: Playable) {
        val seriesId = p.seriesId ?: return
        viewModelScope.launch {
            val info = runCatching { c.xtream.seriesInfo(seriesId) }.getOrNull() ?: return@launch
            val all = info.seasons.flatMap { it.episodes }
            val idx = all.indexOfFirst { it.id == p.id }
            if (idx >= 0 && idx + 1 < all.size && current.id == p.id) nextEpisode = all[idx + 1].toPlayable(info)
        }
    }

    private fun onEnded() {
        if (isLive) {
            // A live TS connection that closes is a dropped stream, not the end of the channel.
            player.prepare()
            return
        }
        saveProgress(completed = true)
        nextEpisode?.let { playEpisode(it) }
    }

    fun playEpisode(p: Playable) {
        saveProgress()
        viewModelScope.launch {
            val resume = c.library.getProgress(p.type, p.id)?.takeIf { it.isResumable }?.positionMs ?: 0L
            queue[index] = p
            play(p, resume)
        }
    }

    // ---- Live extras: quality, start over, catch-up ----

    val hasQualityChoice: Boolean get() = isLive && current.variants.size > 1

    /** Cycles through the channel's other qualities (FHD → HD → SD → FHD). */
    fun cycleQuality() {
        val variants = current.variants
        if (variants.size < 2) return
        val next = variants[(variants.indexOfFirst { it.id == current.id } + 1) % variants.size]
        queue[index] = current.copy(id = next.id, quality = next.quality, icon = next.icon ?: current.icon)
        retries = 0
        play(current, 0)
    }

    /** The programme on air now, if the channel records it, so it can be restarted. */
    val startOverProgramme: EpgEntry?
        get() {
            if (!isLive || current.archiveStreamId == null) return null
            val now = System.currentTimeMillis()
            return epg.firstOrNull { it.startMs <= now && it.endMs > now }
        }

    fun startOver() {
        val programme = startOverProgramme ?: return
        val live = current
        liveOrigin = live
        queue[index] = live.copy(
            id = live.archiveStreamId ?: live.id,
            archiveStartMs = programme.startMs,
            archiveMinutes = programme.durationMinutes,
            programme = programme.title,
        )
        retries = 0
        play(current, 0)
    }

    fun goLive() {
        val live = liveOrigin ?: return
        liveOrigin = null
        queue[index] = live
        retries = 0
        play(current, 0)
    }

    private fun handleError(e: PlaybackException) {
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        if (e.errorCode in DECODER_ERRORS && !renderedFirstFrame) {
            // Retrying can't help: this device has no decoder for the stream's format (often HEVC on older hardware).
            error = "This video's format isn't supported on this device"
            return
        }
        val maxRetries = if (isLive) 5 else 3
        if (retries < maxRetries) {
            retries++
            val resumeAt = player.currentPosition
            viewModelScope.launch {
                delay(1500L * retries)
                if (isLive) player.seekToDefaultPosition() else player.seekTo(resumeAt)
                player.prepare()
            }
        } else {
            error = describe(e)
        }
    }

    /** Turns the provider's HTTP refusals into something a viewer can act on. */
    private fun describe(e: PlaybackException): String {
        var cause: Throwable? = e
        while (cause != null && cause !is HttpDataSource.InvalidResponseCodeException) cause = cause.cause
        val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val max = c.xtream.account.value?.maxConnections
        return when (code) {
            401, 403, 405, 429, 456, 458, 509 ->
                "The provider refused this stream. Your plan allows ${max ?: "a limited number of"} " +
                    "connection${if (max == 1) "" else "s"} at a time. Stop playback on other devices and try again."
            404 -> "This stream isn't available right now."
            else -> "Playback failed: ${e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()}"
        }
    }

    fun retry() {
        retries = 0
        play(current, if (isLive) 0 else player.currentPosition)
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        if (isLive) return
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0, (dur - 1000).coerceAtLeast(0))
        player.seekTo(target)
        positionMs = target
    }

    /** Channel up/down on live; wraps around the channel list. */
    fun changeChannel(delta: Int) {
        if (!isLive || queue.size < 2) return
        index = Math.floorMod(index + delta, queue.size)
        retries = 0
        play(current, 0)
    }

    fun jumpToChannel(i: Int) {
        if (!isLive || i !in queue.indices) return
        index = i
        retries = 0
        play(current, 0)
    }

    val channels: List<Playable> get() = queue

    // ---- Tracks ----

    fun trackOptions(type: Int): List<TrackOption> {
        val options = mutableListOf<TrackOption>()
        val groups = player.currentTracks.groups.filter { it.type == type }
        val anySelected = groups.any { it.isSelected }
        if (type == C.TRACK_TYPE_TEXT) {
            options += TrackOption("Off", null, -1, !anySelected || player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        }
        for (g in groups) {
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val textOff = type == C.TRACK_TYPE_TEXT && player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                options += TrackOption(describe(g.getTrackFormat(i), options.size + 1), g, i, g.isTrackSelected(i) && !textOff)
            }
        }
        return options
    }

    fun selectTrack(type: Int, option: TrackOption) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (option.group == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun describe(f: Format, n: Int): String {
        val language = f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
        val channels = when (f.channelCount) {
            Format.NO_VALUE, 0 -> null
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${f.channelCount}ch"
        }
        val codec = f.sampleMimeType?.substringAfter('/')?.uppercase()?.removePrefix("X-")
        val parts = listOfNotNull(f.label, language, channels, codec).distinct()
        return if (parts.isEmpty()) "Track $n" else parts.joinToString(" · ")
    }

    // ---- Progress ----

    fun saveProgress(completed: Boolean = false) {
        val p = current
        if (p.type != ContentType.MOVIE && p.type != ContentType.EPISODE) return
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val pos = if (completed) dur else player.currentPosition
        viewModelScope.launch { c.library.saveProgress(p, pos, dur) }
    }

    fun onBackground() {
        saveProgress()
        player.pause()
    }

    override fun onCleared() {
        closeSession()
        val p = current
        val dur = player.duration
        val pos = player.currentPosition
        if (dur != C.TIME_UNSET && dur > 0 && (p.type == ContentType.MOVIE || p.type == ContentType.EPISODE)) {
            // viewModelScope is already cancelled here; write on the app-wide scope.
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch { c.library.saveProgress(p, pos, dur) }
        }
        player.release()
        super.onCleared()
    }

    private companion object {
        const val MAX_AUDIO_DELAY_MS = 2_000
        val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }
}
