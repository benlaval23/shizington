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
import kotlinx.coroutines.Dispatchers
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

    private val categories = AsyncCache<ContentType, List<Category>>()
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
        categories.clear()
        streamsByCategory.clear()
        allStreams.clear()
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
            status = status,
            expiresAt = user.str("exp_date")?.toLongId()?.times(1000),
            isTrial = user.str("is_trial") == "1",
            activeConnections = user.str("active_cons")?.toIntOrNull(),
            maxConnections = user.str("max_connections")?.toIntOrNull(),
            allowedFormats = user.get("allowed_output_formats").asArr()
                ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }.orEmpty(),
        )
    }

    // ---- Catalogue ----

    suspend fun categories(type: ContentType): List<Category> = categories.get(type) {
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
    }

    /** Streams for one category; served from the full list when that is already loaded. */
    suspend fun streams(type: ContentType, categoryId: String): List<Stream> {
        allStreams.peek(type)?.let { all -> return all.filter { it.categoryId == categoryId } }
        return streamsByCategory.get("${type.name}:$categoryId") {
            val loaded = request(streamsAction(type), mapOf("category_id" to categoryId)) { parseStreams(type, it) }
            // Some panels ignore category_id and return everything.
            loaded.filter { it.categoryId == null || it.categoryId == categoryId }
        }
    }

    suspend fun allStreams(type: ContentType): List<Stream> = allStreams.get(type) {
        request(streamsAction(type)) { parseStreams(type, it) }
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
                )
            }
        }

    /** Panels send `name` as "Title (2014)" and a clean `title`; prefer the clean one. */
    private fun displayTitle(m: Map<String, String>): String =
        (m["title"].nullIfBlank() ?: m["name"]).orEmpty().trim()

    private fun year(m: Map<String, String>): String? =
        (m["year"].nullIfBlank() ?: m["releaseDate"].nullIfBlank() ?: m["release_date"].nullIfBlank())
            ?.take(4)?.takeIf { it.all(Char::isDigit) }

    suspend fun search(query: String, type: ContentType, limit: Int = 60): List<Stream> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val all = allStreams(type)
        return withContext(Dispatchers.Default) {
            val starts = ArrayList<Stream>()
            val contains = ArrayList<Stream>()
            for (s in all) {
                val idx = s.name.indexOf(q, ignoreCase = true)
                if (idx == 0) starts += s else if (idx > 0) contains += s
                if (starts.size >= limit) break
            }
            (starts + contains).take(limit)
        }
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
        return when (p.type) {
            ContentType.LIVE -> "$base/live/$user/$pass/${p.id}.${store.liveFormat}"
            ContentType.MOVIE -> "$base/movie/$user/$pass/${p.id}.${p.extension ?: "mp4"}"
            ContentType.EPISODE -> "$base/series/$user/$pass/${p.id}.${p.extension ?: "mp4"}"
            ContentType.SERIES -> throw IllegalArgumentException("A series is not directly playable")
        }
    }

    companion object {
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
