package com.shizington.tv.data

import com.shizington.tv.data.db.AppDatabase
import com.shizington.tv.data.db.FavouriteEntity
import com.shizington.tv.data.db.ProgressEntity
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.Playable
import com.shizington.tv.data.model.Stream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Local, per-device library: favourites and watch progress (continue watching). */
class LibraryRepository(private val db: AppDatabase) {

    val favourites: Flow<List<Stream>> = db.favourites().observeAll().map { list ->
        list.map {
            Stream(
                type = ContentType.valueOf(it.type),
                id = it.contentId,
                name = it.name,
                icon = it.icon,
                categoryId = it.categoryId,
                extension = it.extension,
                num = it.num,
                epgChannelId = it.epgChannelId,
            )
        }
    }

    fun isFavourite(type: ContentType, id: Long): Flow<Boolean> = db.favourites().observeIsFavourite(type.name, id)

    suspend fun setFavourite(stream: Stream, favourite: Boolean) {
        if (favourite) {
            db.favourites().insert(
                FavouriteEntity(
                    type = stream.type.name,
                    contentId = stream.id,
                    name = stream.name,
                    icon = stream.icon,
                    extension = stream.extension,
                    categoryId = stream.categoryId,
                    num = stream.num,
                    epgChannelId = stream.epgChannelId,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        } else {
            db.favourites().delete(stream.type.name, stream.id)
        }
    }

    /** In-progress movies and the most recent in-progress episode per series. */
    val continueWatching: Flow<List<ProgressEntity>> = db.progress().observeRecent().map { list ->
        list.filter { it.isResumable }.distinctBy { it.seriesId?.let { s -> "S$s" } ?: "M${it.contentId}" }
    }

    fun progress(type: ContentType, id: Long): Flow<ProgressEntity?> = db.progress().observe(type.name, id)

    fun seriesProgress(seriesId: Long): Flow<List<ProgressEntity>> = db.progress().observeForSeries(seriesId)

    suspend fun getProgress(type: ContentType, id: Long): ProgressEntity? = db.progress().get(type.name, id)

    suspend fun saveProgress(p: Playable, positionMs: Long, durationMs: Long) {
        if (p.type != ContentType.MOVIE && p.type != ContentType.EPISODE) return
        if (durationMs <= 0) return
        db.progress().upsert(
            ProgressEntity(
                type = p.type.name,
                contentId = p.id,
                name = p.name,
                icon = p.icon,
                extension = p.extension,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
                seriesId = p.seriesId,
                seriesName = p.seriesName,
                seriesCover = p.seriesCover,
                season = p.season,
                episode = p.episode,
            ),
        )
    }

    suspend fun removeProgress(type: ContentType, id: Long) = db.progress().delete(type.name, id)

    // ---- Signals for recommendations (kept on the device) ----

    suspend fun logView(p: Playable, watchedMs: Long, durationMs: Long) {
        if (p.type != ContentType.MOVIE && p.type != ContentType.EPISODE) return
        if (watchedMs < 60_000L && durationMs > 0 && watchedMs < durationMs * 0.9) {
            // Under a minute: browsing, not a signal, unless it was a very short item.
            if (watchedMs < 20_000L) return
        }
        val now = java.util.Calendar.getInstance()
        db.signals().insertView(
            com.shizington.tv.data.db.ViewEventEntity(
                type = p.type.name,
                contentId = p.id,
                title = p.seriesName ?: p.name,
                seriesId = p.seriesId,
                genres = null,
                people = null,
                startedAt = System.currentTimeMillis() - watchedMs,
                watchedMs = watchedMs,
                durationMs = durationMs,
                hourOfDay = now.get(java.util.Calendar.HOUR_OF_DAY),
                dayOfWeek = now.get(java.util.Calendar.DAY_OF_WEEK),
            ),
        )
    }

    suspend fun logLive(channelName: String, streamId: Long, programme: String?, startedAt: Long) {
        val minutes = ((System.currentTimeMillis() - startedAt) / 60_000L).toInt()
        if (minutes < 1) return
        db.signals().insertLive(
            com.shizington.tv.data.db.LiveSessionEntity(
                channelName = channelName,
                streamId = streamId,
                programme = programme,
                startedAt = startedAt,
                minutes = minutes,
                hourOfDay = java.util.Calendar.getInstance().apply { timeInMillis = startedAt }.get(java.util.Calendar.HOUR_OF_DAY),
            ),
        )
    }

    /** +1 = "More like this", -1 = "Not for me", 0 clears it. */
    suspend fun setFeedback(stream: Stream, value: Int) {
        if (value == 0) {
            db.signals().deleteFeedback(stream.type.name, stream.id)
        } else {
            db.signals().upsertFeedback(
                com.shizington.tv.data.db.FeedbackEntity(stream.type.name, stream.id, stream.name, value, stream.genre, stream.people, System.currentTimeMillis()),
            )
        }
    }

    val feedback: Flow<List<com.shizington.tv.data.db.FeedbackEntity>> = db.signals().observeFeedback()

    suspend fun signalsSnapshot() = Signals(
        views = db.signals().recentViews(),
        feedback = db.signals().allFeedback(),
        live = db.signals().recentLive(),
        progress = db.progress().observeRecent().first(),
        favourites = favourites.first(),
    )

    val watchlist: Flow<List<com.shizington.tv.data.db.WatchlistEntity>> = db.tmdb().observeWatchlist()

    suspend fun addToWatchlist(e: com.shizington.tv.data.db.WatchlistEntity) = db.tmdb().addWatchlist(e)
    suspend fun removeFromWatchlist(key: String) = db.tmdb().removeWatchlist(key)

    suspend fun clearAll() {
        db.favourites().clear()
        db.progress().clear()
        db.signals().clearViews()
        db.signals().clearFeedback()
        db.signals().clearLive()
    }
}

fun ProgressEntity.toPlayable() = Playable(
    type = ContentType.valueOf(type),
    id = contentId,
    name = name,
    icon = icon,
    extension = extension,
    seriesId = seriesId,
    seriesName = seriesName,
    seriesCover = seriesCover,
    season = season,
    episode = episode,
)

/** Everything the recommender learns from, read in one go. */
data class Signals(
    val views: List<com.shizington.tv.data.db.ViewEventEntity>,
    val feedback: List<com.shizington.tv.data.db.FeedbackEntity>,
    val live: List<com.shizington.tv.data.db.LiveSessionEntity>,
    val progress: List<ProgressEntity>,
    val favourites: List<Stream>,
)
