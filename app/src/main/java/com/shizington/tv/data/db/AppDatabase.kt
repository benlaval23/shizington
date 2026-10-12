package com.shizington.tv.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favourites", primaryKeys = ["type", "contentId"])
data class FavouriteEntity(
    val type: String,
    val contentId: Long,
    val name: String,
    val icon: String?,
    val extension: String?,
    val categoryId: String?,
    val num: Int?,
    val epgChannelId: String?,
    val addedAt: Long,
)

@Entity(tableName = "progress", primaryKeys = ["type", "contentId"])
data class ProgressEntity(
    val type: String,
    val contentId: Long,
    val name: String,
    val icon: String?,
    val extension: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val seriesId: Long?,
    val seriesName: String?,
    val seriesCover: String?,
    val season: Int?,
    val episode: Int?,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val isCompleted: Boolean get() = durationMs > 0 && positionMs >= durationMs * COMPLETED_FRACTION
    val isResumable: Boolean get() = positionMs > MIN_RESUME_MS && !isCompleted

    companion object {
        const val COMPLETED_FRACTION = 0.94
        const val MIN_RESUME_MS = 30_000L
    }
}

@Dao
interface FavouriteDao {
    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavouriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE type = :type AND contentId = :id)")
    fun observeIsFavourite(type: String, id: Long): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE type = :type AND contentId = :id")
    suspend fun delete(type: String, id: Long)

    @Query("DELETE FROM favourites")
    suspend fun clear()
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT 300")
    fun observeRecent(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE type = :type AND contentId = :id")
    fun observe(type: String, id: Long): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE type = :type AND contentId = :id")
    suspend fun get(type: String, id: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE seriesId = :seriesId ORDER BY updatedAt DESC")
    fun observeForSeries(seriesId: Long): Flow<List<ProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProgressEntity)

    @Query("DELETE FROM progress WHERE type = :type AND contentId = :id")
    suspend fun delete(type: String, id: Long)

    @Query("DELETE FROM progress")
    suspend fun clear()
}

@Database(entities = [FavouriteEntity::class, ProgressEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favourites(): FavouriteDao
    abstract fun progress(): ProgressDao
}
