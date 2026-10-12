package com.shizington.tv.data.tmdb

import com.shizington.tv.data.model.ContentType

/** A film or series as TMDB lists it (trending, releases, recommendations…). */
data class TmdbTitle(
    val id: Long,
    val type: ContentType, // MOVIE or SERIES
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val releaseDate: String?,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val vote: Double,
    val votes: Int,
    val popularity: Double,
    val genreIds: List<Int> = emptyList(),
)

data class TmdbPerson(val id: Long, val name: String, val role: String?, val photo: String?)

data class TmdbDetails(
    val id: Long,
    val type: ContentType,
    val title: String,
    val tagline: String?,
    /** Transparent title artwork, shown instead of plain text in banners. */
    val logo: String?,
    /** Backdrop without text, at banner resolution. */
    val backdrop: String?,
    /** UK age rating (BBFC for films, broadcaster rating for TV). */
    val certification: String?,
    val keywords: List<String>,
    val trailerKey: String?,
    val cast: List<TmdbPerson>,
    val director: String?,
    val collectionId: Long?,
    val collectionName: String?,
    val runtime: Int?,
    val vote: Double,
    val votes: Int,
    val status: String?,
    val network: String?,
    val lastAirDate: String?,
    val nextEpisodeDate: String?,
    val nextEpisodeLabel: String?,
    val numberOfSeasons: Int?,
)

data class TmdbCollection(val id: Long, val name: String, val parts: List<TmdbTitle>)

data class TmdbPersonDetails(
    val id: Long,
    val name: String,
    val biography: String?,
    val photo: String?,
    val knownFor: String?,
    val credits: List<TmdbTitle>,
    val directedIds: Set<Long> = emptySet(),
)

object TmdbImages {
    private const val BASE = "https://image.tmdb.org/t/p/"
    fun url(path: String?, size: String): String? = path?.takeIf { it.isNotBlank() }?.let { BASE + size + it }
}
