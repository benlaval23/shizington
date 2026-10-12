package com.shizington.tv.data

import android.net.Uri
import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.shizington.tv.data.api.JsonUtil
import com.shizington.tv.data.api.XtreamApi
import com.shizington.tv.data.api.asArr
import com.shizington.tv.data.api.asObj
import com.shizington.tv.data.api.nullIfBlank
import com.shizington.tv.data.api.str
import com.shizington.tv.data.api.toLongId
import com.shizington.tv.data.model.AccountInfo
import com.shizington.tv.data.model.Category
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.Credentials
import com.shizington.tv.data.model.EpgEntry
import com.shizington.tv.data.model.Episode
import com.shizington.tv.data.model.MovieInfo
import com.shizington.tv.data.model.Playable
import com.shizington.tv.data.model.Season
import com.shizington.tv.data.model.SeriesInfo
import com.shizington.tv.data.model.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class AuthException(message: String) : Exception(message)

/** Memoises suspend loads per key; concurrent callers for the same key share one request. */
private class AsyncCache<K : Any, V : Any> {
    private val values = ConcurrentHashMap<K, V>()
    private val locks = ConcurrentHashMap<K, Mutex>()

    fun peek(key: K): V? = values[key]

    suspend fun get(key: K, loader: suspend () -> V): V {
        values[key]?.let { return it }
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock { values[key] ?: loader().also { values[key] = it } }
    }

    fun clear() {
        values.clear()
        locks.clear()
    }
}

