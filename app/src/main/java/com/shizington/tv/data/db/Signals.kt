package com.shizington.tv.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One viewing of a movie or episode: what was watched, how much, and when. Feeds the taste profile. */
@Entity(tableName = "view_events", indices = [Index("type", "contentId")])
data class ViewEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val contentId: Long,
    val title: String,
    val seriesId: Long?,
    val genres: String?,
    val people: String?,
    val startedAt: Long,
    val watchedMs: Long,
    val durationMs: Long,
    val hourOfDay: Int,
    val dayOfWeek: Int,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (watchedMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** Explicit taste: +1 "More like this", -1 "Not for me". */
@Entity(tableName = "feedback", primaryKeys = ["type", "contentId"])
data class FeedbackEntity(
    val type: String,
    val contentId: Long,
    val title: String,
    val value: Int,
    val genres: String?,
    val people: String?,
    val at: Long,
)

/** Time spent on a live channel (grouped name), so recommendations know what you actually watch live. */
@Entity(tableName = "live_sessions")
data class LiveSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelName: String,
    val streamId: Long,
    val programme: String?,
    val startedAt: Long,
    val minutes: Int,
    val hourOfDay: Int,
)

/** Raw TMDB responses, cached with their fetch time so the stick makes few requests. */
@Entity(tableName = "tmdb_cache")
data class TmdbCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long,
)

/** Provider title -> TMDB id (0 when TMDB had no confident match). */
@Entity(tableName = "tmdb_map", primaryKeys = ["type", "contentId"])
data class TmdbMapEntity(
    val type: String,
    val contentId: Long,
    val tmdbId: Long,
    val matchedAt: Long,
)

/** "Coming soon" titles saved to My List before the provider has them. */
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val key: String,
    val tmdbId: Long,
    val mediaType: String,
    val title: String,
    val year: Int?,
    val poster: String?,
    val releaseDate: String?,
    val addedAt: Long,
)

@Dao
interface SignalsDao {
    @Insert
    suspend fun insertView(e: ViewEventEntity)

    @Query("SELECT * FROM view_events ORDER BY startedAt DESC LIMIT 500")
    suspend fun recentViews(): List<ViewEventEntity>

    @Query("SELECT * FROM view_events ORDER BY startedAt DESC LIMIT 500")
    fun observeRecentViews(): Flow<List<ViewEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFeedback(e: FeedbackEntity)

    @Query("DELETE FROM feedback WHERE type = :type AND contentId = :id")
    suspend fun deleteFeedback(type: String, id: Long)

    @Query("SELECT * FROM feedback")
    suspend fun allFeedback(): List<FeedbackEntity>

    @Query("SELECT * FROM feedback")
    fun observeFeedback(): Flow<List<FeedbackEntity>>

    @Insert
    suspend fun insertLive(e: LiveSessionEntity)

    @Query("SELECT * FROM live_sessions ORDER BY startedAt DESC LIMIT 1000")
    suspend fun recentLive(): List<LiveSessionEntity>

    @Query("DELETE FROM view_events")
    suspend fun clearViews()

    @Query("DELETE FROM feedback")
    suspend fun clearFeedback()

    @Query("DELETE FROM live_sessions")
    suspend fun clearLive()
}

@Dao
interface TmdbDao {
    @Query("SELECT * FROM tmdb_cache WHERE `key` = :key")
    suspend fun cached(key: String): TmdbCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCache(e: TmdbCacheEntity)

    @Query("DELETE FROM tmdb_cache WHERE fetchedAt < :before")
    suspend fun pruneCache(before: Long)

    @Query("SELECT * FROM tmdb_map WHERE type = :type AND contentId = :id")
    suspend fun mapping(type: String, id: Long): TmdbMapEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMapping(e: TmdbMapEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMappings(e: List<TmdbMapEntity>)

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun observeWatchlist(): Flow<List<WatchlistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addWatchlist(e: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE `key` = :key")
    suspend fun removeWatchlist(key: String)
}
