package com.shizington.tv.data

import com.shizington.tv.data.db.AppDatabase
import com.shizington.tv.data.db.FavouriteEntity
import com.shizington.tv.data.db.ProgressEntity
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.Playable
import com.shizington.tv.data.model.Stream
import kotlinx.coroutines.flow.Flow
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

    suspend fun clearAll() {
        db.favourites().clear()
        db.progress().clear()
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
