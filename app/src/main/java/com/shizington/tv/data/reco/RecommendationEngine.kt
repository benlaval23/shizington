package com.shizington.tv.data.reco

import com.shizington.tv.data.LibraryRepository
import com.shizington.tv.data.Signals
import com.shizington.tv.data.XtreamRepository
import com.shizington.tv.data.catalog.Catalog
import com.shizington.tv.data.db.ProgressEntity
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.Stream
import com.shizington.tv.data.tmdb.TmdbDetails
import com.shizington.tv.data.tmdb.TmdbRepository
import com.shizington.tv.data.tmdb.TmdbTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

enum class RowStyle { POSTER, TOP10, LANDSCAPE }

data class FeedItem(
    val stream: Stream,
    /** Why it's here, shown on the banner when focused ("Because you watched Suits LA"). */
    val reason: String? = null,
    val badge: String? = null,
    val rank: Int? = null,
    val tmdbId: Long? = null,
)

data class FeedRow(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val style: RowStyle = RowStyle.POSTER,
    val items: List<FeedItem>,
)

/** A featured title for the rotating billboard, with TMDB artwork when available. */
data class BillboardItem(val item: FeedItem, val details: TmdbDetails?, val label: String)

data class HomeFeed(val billboard: List<BillboardItem>, val rows: List<FeedRow>, val personalised: Boolean)

data class NewHotFeed(
    val outNow: List<FeedItem>,
    val top10: List<FeedItem>,
    val trendingSeries: List<FeedItem>,
    val newEpisodes: List<FeedItem>,
    /** Not on the provider yet; can be saved to My List and shows up on Home once it lands. */
    val comingSoon: List<TmdbTitle>,
)

/**
 * On-device recommender. Learns one shared household profile from what's finished, abandoned,
 * favourited and rated, blends in TMDB's crowd recommendations, and only ever suggests things the
 * provider actually carries. Nothing about viewing leaves the device; TMDB only sees title lookups.
 */
