package com.shizington.tv.data.tmdb

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.shizington.tv.BuildConfig
import com.shizington.tv.data.api.asArr
import com.shizington.tv.data.api.asObj
import com.shizington.tv.data.api.str
import com.shizington.tv.data.db.TmdbCacheEntity
import com.shizington.tv.data.db.TmdbDao
import com.shizington.tv.data.db.TmdbMapEntity
import com.shizington.tv.data.model.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate

/**
 * TMDB access with a Room-backed cache. Everything degrades to empty results when there's no token or
 * no network, so TMDB only ever adds to the app. Region is the UK (release dates, ratings, trending).
 */
class TmdbRepository(private val http: OkHttpClient, private val dao: TmdbDao) {
    val enabled: Boolean get() = BuildConfig.TMDB_TOKEN.isNotBlank()

    private val permits = Semaphore(4)

    // ---- Lists ----

    suspend fun trending(type: ContentType, window: String = "day"): List<TmdbTitle> =
        list("trending/${path(type)}/$window", emptyMap(), type, LIST_TTL)

    /** Films that came out on digital/home release in the UK recently, most popular first. */
    suspend fun ukDigitalReleases(days: Long = 60, pages: Int = 3): List<TmdbTitle> {
        val today = LocalDate.now()
        return (1..pages).flatMap { page ->
            list(
                "discover/movie",
                mapOf(
                    "region" to REGION,
                    "with_release_type" to "4|5",
                    "release_date.gte" to today.minusDays(days).toString(),
                    "release_date.lte" to today.toString(),
                    "sort_by" to "popularity.desc",
                    "vote_count.gte" to "10",
                    "page" to page.toString(),
                ),
                ContentType.MOVIE, LIST_TTL,
            )
        }.distinctBy { it.id }
    }

    /** Upcoming UK cinema/digital releases (not out yet). */
    suspend fun upcomingMovies(pages: Int = 2): List<TmdbTitle> {
        val today = LocalDate.now().toString()
        return (1..pages).flatMap { page ->
            list("movie/upcoming", mapOf("region" to REGION, "page" to page.toString()), ContentType.MOVIE, LIST_TTL)
        }.filter { (it.releaseDate ?: "") > today }.distinctBy { it.id }.sortedBy { it.releaseDate }
    }

    /** Highly rated titles that relatively few people have rated. */
    suspend fun hiddenGems(type: ContentType, pages: Int = 3): List<TmdbTitle> = (1..pages).flatMap { page ->
        list(
            "discover/${path(type)}",
            mapOf(
                "vote_average.gte" to "7.3",
                "vote_count.gte" to "150",
                "vote_count.lte" to "2500",
                "sort_by" to "vote_average.desc",
                "page" to page.toString(),
            ),
            type, DETAIL_TTL,
        )
    }.distinctBy { it.id }

    suspend fun recommendations(type: ContentType, id: Long): List<TmdbTitle> =
        (list("${path(type)}/$id/recommendations", emptyMap(), type, DETAIL_TTL) +
            list("${path(type)}/$id/similar", emptyMap(), type, DETAIL_TTL)).distinctBy { it.id }

    // ---- Details ----

