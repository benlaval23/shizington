package com.shizington.tv.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2 adds viewing signals, feedback, live sessions and TMDB caches; favourites and progress are untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `view_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `contentId` INTEGER NOT NULL, `title` TEXT NOT NULL, `seriesId` INTEGER, `genres` TEXT, `people` TEXT, `startedAt` INTEGER NOT NULL, `watchedMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `hourOfDay` INTEGER NOT NULL, `dayOfWeek` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_view_events_type_contentId` ON `view_events` (`type`, `contentId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `feedback` (`type` TEXT NOT NULL, `contentId` INTEGER NOT NULL, `title` TEXT NOT NULL, `value` INTEGER NOT NULL, `genres` TEXT, `people` TEXT, `at` INTEGER NOT NULL, PRIMARY KEY(`type`, `contentId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `live_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channelName` TEXT NOT NULL, `streamId` INTEGER NOT NULL, `programme` TEXT, `startedAt` INTEGER NOT NULL, `minutes` INTEGER NOT NULL, `hourOfDay` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tmdb_cache` (`key` TEXT NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tmdb_map` (`type` TEXT NOT NULL, `contentId` INTEGER NOT NULL, `tmdbId` INTEGER NOT NULL, `matchedAt` INTEGER NOT NULL, PRIMARY KEY(`type`, `contentId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `watchlist` (`key` TEXT NOT NULL, `tmdbId` INTEGER NOT NULL, `mediaType` TEXT NOT NULL, `title` TEXT NOT NULL, `year` INTEGER, `poster` TEXT, `releaseDate` TEXT, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
    }
}
