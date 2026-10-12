package com.shizington.tv.data.catalog

import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.Stream
import java.text.Normalizer

/**
 * Looks provider titles up by normalised title + year, so TMDB lists (trending, releases,
 * recommendations) can be turned into things the provider actually carries without one
 * request per film. Remakes share titles, so the year decides when there's a choice.
 */
class CatalogIndex(streams: List<Stream>) {
    private val byTitle: Map<String, List<Stream>> = streams.groupBy { normalize(it.name) }

    val size: Int get() = byTitle.size

    fun find(title: String?, year: Int?, originalTitle: String? = null): Stream? {
        val candidates = listOfNotNull(title, originalTitle)
            .flatMap { byTitle[normalize(it)].orEmpty() }
            .distinctBy { it.id }
        if (candidates.isEmpty()) return null
        if (year == null) return candidates.maxByOrNull { it.added }
        // Release years drift by one between TMDB and providers (festival vs general release).
        return candidates
            .filter { s -> s.year?.toIntOrNull()?.let { kotlin.math.abs(it - year) <= 1 } ?: true }
            .minByOrNull { s -> s.year?.toIntOrNull()?.let { kotlin.math.abs(it - year) } ?: 2 }
    }

    companion object {
        private val yearSuffix = Regex("""\(\d{4}\)""")
        private val nonAlnum = Regex("[^a-z0-9]+")

        fun normalize(title: String): String {
            val ascii = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            return ascii.lowercase()
                .replace(yearSuffix, "")
                .replace("&", " and ")
                .replace(nonAlnum, " ")
                .trim()
        }
    }
}

/** Both catalogues, indexed once per session. */
class Catalog(val movies: List<Stream>, val series: List<Stream>) {
    val movieIndex = CatalogIndex(movies)
    val seriesIndex = CatalogIndex(series)
    val movieById: Map<Long, Stream> = movies.associateBy { it.id }
    val seriesById: Map<Long, Stream> = series.associateBy { it.id }

    fun index(type: ContentType) = if (type == ContentType.SERIES) seriesIndex else movieIndex
}