    suspend fun details(type: ContentType, id: Long): TmdbDetails? {
        val append = if (type == ContentType.SERIES) "images,content_ratings,keywords,videos,credits" else "images,release_dates,keywords,videos,credits"
        val o = get("${path(type)}/$id", mapOf("append_to_response" to append, "include_image_language" to "en,null"), DETAIL_TTL) ?: return null
        val images = o.get("images").asObj()
        val logo = images?.get("logos").asArr()?.objects()
            ?.filter { it.str("iso_639_1").let { l -> l == null || l == "en" } }
            ?.maxWithOrNull(compareBy<JsonObject>({ if (it.str("iso_639_1") == "en") 1 else 0 }, { it.dbl("vote_average") }))
            ?.str("file_path")
        val backdrop = images?.get("backdrops").asArr()?.objects()
            ?.sortedWith(compareBy<JsonObject>({ if (it.str("iso_639_1") == null) 0 else 1 }, { -it.dbl("vote_average") }))
            ?.firstOrNull()?.str("file_path") ?: o.str("backdrop_path")
        val cert = if (type == ContentType.SERIES) {
            o.get("content_ratings").asObj()?.get("results").asArr()?.objects()
                ?.firstOrNull { it.str("iso_3166_1") == REGION }?.str("rating")
        } else {
            o.get("release_dates").asObj()?.get("results").asArr()?.objects()
                ?.firstOrNull { it.str("iso_3166_1") == REGION }
                ?.get("release_dates").asArr()?.objects()?.firstNotNullOfOrNull { it.str("certification") }
        }
        val keywords = (o.get("keywords").asObj()?.let { it.get("keywords") ?: it.get("results") }).asArr()?.objects()
            ?.mapNotNull { it.str("name") }.orEmpty()
        val trailer = o.get("videos").asObj()?.get("results").asArr()?.objects()
            ?.filter { it.str("site") == "YouTube" && it.str("type") in setOf("Trailer", "Teaser") }
            ?.sortedWith(compareBy({ if (it.str("type") == "Trailer") 0 else 1 }, { if (it.get("official")?.asBoolean == true) 0 else 1 }))
            ?.firstOrNull()?.str("key")
        val credits = o.get("credits").asObj()
        val cast = credits?.get("cast").asArr()?.objects()?.take(15)?.mapNotNull { c ->
            TmdbPerson(c.long("id") ?: return@mapNotNull null, c.str("name") ?: return@mapNotNull null, c.str("character"), TmdbImages.url(c.str("profile_path"), "w185"))
        }.orEmpty()
        val director = credits?.get("crew").asArr()?.objects()?.firstOrNull { it.str("job") == "Director" }?.str("name")
            ?: o.get("created_by").asArr()?.objects()?.mapNotNull { it.str("name") }?.joinToString(", ")?.takeIf { it.isNotBlank() }
        val collection = o.get("belongs_to_collection").asObj()
        val next = o.get("next_episode_to_air").asObj()
        return TmdbDetails(
            id = id,
            type = type,
            title = o.str("title", "name").orEmpty(),
            tagline = o.str("tagline"),
            logo = TmdbImages.url(logo, "w500"),
            backdrop = TmdbImages.url(backdrop, "w1280"),
            certification = cert,
            keywords = keywords,
            trailerKey = trailer,
            cast = cast,
            director = director,
            collectionId = collection?.long("id"),
            collectionName = collection?.str("name"),
            runtime = o.int("runtime") ?: o.get("episode_run_time").asArr()?.firstOrNull()?.let { runCatching { it.asInt }.getOrNull() },
            vote = o.dbl("vote_average"),
            votes = o.int("vote_count") ?: 0,
            status = o.str("status"),
            network = o.get("networks").asArr()?.objects()?.firstOrNull()?.str("name"),
            lastAirDate = o.str("last_air_date"),
            nextEpisodeDate = next?.str("air_date"),
            nextEpisodeLabel = next?.let { "S${it.int("season_number") ?: 0}E${it.int("episode_number") ?: 0}" },
            numberOfSeasons = o.int("number_of_seasons"),
        )
    }

    suspend fun collection(id: Long): TmdbCollection? {
        val o = get("collection/$id", emptyMap(), DETAIL_TTL) ?: return null
        val parts = o.get("parts").asArr()?.objects()?.mapNotNull { parse(it, ContentType.MOVIE) }.orEmpty()
            .sortedBy { it.releaseDate ?: "9999" }
        return TmdbCollection(id, o.str("name").orEmpty(), parts)
    }

    suspend fun person(id: Long): TmdbPersonDetails? {
        val o = get("person/$id", mapOf("append_to_response" to "combined_credits"), DETAIL_TTL) ?: return null
        val cc = o.get("combined_credits").asObj()
        val directing = cc?.get("crew").asArr()?.objects().orEmpty().filter { it.str("job") == "Director" }
        val directedIds = directing.mapNotNull { it.long("id") }.toSet()
        val credits = (cc?.get("cast").asArr()?.objects().orEmpty() + directing).mapNotNull { c ->
            val type = if (c.str("media_type") == "tv") ContentType.SERIES else ContentType.MOVIE
            parse(c, type)
        }.distinctBy { "${it.type}:${it.id}" }.sortedByDescending { it.popularity }
        return TmdbPersonDetails(
            id = id,
            name = o.str("name").orEmpty(),
            biography = o.str("biography"),
            photo = TmdbImages.url(o.str("profile_path"), "w342"),
            knownFor = o.str("known_for_department"),
            credits = credits,
            directedIds = directedIds,
        )
    }

    /** Actors and directors matching a name, most famous first. */
    suspend fun searchPeople(query: String): List<TmdbPerson> {
        if (!enabled || query.trim().length < 3) return emptyList()
        return get("search/person", mapOf("query" to query.trim()), LIST_TTL)?.get("results").asArr()?.objects().orEmpty()
            .filter { it.str("known_for_department") in setOf("Acting", "Directing") && (it.dbl("popularity")) >= 1.0 }
            .mapNotNull { o ->
                TmdbPerson(
                    id = o.long("id") ?: return@mapNotNull null,
                    name = o.str("name") ?: return@mapNotNull null,
                    role = o.str("known_for_department")?.let { if (it == "Directing") "Director" else "Actor" },
                    photo = TmdbImages.url(o.str("profile_path"), "w185"),
                )
            }.take(12)
    }

