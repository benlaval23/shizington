package com.shizington.tv.data.model

enum class ContentType { LIVE, MOVIE, SERIES, EPISODE }

data class Credentials(val server: String, val username: String, val password: String) {
    /** Identifies the account so local history is not mixed across providers. */
    val accountKey: String get() = "$server|$username"
}

data class AccountInfo(
    val status: String?,
    val expiresAt: Long?,
    val isTrial: Boolean,
    val activeConnections: Int?,
    val maxConnections: Int?,
    val allowedFormats: List<String>,
)

data class Category(val id: String, val name: String)

/** A catalogue entry: a live channel, a movie or a series. */
data class Stream(
    val type: ContentType,
    val id: Long,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val extension: String? = null,
    val rating: String? = null,
    val num: Int? = null,
    val epgChannelId: String? = null,
    val plot: String? = null,
    val backdrop: String? = null,
    val year: String? = null,
    val genre: String? = null,
) {
    val key: String get() = "${type.name}:$id"
}

data class MovieInfo(
    val id: Long,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val duration: String?,
    val extension: String,
    val categoryId: String?,
)

data class SeriesInfo(
    val id: Long,
    val name: String,
    val cover: String?,
    val backdrop: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val seasons: List<Season>,
)

data class Season(val number: Int, val name: String, val cover: String?, val episodes: List<Episode>)

data class Episode(
    val id: Long,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Int?,
    val extension: String,
)

data class EpgEntry(val title: String, val description: String?, val startMs: Long, val endMs: Long) {
    fun progress(now: Long): Float =
        if (endMs <= startMs) 0f else ((now - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

/** Something the player can open. */
data class Playable(
    val type: ContentType,
    val id: Long,
    val name: String,
    val icon: String?,
    val extension: String?,
    val num: Int? = null,
    val seriesId: Long? = null,
    val seriesName: String? = null,
    val seriesCover: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val title: String
        get() = if (type == ContentType.EPISODE && seriesName != null) {
            "$seriesName · S${season ?: 0}E${episode ?: 0}"
        } else name
}

data class PlaybackRequest(val items: List<Playable>, val index: Int, val startPositionMs: Long = 0L)

fun Stream.toPlayable() = Playable(type, id, name, icon, extension, num)

fun formatRating(raw: String?): String? {
    val v = raw?.toDoubleOrNull() ?: return null
    if (v <= 0.0) return null
    return "★ " + if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)
}