class XtreamRepository(
    private val httpClient: OkHttpClient,
    private val store: CredentialsStore,
) {
    @Volatile
    var credentials: Credentials? = null
        private set

    /** One client per server (primary first, then backups). */
    @Volatile
    private var apis: List<Pair<String, XtreamApi>> = emptyList()

    /** Index of the server that last answered; requests start there. */
    @Volatile
    private var activeIndex = 0

    /** The server currently in use, which stream URLs are built from too. */
    val activeServer: String? get() = apis.getOrNull(activeIndex)?.first ?: credentials?.server

    private val _account = MutableStateFlow<AccountInfo?>(null)

    /** Latest account details from sign-in (expiry, connection limit, provider timezone). */
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    private val rawCategories = AsyncCache<ContentType, List<Category>>()
    private val adultCategoryIds = ConcurrentHashMap<ContentType, Set<String>>()
    private val streamsByCategory = AsyncCache<String, List<Stream>>()
    private val allStreams = AsyncCache<ContentType, List<Stream>>()
    private val movieInfos = AsyncCache<Long, MovieInfo>()
    private val seriesInfos = AsyncCache<Long, SeriesInfo>()
    private val epgCache = ConcurrentHashMap<Long, Pair<Long, List<EpgEntry>>>()
    private val epgPermits = Semaphore(4)

    fun setCredentials(c: Credentials) {
        credentials = c
        apis = c.servers.map { server ->
            server to Retrofit.Builder()
                .baseUrl(server.trimEnd('/') + "/")
                .client(httpClient)
                .build()
                .create(XtreamApi::class.java)
        }
        activeIndex = 0
        clearCache()
    }

    fun clearCache() {
        rawCategories.clear()
        adultCategoryIds.clear()
        streamsByCategory.clear()
        allStreams.clear()
        visibleStreams.clear()
        catalogueLoads.clear()
        searchIndexes.clear()
        movieInfos.clear()
        seriesInfos.clear()
        epgCache.clear()
    }

    private suspend fun <T> request(action: String?, extra: Map<String, String> = emptyMap(), parse: (ResponseBody) -> T): T =
        withContext(Dispatchers.IO) {
            val c = credentials ?: throw AuthException("Not signed in")
            val clients = apis.ifEmpty { throw AuthException("Not signed in") }
            val params = buildMap {
                put("username", c.username)
                put("password", c.password)
                if (action != null) put("action", action)
                putAll(extra)
            }
            // Start with the server that worked last; on network or HTTP errors fall through to the next one.
            val start = activeIndex
            var lastError: Exception? = null
            for (offset in clients.indices) {
                val i = (start + offset) % clients.size
                try {
                    return@withContext clients[i].second.call(params).use(parse).also { activeIndex = i }
                } catch (e: IOException) {
                    lastError = e
                } catch (e: HttpException) {
                    lastError = e
                }
            }
            throw lastError!!
        }

    private suspend fun requestJson(action: String?, extra: Map<String, String> = emptyMap()): JsonElement =
        request(action, extra) { JsonParser.parseReader(it.charStream()) }

    // ---- Auth ----

    suspend fun authenticate(): AccountInfo {
        val root = requestJson(null).asObj() ?: throw AuthException("Unexpected server response")
        val user = root.get("user_info").asObj() ?: throw AuthException("Invalid username or password")
        if (user.str("auth") != "1") throw AuthException("Invalid username or password")
        val status = user.str("status")
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw AuthException("Account status: $status")
        }
        return AccountInfo(
            timezone = root.get("server_info").asObj()?.str("timezone"),
            status = status,
            expiresAt = user.str("exp_date")?.toLongId()?.times(1000),
            isTrial = user.str("is_trial") == "1",
            activeConnections = user.str("active_cons")?.toIntOrNull(),
            maxConnections = user.str("max_connections")?.toIntOrNull(),
            allowedFormats = user.get("allowed_output_formats").asArr()
                ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }.orEmpty(),
        ).also { _account.value = it }
    }

    // ---- Catalogue ----

    private suspend fun loadCategories(type: ContentType): List<Category> = rawCategories.get(type) {
        val action = when (type) {
            ContentType.LIVE -> "get_live_categories"
            ContentType.MOVIE -> "get_vod_categories"
            else -> "get_series_categories"
        }
        request(action) { body ->
            JsonUtil.readObjectArray(body.charStream()) { m ->
                val id = m["category_id"] ?: return@readObjectArray null
                Category(id, m["category_name"]?.trim().orEmpty().ifEmpty { "Uncategorised" })
            }
        }
    }.also { list ->
        adultCategoryIds[type] = list.filter { isAdultName(it.name) }.map { it.id }.toSet()
    }

    /** Categories, minus adult ones unless the viewer has unlocked them in Settings. */
    suspend fun categories(type: ContentType): List<Category> {
        val all = loadCategories(type)
        return if (store.showAdult) all else all.filter { !isAdultName(it.name) }
    }

    private fun isAdultName(name: String) = ADULT.containsMatchIn(name)

    private suspend fun hideAdult(type: ContentType, list: List<Stream>): List<Stream> {
        if (store.showAdult) return list
        loadCategories(type)
        val ids = adultCategoryIds[type].orEmpty()
        return list.filter { it.categoryId !in ids && !it.name.startsWith("XXX", ignoreCase = true) }
    }

    /** Streams for one category; served from the full list when that is already loaded. */
    suspend fun streams(type: ContentType, categoryId: String): List<Stream> {
        allStreams.peek(type)?.let { all -> return hideAdult(type, all.filter { it.categoryId == categoryId }) }
        return streamsByCategory.get("${type.name}:$categoryId") {
            val loaded = request(streamsAction(type), mapOf("category_id" to categoryId)) { parseStreams(type, it) }
            // Some panels ignore category_id and return everything.
            loaded.filter { it.categoryId == null || it.categoryId == categoryId }
        }
    }

    /** The filtered list per type, reused while the raw list and the adult setting are unchanged (see [allStreams]). */
    private val visibleStreams = ConcurrentHashMap<ContentType, Triple<List<Stream>, Boolean, List<Stream>>>()

    /**
     * Everything of one type (cached for the session). The same list instance comes back each time, so callers
     * can key their own caches on it, and a 16k-title list isn't re-filtered and copied on every call.
     */
    suspend fun allStreams(type: ContentType): List<Stream> {
        val raw = allStreams.get(type) { request(streamsAction(type)) { parseStreams(type, it) } }
        val adult = store.showAdult
        visibleStreams[type]?.let { (r, a, list) -> if (r === raw && a == adult) return list }
        return hideAdult(type, raw).also { visibleStreams[type] = Triple(raw, adult, it) }
    }

    private fun streamsAction(type: ContentType) = when (type) {
        ContentType.LIVE -> "get_live_streams"
        ContentType.MOVIE -> "get_vod_streams"
        else -> "get_series"
    }

    private fun parseStreams(type: ContentType, body: ResponseBody): List<Stream> =
        JsonUtil.readObjectArray(body.charStream()) { m ->
            when (type) {
                ContentType.LIVE -> Stream(
                    type = ContentType.LIVE,
                    id = m["stream_id"]?.toLongId() ?: return@readObjectArray null,
                    name = m["name"]?.trim().orEmpty(),
                    icon = m["stream_icon"].nullIfBlank(),
                    categoryId = m["category_id"],
                    num = m["num"]?.toIntOrNull(),
                    epgChannelId = m["epg_channel_id"].nullIfBlank(),
                    archiveDays = if (m["tv_archive"] == "1") m["tv_archive_duration"]?.toIntOrNull() ?: 0 else 0,
                )
                ContentType.MOVIE -> Stream(
                    type = ContentType.MOVIE,
                    id = m["stream_id"]?.toLongId() ?: return@readObjectArray null,
                    name = displayTitle(m),
                    icon = (m["stream_icon"] ?: m["cover"]).nullIfBlank(),
                    categoryId = m["category_id"],
                    extension = m["container_extension"].nullIfBlank()?.lowercase() ?: "mp4",
                    rating = m["rating"].nullIfBlank(),
                    num = m["num"]?.toIntOrNull(),
                    plot = m["plot"].nullIfBlank(),
                    year = year(m),
                    genre = m["genre"].nullIfBlank(),
                    added = m["added"]?.toLongId() ?: 0,
                    trailer = m["youtube_trailer"].nullIfBlank(),
                    hasCast = !m["cast"].isNullOrBlank(),
                    people = people(m),
                    runtimeMin = m["episode_run_time"]?.toIntOrNull()?.takeIf { it > 0 },
                )
                else -> Stream(
                    type = ContentType.SERIES,
                    id = m["series_id"]?.toLongId() ?: return@readObjectArray null,
                    name = displayTitle(m),
                    icon = m["cover"].nullIfBlank(),
                    categoryId = m["category_id"],
                    rating = m["rating"].nullIfBlank(),
                    num = m["num"]?.toIntOrNull(),
                    plot = m["plot"].nullIfBlank(),
                    backdrop = m["backdrop_path"].nullIfBlank(),
                    year = year(m),
                    genre = m["genre"].nullIfBlank(),
                    added = m["last_modified"]?.toLongId() ?: 0,
                    trailer = m["youtube_trailer"].nullIfBlank(),
                    hasCast = !m["cast"].isNullOrBlank(),
                    people = people(m),
                    runtimeMin = m["episode_run_time"]?.toIntOrNull()?.takeIf { it > 0 },
                )
            }
        }

    /** Panels send `name` as "Title (2014)" and a clean `title`; prefer the clean one. */
    private fun displayTitle(m: Map<String, String>): String =
        (m["title"].nullIfBlank() ?: m["name"]).orEmpty().trim()

    private fun people(m: Map<String, String>): String? =
        listOfNotNull(m["director"].nullIfBlank(), m["cast"].nullIfBlank()).joinToString(", ").nullIfBlank()

    private fun year(m: Map<String, String>): String? =
        (m["year"].nullIfBlank() ?: m["releaseDate"].nullIfBlank() ?: m["release_date"].nullIfBlank())
            ?.take(4)?.takeIf { it.all(Char::isDigit) }

    /**
     * One build per catalogue, outside the caller's coroutine. Search restarts on every keystroke; if the build
     * lived in the search coroutine, each letter would throw away a half-built index, and on a Fire TV Stick
     * (where building takes several seconds) search would never finish while someone was typing.
     */
    private val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val searchIndexes = ConcurrentHashMap<ContentType, Deferred<com.shizington.tv.data.search.SearchIndex>>()
    private val catalogueLoads = ConcurrentHashMap<ContentType, Deferred<List<Stream>>>()

    private suspend fun searchIndex(type: ContentType): com.shizington.tv.data.search.SearchIndex {
        // The catalogue download is shared and survives cancellation for the same reason.
        val all = catalogueLoads.compute(type) { _, old ->
            if (old != null && !(old.isCompleted && old.getCompletionExceptionOrNull() != null)) old
            else indexScope.async { allStreams(type) }
        }!!.await()
        return searchIndexes.compute(type) { _, old ->
            val stale = old == null || (old.isCompleted && (old.getCompletionExceptionOrNull() != null ||
                old.getCompleted().source.let { it.size != all.size || it.firstOrNull()?.id != all.firstOrNull()?.id }))
            if (stale) indexScope.async { com.shizington.tv.data.search.SearchIndex(all) } else old
        }!!.await()
    }

    /** Builds the search indexes in the background so the first search is quick. */
    fun prewarmSearch() {
        indexScope.launch { for (type in listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE)) runCatching { searchIndex(type) } }
    }

    suspend fun search(query: String, type: ContentType, limit: Int = 60): List<Stream> {
        if (query.isBlank()) return emptyList()
        val index = searchIndex(type)
        return withContext(Dispatchers.Default) { index.titles(query, limit) }
    }

    /** Films and series featuring a person, from the cast/director fields the provider sends. */
    suspend fun searchByPerson(query: String, type: ContentType): com.shizington.tv.data.search.PersonMatch? {
        if (query.isBlank()) return null
        val index = searchIndex(type)
        return withContext(Dispatchers.Default) { index.byPerson(query) }
    }

    // ---- Details ----

    suspend fun movieInfo(id: Long): MovieInfo = movieInfos.get(id) {
        val root = requestJson("get_vod_info", mapOf("vod_id" to id.toString())).asObj()
            ?: throw IllegalStateException("Movie not found")
        val info = root.get("info").asObj()
        val data = root.get("movie_data").asObj()
        MovieInfo(
            id = id,
            name = data?.str("title") ?: info?.str("name", "o_name") ?: data?.str("name") ?: "",
            poster = info?.str("movie_image", "cover_big", "cover"),
            backdrop = info?.str("backdrop_path", "backdrop"),
            plot = info?.str("plot", "description"),
            cast = info?.str("cast", "actors"),
            director = info?.str("director"),
            genre = info?.str("genre"),
            releaseDate = info?.str("releasedate", "release_date", "releaseDate", "year"),
            rating = info?.str("rating"),
            duration = info?.str("duration_secs")?.toIntOrNull()?.let(::formatRuntime)
                ?: info?.str("episode_run_time")?.toIntOrNull()?.let { formatRuntime(it * 60) }
                ?: info?.str("duration")?.takeUnless { it.all { c -> c == '0' || c == ':' } },
            extension = data?.str("container_extension")?.lowercase() ?: "mp4",
            categoryId = data?.str("category_id"),
            trailer = info?.str("youtube_trailer"),
            country = info?.str("country"),
            ageRating = info?.str("mpaa_rating", "age"),
            tmdbId = info?.str("tmdb_id")?.toLongId()?.takeIf { it > 0 },
            bitrateKbps = info?.str("bitrate")?.toIntOrNull()?.takeIf { it > 0 },
        )
    }

    suspend fun seriesInfo(id: Long): SeriesInfo = seriesInfos.get(id) {
        val root = requestJson("get_series_info", mapOf("series_id" to id.toString())).asObj()
            ?: throw IllegalStateException("Series not found")
        val info = root.get("info").asObj()

        val seasonMeta = root.get("seasons").asArr()?.mapNotNull { it.asObj() }
            ?.associateBy { it.str("season_number")?.toIntOrNull() ?: -1 }.orEmpty()

        val rawEpisodes = mutableListOf<Pair<Int?, JsonElement>>()
        val episodesEl = root.get("episodes")
        episodesEl.asObj()?.entrySet()?.forEach { (season, list) ->
            list.asArr()?.forEach { rawEpisodes += season.toIntOrNull() to it }
        }
        episodesEl.asArr()?.forEach { inner ->
            inner.asArr()?.forEach { rawEpisodes += null to it } ?: rawEpisodes.add(null to inner)
        }

        val episodes = rawEpisodes.mapNotNull { (seasonKey, el) ->
            val e = el.asObj() ?: return@mapNotNull null
            val epInfo = e.get("info").asObj()
            Episode(
                id = e.str("id")?.toLongId() ?: return@mapNotNull null,
                season = e.str("season")?.toIntOrNull() ?: seasonKey ?: 1,
                episodeNum = e.str("episode_num")?.toIntOrNull() ?: 0,
                title = cleanEpisodeTitle(e.str("title"), e.str("episode_num")?.toIntOrNull()),
                plot = epInfo?.str("plot", "description"),
                image = epInfo?.str("movie_image", "cover_big"),
                durationSecs = epInfo?.str("duration_secs")?.toIntOrNull(),
                extension = e.str("container_extension")?.lowercase() ?: "mp4",
            )
        }

        val seasons = episodes.groupBy { it.season }.toSortedMap().map { (num, eps) ->
            val meta = seasonMeta[num]
            Season(
                number = num,
                name = meta?.str("name")?.takeUnless { it.matches(Regex("(?i)season\\s*\\d+")) }
                    ?.let { "Season $num · $it" } ?: "Season $num",
                cover = meta?.str("cover_big", "cover"),
                episodes = eps.sortedBy { it.episodeNum },
                overview = meta?.str("overview"),
                airDate = meta?.str("air_date"),
            )
        }

        SeriesInfo(
            id = id,
            name = info?.str("title", "name") ?: "",
            cover = info?.str("cover"),
            backdrop = info?.str("backdrop_path"),
            plot = info?.str("plot"),
            cast = info?.str("cast"),
            director = info?.str("director"),
            genre = info?.str("genre"),
            releaseDate = info?.str("releaseDate", "release_date"),
            rating = info?.str("rating"),
            seasons = seasons,
            trailer = info?.str("youtube_trailer"),
        )
    }

    /** "100 Foot Wave - S01E03 - Chapter III" -> "Chapter III"; a bare "Episode" becomes "Episode 3". */
    private fun cleanEpisodeTitle(raw: String?, num: Int?): String {
        val stripped = raw?.replace(Regex("^.*?\\bS\\d+\\s*E\\d+\\s*[-:]?\\s*", RegexOption.IGNORE_CASE), "")?.trim()
        return when {
            stripped.isNullOrBlank() || stripped.equals("episode", ignoreCase = true) -> "Episode ${num ?: ""}".trim()
            else -> stripped
        }
    }

    private fun formatRuntime(secs: Int): String? {
        if (secs <= 0) return null
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    // ---- Curated rows (built from the full catalogue, which is cached after the first load) ----

    suspend fun recentlyAdded(type: ContentType, limit: Int = 40): List<Stream> =
        allStreams(type).sortedByDescending { it.added }.take(limit)

    /**
     * Ratings come without vote counts, so the 10/10s are obscure titles with a handful of votes.
     * Real top films sit around 7.3–8.6, so keep that band from recent years, with real metadata.
     */
    suspend fun topRated(type: ContentType, limit: Int = 40): List<Stream> {
        val minYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 7
        return withContext(Dispatchers.Default) {
            allStreams(type)
                .filter { it.ratingValue in 7.3..8.6 && (it.year?.toIntOrNull() ?: 0) >= minYear && it.plot != null && it.hasCast }
                .sortedWith(compareByDescending<Stream> { it.ratingValue }.thenByDescending { it.added })
                .take(limit)
        }
    }

    /** The most common genres, for genre rows. */
    suspend fun topGenres(type: ContentType, count: Int): List<String> = withContext(Dispatchers.Default) {
        allStreams(type).flatMap { it.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(count).map { it.key }
    }

    /** Newer, decently rated titles first. */
    suspend fun byGenre(type: ContentType, genre: String, limit: Int = 60): List<Stream> = withContext(Dispatchers.Default) {
        allStreams(type).filter { genre in it.genres }
            .sortedWith(compareByDescending<Stream> { it.ratingValue >= 6.0 }.thenByDescending { it.year }.thenByDescending { it.ratingValue })
            .take(limit)
    }

    /** "More like this": titles sharing the most genres, then the best rated. */
    suspend fun similar(type: ContentType, id: Long, genre: String?, limit: Int = 20): List<Stream> = withContext(Dispatchers.Default) {
        val wanted = genre?.split(',', '/', '&')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        if (wanted.isEmpty()) return@withContext emptyList()
        allStreams(type).asSequence()
            .filter { it.id != id && it.ratingValue >= 6.0 }
            .map { it to it.genres.count { g -> g in wanted } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Stream, Int>> { it.second }.thenByDescending { it.first.ratingValue })
            .take(limit).map { it.first }.toList()
    }

    // ---- Catch-up ----

    /** Past programmes the provider has recordings of, newest first. */
    suspend fun catchUp(streamId: Long): List<EpgEntry> {
        val now = System.currentTimeMillis()
        val root = requestJson("get_simple_data_table", mapOf("stream_id" to streamId.toString()))
        return root.asObj()?.get("epg_listings").asArr()?.mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            if (o.str("has_archive") != "1") return@mapNotNull null
            val start = o.str("start_timestamp")?.toLongId()?.times(1000) ?: return@mapNotNull null
            val end = o.str("stop_timestamp", "end_timestamp")?.toLongId()?.times(1000) ?: return@mapNotNull null
            if (start > now) return@mapNotNull null
            EpgEntry(decode(o.str("title")).ifBlank { "Programme" }, decode(o.str("description")).nullIfBlank(), start, end, hasArchive = true)
        }.orEmpty().sortedByDescending { it.startMs }
    }

    fun cachedMovieInfo(id: Long): MovieInfo? = movieInfos.peek(id)

    // ---- EPG ----

    /** Short EPG (now + upcoming) for a live channel, cached until the current programme ends. */
    suspend fun shortEpg(streamId: Long, limit: Int = 4): List<EpgEntry> {
        val now = System.currentTimeMillis()
        epgCache[streamId]?.let { (validUntil, list) -> if (now < validUntil) return list }
        val list = epgPermits.withPermit {
            runCatching {
                val root = requestJson("get_short_epg", mapOf("stream_id" to streamId.toString(), "limit" to limit.toString()))
                root.asObj()?.get("epg_listings").asArr()?.mapNotNull { el ->
                    val o = el.asObj() ?: return@mapNotNull null
                    val start = o.str("start_timestamp")?.toLongId()?.times(1000) ?: return@mapNotNull null
                    val end = o.str("stop_timestamp", "end_timestamp")?.toLongId()?.times(1000) ?: return@mapNotNull null
                    EpgEntry(decode(o.str("title")).ifBlank { "No information" }, decode(o.str("description")).nullIfBlank(), start, end)
                }.orEmpty().filter { it.endMs > now }.sortedBy { it.startMs }
            }.getOrDefault(emptyList())
        }
        val validUntil = list.firstOrNull()?.endMs?.coerceAtMost(now + 30 * 60_000L) ?: (now + 10 * 60_000L)
        epgCache[streamId] = validUntil to list
        return list
    }

    fun cachedEpg(streamId: Long): List<EpgEntry>? = epgCache[streamId]?.second

    private fun decode(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return try {
            String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: IllegalArgumentException) {
            value
        }
    }

    // ---- Stream URLs ----

    fun streamUrl(p: Playable): String {
        val c = credentials ?: throw AuthException("Not signed in")
        val base = (activeServer ?: c.server).trimEnd('/')
        val user = Uri.encode(c.username)
        val pass = Uri.encode(c.password)
        if (p.isCatchUp) {
            // Xtream expects the start in the provider's own timezone, as yyyy-MM-dd:HH-mm.
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone(_account.value?.timezone ?: "UTC")
            }
            return "$base/timeshift/$user/$pass/${p.archiveMinutes ?: 60}/${fmt.format(java.util.Date(p.archiveStartMs!!))}/${p.id}.ts"
        }
        return when (p.type) {
            ContentType.LIVE -> "$base/live/$user/$pass/${p.id}.${store.liveFormat}"
            ContentType.MOVIE -> "$base/movie/$user/$pass/${p.id}.${p.extension ?: "mp4"}"
            ContentType.EPISODE -> "$base/series/$user/$pass/${p.id}.${p.extension ?: "mp4"}"
            ContentType.SERIES -> throw IllegalArgumentException("A series is not directly playable")
        }
    }

    companion object {
        private val ADULT = Regex("""(?i)\b(xxx|adult|porn)\b|18\+""")

        /** Accepts "host:port", "http://host:port/", or a pasted player_api.php URL. */
        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.substringBefore("?")
            s = s.removeSuffix("/player_api.php").removeSuffix("/get.php").trimEnd('/')
            return s
        }
    }
}