    /** Matches a provider title to TMDB once and remembers the answer (including "no match"). */
    suspend fun findId(type: ContentType, contentId: Long, title: String, year: Int?): Long? {
        if (!enabled) return null
        dao.mapping(type.name, contentId)?.let { m ->
            if (m.tmdbId > 0) return m.tmdbId
            if (System.currentTimeMillis() - m.matchedAt < 30L * DAY) return null
        }
        val params = buildMap {
            put("query", title)
            if (year != null) put(if (type == ContentType.SERIES) "first_air_date_year" else "year", year.toString())
        }
        val results = get("search/${path(type)}", params, DETAIL_TTL)?.get("results").asArr()?.objects().orEmpty()
        val norm = com.shizington.tv.data.catalog.CatalogIndex.normalize(title)
        val best = results.firstOrNull { r ->
            listOfNotNull(r.str("title", "name"), r.str("original_title", "original_name"))
                .any { com.shizington.tv.data.catalog.CatalogIndex.normalize(it) == norm }
        } ?: results.firstOrNull()
        val id = best?.long("id") ?: 0L
        dao.putMapping(TmdbMapEntity(type.name, contentId, id, System.currentTimeMillis()))
        return id.takeIf { it > 0 }
    }

    suspend fun rememberId(type: ContentType, contentId: Long, tmdbId: Long) =
        dao.putMapping(TmdbMapEntity(type.name, contentId, tmdbId, System.currentTimeMillis()))

    suspend fun rememberIds(ids: List<Triple<ContentType, Long, Long>>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.putMappings(ids.map { (type, contentId, tmdbId) -> TmdbMapEntity(type.name, contentId, tmdbId, now) })
    }

    suspend fun prune() = dao.pruneCache(System.currentTimeMillis() - 14 * DAY)

    // ---- Plumbing ----

    private suspend fun list(p: String, params: Map<String, String>, type: ContentType, ttl: Long): List<TmdbTitle> =
        get(p, params, ttl)?.get("results").asArr()?.objects()?.mapNotNull { o ->
            val t = when (o.str("media_type")) {
                "tv" -> ContentType.SERIES
                "movie" -> ContentType.MOVIE
                "person" -> return@mapNotNull null
                else -> type
            }
            parse(o, t)
        }.orEmpty()

    private fun parse(o: JsonObject, type: ContentType): TmdbTitle? {
        val id = o.long("id") ?: return null
        val date = o.str("release_date", "first_air_date")
        return TmdbTitle(
            id = id,
            type = type,
            title = o.str("title", "name") ?: return null,
            originalTitle = o.str("original_title", "original_name"),
            year = date?.take(4)?.toIntOrNull(),
            releaseDate = date,
            overview = o.str("overview"),
            poster = TmdbImages.url(o.str("poster_path"), "w342"),
            backdrop = TmdbImages.url(o.str("backdrop_path"), "w1280"),
            vote = o.dbl("vote_average"),
            votes = o.int("vote_count") ?: 0,
            popularity = o.dbl("popularity"),
            genreIds = o.get("genre_ids").asArr()?.mapNotNull { runCatching { it.asInt }.getOrNull() }.orEmpty(),
        )
    }

    private suspend fun get(path: String, params: Map<String, String>, ttl: Long): JsonObject? {
        if (!enabled) return null
        val key = path + "?" + params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val cached = dao.cached(key)
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < ttl) return parseObj(cached.json)
        val fresh = permits.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder().apply {
                        addQueryParameter("language", "en-GB")
                        params.forEach { (k, v) -> addQueryParameter(k, v) }
                    }.build()
                    val req = Request.Builder().url(url).header("Authorization", "Bearer ${BuildConfig.TMDB_TOKEN}").build()
                    http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                }.getOrNull()
            }
        }
        if (fresh != null) {
            dao.putCache(TmdbCacheEntity(key, fresh, System.currentTimeMillis()))
            return parseObj(fresh)
        }
        // Offline or rate limited: stale data beats nothing.
        return cached?.let { parseObj(it.json) }
    }

    private fun parseObj(json: String): JsonObject? = runCatching { JsonParser.parseString(json).asObj() }.getOrNull()

    private fun path(type: ContentType) = if (type == ContentType.SERIES) "tv" else "movie"

    companion object {
        const val REGION = "GB"
        private const val DAY = 86_400_000L
        private const val LIST_TTL = 6 * 3_600_000L
        private const val DETAIL_TTL = 7 * DAY
    }
}

private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it.asObj() }
private fun JsonObject.long(k: String): Long? = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
private fun JsonObject.int(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
private fun JsonObject.dbl(k: String): Double = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
@Suppress("unused") private fun JsonElement?.isNull() = this == null || this.isJsonNull