class RecommendationEngine(
    private val xtream: XtreamRepository,
    private val tmdb: TmdbRepository,
    private val library: LibraryRepository,
) {
    private class Taste(
        val genres: Map<String, Double>,
        val people: Map<String, Double>,
        val seeds: List<Pair<Stream, Double>>,
        val seen: Set<String>,
        val disliked: Set<String>,
        val inProgress: Set<String>,
    ) {
        val isEmpty get() = genres.isEmpty() && seeds.isEmpty()
    }

    private val catalogLock = kotlinx.coroutines.sync.Mutex()
    private var cachedCatalog: Catalog? = null
    private var catalogAt = 0L

    /** One index of the provider's films and series, shared by Home and New & Hot (rebuilt every 20 min). */
    private suspend fun catalog(): Catalog = catalogLock.withLock {
        cachedCatalog?.takeIf { System.currentTimeMillis() - catalogAt < 20 * 60_000L }?.let { return it }
        coroutineScope {
            val movies = async { runCatching { xtream.allStreams(ContentType.MOVIE) }.getOrDefault(emptyList()) }
            val series = async { runCatching { xtream.allStreams(ContentType.SERIES) }.getOrDefault(emptyList()) }
            withContext(Dispatchers.Default) { Catalog(movies.await(), series.await()) }
        }.also { if (it.movies.isNotEmpty()) { cachedCatalog = it; catalogAt = System.currentTimeMillis() } }
    }

    /** TMDB titles that the provider actually has, in the same order. */
    suspend fun onProvider(titles: List<TmdbTitle>): List<FeedItem> = mapTmdb(catalog(), titles)

    /** Extras for a detail page: TMDB details, the rest of the franchise and "more like this", all mapped to the catalogue. */
    suspend fun detailExtras(type: ContentType, contentId: Long, name: String, year: Int?, knownTmdbId: Long?): DetailExtras? = coroutineScope {
        if (!tmdb.enabled) return@coroutineScope null
        val id = knownTmdbId?.also { tmdb.rememberId(type, contentId, it) } ?: tmdb.findId(type, contentId, name, year) ?: return@coroutineScope null
        val details = async { tmdb.details(type, id) }
        val recs = async { onProvider(tmdb.recommendations(type, id)).filter { it.stream.id != contentId || it.stream.type != type } }
        val d = details.await() ?: return@coroutineScope null
        val franchise = d.collectionId?.let { tmdb.collection(it) }?.let { coll ->
            val items = onProvider(coll.parts)
            if (items.size >= 2) coll.name to items else null
        }
        DetailExtras(d, franchise?.first, franchise?.second.orEmpty(), recs.await())
    }

    suspend fun homeFeed(): HomeFeed = coroutineScope {
        val catalog = catalog()
        val signals = library.signalsSnapshot()
        val taste = withContext(Dispatchers.Default) { taste(catalog, signals) }

        // TMDB lists, fetched in parallel and cached (cheap after the first run of the day).
        val trendingDay = async { tmdb.trending(ContentType.MOVIE, "day") + tmdb.trending(ContentType.SERIES, "day") }
        val trendingTv = async { tmdb.trending(ContentType.SERIES, "week") }
        val releases = async { tmdb.ukDigitalReleases() }
        val gems = async { tmdb.hiddenGems(ContentType.MOVIE) }
        val seedRecs = taste.seeds.take(4).map { (s, _) -> async { s to seedRecommendations(catalog, s) } }
        val watchlist = async { library.watchlist.first() }
        val franchiseItems = async { franchise(catalog, signals) }

        val becauseOf = seedRecs.awaitAll()
        val rows = mutableListOf<FeedRow>()
        val used = HashSet<String>()

        fun FeedRow.keep(): FeedRow? = takeIf { it.items.size >= 4 }?.also { r -> r.items.take(8).forEach { used += it.stream.key } }

        // From the watchlist: things saved under "Coming soon" that the provider now has.
        val arrived = watchlist.await().mapNotNull { w ->
            val type = if (w.mediaType == "tv") ContentType.SERIES else ContentType.MOVIE
            catalog.index(type).find(w.title, w.year)?.let { FeedItem(it, "On your list, now available", badge = "NOW AVAILABLE", tmdbId = w.tmdbId) }
        }
        if (arrived.isNotEmpty()) rows += FeedRow("arrived", "From your list, now available", style = RowStyle.POSTER, items = arrived)

        val top10 = mapTmdb(catalog, trendingDay.await()).filterNot { it.stream.key in taste.disliked }.take(10)
            .mapIndexed { i, f -> f.copy(rank = i + 1, reason = "#${i + 1} in the UK today") }
        val picks = topPicks(catalog, taste, becauseOf)
        val landed = mapTmdb(catalog, releases.await()).filterNot { it.stream.key in taste.disliked }
            .map { it.copy(badge = "NEW", reason = "Just released in the UK") }

        FeedRow("picks", if (taste.isEmpty) "Picked for tonight" else "Top picks for you both",
            if (taste.isEmpty) "Watch a few things and these get personal" else "Based on what you finish, favourite and rate",
            items = picks).keep()?.let { rows += it }
        FeedRow("top10", "Top 10 in the UK today", style = RowStyle.TOP10, items = top10).takeIf { top10.size >= 5 }?.let { rows += it }
        FeedRow("landed", "Just landed", "New on digital release in the UK", items = landed.take(30)).keep()?.let { rows += it }

        becauseOf.take(2).forEach { (seed, recs) ->
            val items = recs.filter { it.stream.key !in taste.seen && it.stream.key !in taste.disliked && it.stream.key !in used }
                .map { it.copy(reason = "Because you watched ${seed.name}") }
            FeedRow("because_${seed.key}", "Because you watched ${seed.name}", items = items.take(25)).keep()?.let { rows += it }
        }

        newEpisodes(catalog, signals).takeIf { it.isNotEmpty() }?.let {
            rows += FeedRow("new_eps", "New episodes of your shows", style = RowStyle.POSTER, items = it)
        }

        val trendingSeries = mapTmdb(catalog, trendingTv.await()).filter { it.stream.type == ContentType.SERIES && it.stream.key !in taste.disliked && it.stream.key !in used }
            .map { it.copy(reason = "Trending in the UK this week") }
        FeedRow("trending_tv", "Trending series", items = trendingSeries).keep()?.let { rows += it }

        val gemItems = mapTmdb(catalog, gems.await()).filter { it.stream.key !in taste.seen && it.stream.key !in taste.disliked && it.stream.key !in used }
            .sortedByDescending { score(it.stream, taste) }
            .map { it.copy(reason = "Loved by the few who've seen it", badge = "GEM") }
        FeedRow("gems", "Hidden gems", "Brilliant, but you may have missed them", items = gemItems.take(25)).keep()?.let { rows += it }

        FeedRow("short", "Short on time?", shortSubtitle(), items = shortOnTime(catalog, taste)).keep()?.let { rows += it }

        franchiseItems.await().takeIf { it.size >= 2 }?.let {
            rows += FeedRow("franchise", "Continue the franchise", items = it)
        }

        val billboard = billboard(picks, landed, top10)
        HomeFeed(billboard, rows, personalised = !taste.isEmpty)
    }

    /** Everything for the New & Hot tab: what's out, what's big, what's back, what's coming. */
    suspend fun newAndHot(): NewHotFeed = coroutineScope {
        val catalog = catalog()
        val signals = library.signalsSnapshot()
        val releases = async { tmdb.ukDigitalReleases(days = 45) }
        val trending = async { tmdb.trending(ContentType.MOVIE, "day") + tmdb.trending(ContentType.SERIES, "day") }
        val trendingTv = async { tmdb.trending(ContentType.SERIES, "week") }
        val upcoming = async { tmdb.upcomingMovies(pages = 3) }

        val today = java.time.LocalDate.now()
        val releaseList = releases.await()
        val outNow = mapTmdb(catalog, releaseList).map { item ->
            val date = releaseList.firstOrNull { it.id == item.tmdbId }?.releaseDate
            val days = date?.let { runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(it), today) }.getOrNull() }
            item.copy(
                badge = when (days) { null -> "NEW"; 0L -> "OUT TODAY"; 1L -> "OUT YESTERDAY"; in 2..13 -> "OUT $days DAYS AGO"; else -> "NEW" },
                reason = "Out on digital in the UK" + (date?.let { " · ${java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))}" } ?: ""),
            )
        }
        val top10 = mapTmdb(catalog, trending.await()).take(10).mapIndexed { i, f -> f.copy(rank = i + 1, reason = "#${i + 1} in the UK today") }
        val trendingSeries = mapTmdb(catalog, trendingTv.await()).filter { it.stream.type == ContentType.SERIES }
            .map { it.copy(reason = "Trending in the UK this week") }
        val comingSoon = upcoming.await().filter { catalog.movieIndex.find(it.title, it.year, it.originalTitle) == null && it.poster != null }

        NewHotFeed(
            outNow = outNow,
            top10 = top10,
            trendingSeries = trendingSeries,
            newEpisodes = newEpisodes(catalog, signals),
            comingSoon = comingSoon,
        )
    }

    /** Scores the catalogue against the profile, mixes in TMDB "because you watched" titles, and keeps variety. */
    private fun topPicks(catalog: Catalog, taste: Taste, becauseOf: List<Pair<Stream, List<FeedItem>>>): List<FeedItem> {
        val fromSeeds = becauseOf.flatMap { (seed, recs) -> recs.map { it.copy(reason = "Because you watched ${seed.name}") } }
            .filter { it.stream.key !in taste.seen && it.stream.key !in taste.disliked && it.stream.key !in taste.inProgress }
        if (taste.isEmpty && fromSeeds.isEmpty()) {
            // Cold start: well-made, recent and popular.
            val pool = (catalog.movies + catalog.series).filter { it.ratingValue in 7.0..8.8 && (it.year?.toIntOrNull() ?: 0) >= currentYear() - 3 && it.hasCast }
            return diversify(pool.sortedByDescending { it.ratingValue + freshness(it) }.take(120).map { FeedItem(it, "Well reviewed and recent") }, 24)
        }
        val scored = (catalog.movies + catalog.series).asSequence()
            .filter { it.key !in taste.seen && it.key !in taste.disliked && it.key !in taste.inProgress && it.hasCast }
            .map { it to score(it, taste) }
            .sortedByDescending { it.second }
            .take(150)
            .map { (s, _) -> FeedItem(s, reasonFor(s, taste)) }
            .toList()
        // Crowd-backed picks first, then the profile matches; diversify keeps one genre from taking over.
        return diversify((fromSeeds.take(30) + scored).distinctBy { it.stream.key }, 24)
    }

    private fun score(s: Stream, t: Taste): Double {
        val g = s.genres.sumOf { t.genres[it] ?: 0.0 } / sqrt(s.genres.size.coerceAtLeast(1).toDouble())
        val p = names(s.people).take(6).sumOf { t.people[it] ?: 0.0 }
        val quality = ((s.ratingValue - 5.0) / 4.0).coerceIn(0.0, 1.0)
        return 0.5 * g + 0.25 * p + 0.15 * quality + 0.1 * freshness(s)
    }

    private fun reasonFor(s: Stream, t: Taste): String {
        val person = names(s.people).take(6).maxByOrNull { t.people[it] ?: 0.0 }?.takeIf { (t.people[it] ?: 0.0) > 0.6 }
        if (person != null) return "With $person, who you watch a lot"
        val genres = s.genres.filter { (t.genres[it] ?: 0.0) > 0 }.sortedByDescending { t.genres[it] }.take(2)
        return if (genres.isNotEmpty()) "Because you like ${genres.joinToString(" & ")}" else "Well reviewed"
    }

    /** Builds the household taste profile with a ~45-day half-life so recent viewing matters most. */
    private fun taste(catalog: Catalog, sig: Signals): Taste {
        val now = System.currentTimeMillis()
        val weights = HashMap<String, Double>() // stream key -> signed weight
        val streams = HashMap<String, Stream>()
        fun add(s: Stream?, w: Double, at: Long) {
            s ?: return
            val decay = exp(-ln(2.0) * ((now - at).coerceAtLeast(0) / 86_400_000.0) / 45.0)
            weights[s.key] = (weights[s.key] ?: 0.0) + w * decay
            streams[s.key] = s
        }
        fun resolve(type: String, id: Long, seriesId: Long?): Stream? = when (type) {
            ContentType.MOVIE.name -> catalog.movieById[id]
            ContentType.EPISODE.name -> seriesId?.let { catalog.seriesById[it] }
            ContentType.SERIES.name -> catalog.seriesById[id]
            else -> null
        }
        val seen = HashSet<String>()
        val inProgress = HashSet<String>()
        sig.views.forEach { v ->
            val s = resolve(v.type, v.contentId, v.seriesId)
            val w = when {
                v.fraction >= 0.85f -> 1.0
                v.fraction >= 0.4f -> 0.6
                v.fraction < 0.1f && v.watchedMs > 60_000 -> -0.4
                else -> 0.2
            }
            add(s, w, v.startedAt)
            if (v.type == ContentType.MOVIE.name && v.fraction >= 0.85f) s?.let { seen += it.key }
        }
        sig.progress.forEach { p: ProgressEntity ->
            val s = resolve(p.type, p.contentId, p.seriesId)
            add(s, if (p.isCompleted) 1.0 else if (p.isResumable) 0.6 else 0.2, p.updatedAt)
            if (p.type == ContentType.MOVIE.name && p.isCompleted) s?.let { seen += it.key }
            if (p.isResumable) s?.let { inProgress += it.key }
        }
        sig.favourites.filter { it.type != ContentType.LIVE }.forEach { f -> add(resolve(f.type.name, f.id, null) ?: f, 1.0, now) }
        val disliked = HashSet<String>()
        sig.feedback.forEach { f ->
            val s = resolve(f.type, f.contentId, null)
            add(s, if (f.value > 0) 1.5 else -2.0, f.at)
            if (f.value < 0) s?.let { disliked += it.key }
        }
        val genres = HashMap<String, Double>()
        val people = HashMap<String, Double>()
        weights.forEach { (key, w) ->
            val s = streams[key] ?: return@forEach
            s.genres.forEach { genres[it] = (genres[it] ?: 0.0) + w }
            names(s.people).take(5).forEach { people[it] = (people[it] ?: 0.0) + w }
        }
        val gMax = genres.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val pMax = people.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val seeds = weights.filter { it.value > 0.5 }.mapNotNull { (k, w) -> streams[k]?.let { it to w } }.sortedByDescending { it.second }
        return Taste(
            genres = genres.mapValues { it.value / gMax },
            people = people.filterValues { it > 0 }.mapValues { it.value / pMax },
            seeds = seeds,
            seen = seen,
            disliked = disliked,
            inProgress = inProgress,
        )
    }

    /** TMDB "people who liked this also liked", limited to what the provider has; falls back to genre/cast similarity. */
    private suspend fun seedRecommendations(catalog: Catalog, seed: Stream): List<FeedItem> {
        val type = if (seed.type == ContentType.SERIES) ContentType.SERIES else ContentType.MOVIE
        val tmdbId = tmdb.findId(type, seed.id, seed.name, seed.year?.toIntOrNull())
        val fromTmdb = tmdbId?.let { mapTmdb(catalog, tmdb.recommendations(type, it)) }.orEmpty()
        if (fromTmdb.size >= 6) return fromTmdb.filter { it.stream.key != seed.key }
        val similar = runCatching { xtream.similar(type, seed.id, seed.genre, 30) }.getOrDefault(emptyList()).map { FeedItem(it) }
        return (fromTmdb + similar).distinctBy { it.stream.key }.filter { it.stream.key != seed.key }
    }

    /** Turns TMDB titles into provider streams (title + year), remembering the TMDB id for detail pages. */
    private suspend fun mapTmdb(catalog: Catalog, titles: List<TmdbTitle>): List<FeedItem> {
        val out = ArrayList<FeedItem>()
        val seen = HashSet<String>()
        val ids = ArrayList<Triple<ContentType, Long, Long>>()
        withContext(Dispatchers.Default) {
            for (t in titles) {
                val s = catalog.index(t.type).find(t.title, t.year, t.originalTitle) ?: continue
                if (!seen.add(s.key)) continue
                ids += Triple(t.type, s.id, t.id)
                out += FeedItem(s, tmdbId = t.id)
            }
        }
        tmdb.rememberIds(ids)
        return out
    }

    /** Series you watch whose provider entry was updated recently. */
    private fun newEpisodes(catalog: Catalog, sig: Signals): List<FeedItem> {
        val ids = (sig.progress.mapNotNull { it.seriesId } + sig.views.mapNotNull { it.seriesId } +
            sig.favourites.filter { it.type == ContentType.SERIES }.map { it.id }).toSet()
        val cutoff = System.currentTimeMillis() / 1000 - 21 * 86_400L
        return catalog.series.filter { it.id in ids && it.added > cutoff }
            .sortedByDescending { it.added }
            .map { FeedItem(it, "New episodes added", badge = "NEW EPISODES") }
    }

    private fun shortOnTime(catalog: Catalog, taste: Taste): List<FeedItem> =
        catalog.movies.asSequence()
            .filter { (it.runtimeMin ?: 0) in 60..95 && it.ratingValue >= 6.5 && it.key !in taste.seen && it.key !in taste.disliked && it.hasCast }
            .sortedByDescending { score(it, taste) }
            .take(60).toList()
            .let { list -> diversify(list.map { FeedItem(it, "${it.runtimeMin} minutes", badge = "${it.runtimeMin} MIN") }, 24) }

    /** Next unwatched film in a franchise you've started (needs TMDB). */
    private suspend fun franchise(catalog: Catalog, sig: Signals): List<FeedItem> = coroutineScope {
        if (!tmdb.enabled) return@coroutineScope emptyList()
        val watched = sig.progress.filter { it.type == ContentType.MOVIE.name && (it.isCompleted || it.isResumable) }.take(10)
        watched.map { p ->
            async {
                val stream = catalog.movieById[p.contentId] ?: return@async null
                val id = tmdb.findId(ContentType.MOVIE, stream.id, stream.name, stream.year?.toIntOrNull()) ?: return@async null
                val coll = tmdb.details(ContentType.MOVIE, id)?.collectionId?.let { tmdb.collection(it) } ?: return@async null
                val idx = coll.parts.indexOfFirst { it.id == id }
                coll.parts.drop(idx + 1).firstNotNullOfOrNull { part ->
                    catalog.movieIndex.find(part.title, part.year)?.let { FeedItem(it, "Next in ${coll.name}", badge = "NEXT IN SERIES") }
                }
            }
        }.awaitAll().filterNotNull().distinctBy { it.stream.key }
    }

    private suspend fun billboard(picks: List<FeedItem>, landed: List<FeedItem>, top10: List<FeedItem>): List<BillboardItem> = coroutineScope {
        val chosen = (landed.take(2).map { it to "New in the UK" } + picks.take(2).map { it to "Picked for you" } + top10.take(2).map { it to "Top 10 · #${it.rank}" })
            .distinctBy { it.first.stream.key }.take(5)
        chosen.map { (item, label) ->
            async {
                val type = if (item.stream.type == ContentType.SERIES) ContentType.SERIES else ContentType.MOVIE
                val id = item.tmdbId ?: tmdb.findId(type, item.stream.id, item.stream.name, item.stream.year?.toIntOrNull())
                BillboardItem(item, id?.let { tmdb.details(type, it) }, label)
            }
        }.awaitAll()
    }

    /** Greedy re-rank so no single genre dominates a row. */
    private fun diversify(items: List<FeedItem>, n: Int): List<FeedItem> {
        val out = ArrayList<FeedItem>()
        val genreCount = HashMap<String, Int>()
        val pool = items.toMutableList()
        while (out.size < n && pool.isNotEmpty()) {
            val best = pool.withIndex().maxByOrNull { (i, f) ->
                val primary = f.stream.genres.firstOrNull()
                -i * 0.02 - (genreCount[primary] ?: 0) * 0.35
            }!!
            pool.removeAt(best.index)
            out += best.value
            best.value.stream.genres.firstOrNull()?.let { genreCount[it] = (genreCount[it] ?: 0) + 1 }
        }
        return out
    }

    private fun shortSubtitle(): String {
        val c = Calendar.getInstance()
        val weekend = c.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.FRIDAY, Calendar.SATURDAY)
        return if (weekend) "Under 95 minutes, if it's a late one" else "Under 95 minutes, for a weeknight"
    }

    private fun freshness(s: Stream): Double {
        val y = s.year?.toIntOrNull() ?: return 0.2
        return (1.0 - (currentYear() - y) / 15.0).coerceIn(0.0, 1.0)
    }

    private fun currentYear() = Calendar.getInstance().get(Calendar.YEAR)

    private fun names(people: String?): List<String> =
        people?.split(',')?.map { it.trim() }?.filter { it.length > 2 }.orEmpty()
}

data class DetailExtras(
    val details: TmdbDetails,
    val franchiseName: String?,
    val franchise: List<FeedItem>,
    val moreLikeThis: List<FeedItem>,
)
