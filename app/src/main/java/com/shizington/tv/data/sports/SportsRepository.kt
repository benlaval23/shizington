package com.shizington.tv.data.sports

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.shizington.tv.data.XtreamRepository
import com.shizington.tv.data.api.asArr
import com.shizington.tv.data.api.asObj
import com.shizington.tv.data.api.str
import com.shizington.tv.data.model.ChannelGroup
import com.shizington.tv.data.model.Category
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.EpgEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/** What the household follows. Order is display order. */
enum class SportTopic(val label: String, private val pattern: Regex) {
    MAN_UTD("Man Utd", Regex("""man(chester)?\.?\s*(utd|united)|\bmutv\b|\bmufc\b|red devils|old trafford""", RegexOption.IGNORE_CASE)),
    ENGLAND("England", Regex("""\bengland\b|three lions|lionesses""", RegexOption.IGNORE_CASE)),
    ENGLAND_RUGBY("England Rugby", Regex("""\bengland\b.*\brugby\b|\brugby\b.*\bengland\b|red roses""", RegexOption.IGNORE_CASE)),
    PREMIER_LEAGUE("Premier League", Regex("""premier league|\bpl match\b|monday night football|super sunday|match of the day|\bmotd\b|football first""", RegexOption.IGNORE_CASE)),
    CHAMPIONS_LEAGUE("Champions League", Regex("""champions league|\bucl\b|europa league|conference league""", RegexOption.IGNORE_CASE)),
    F1("Formula 1", Regex("""formula 1|formula one|\bf1\b|grand prix""", RegexOption.IGNORE_CASE)),
    MOTOGP("MotoGP", Regex("""motogp|\bmoto[23]\b|world superbikes?|\bwsbk\b""", RegexOption.IGNORE_CASE)),
    CRICKET("Cricket", Regex("""cricket|test match|the hundred|\bt20\b|\bodi\b|\bashes\b""", RegexOption.IGNORE_CASE)),
    RUGBY("Rugby", Regex("""rugby|six nations|super league""", RegexOption.IGNORE_CASE)),
    DARTS("Darts", Regex("""\bdarts\b|\bpdc\b""", RegexOption.IGNORE_CASE)),
    SNOOKER("Snooker", Regex("""snooker""", RegexOption.IGNORE_CASE)),
    TENNIS("Tennis", Regex("""tennis|wimbledon|\batp\b|\bwta\b""", RegexOption.IGNORE_CASE)),
    FIGHTING("Boxing & UFC", Regex("""boxing|\bufc\b|fight night""", RegexOption.IGNORE_CASE)),
    GOLF("Golf", Regex("""\bgolf\b|ryder cup|\bthe open\b|\bpga\b""", RegexOption.IGNORE_CASE)),
    RACING("Horse racing", Regex("""horse racing|racing tv|sky sports racing|cheltenham|grand national|royal ascot|\bitv racing\b""", RegexOption.IGNORE_CASE));

    fun matches(text: String) = pattern.containsMatchIn(text)

    companion object {
        /** Teams get top billing; everything else is a sport. */
        val teams = listOf(MAN_UTD, ENGLAND, ENGLAND_RUGBY)
    }
}

/** A guide entry on a sports-carrying channel that matches what the household follows. */
data class SportListing(
    val channel: ChannelGroup,
    val entry: EpgEntry,
    val topics: Set<SportTopic>,
) {
    /** "Live …" or "X v Y" in the title means an actual event rather than a magazine show. */
    val isEvent: Boolean get() = EVENT.containsMatchIn(entry.title) || entry.title.contains("live", ignoreCase = true)

    companion object {
        private val EVENT = Regex("""\s(v|vs\.?)\s""", RegexOption.IGNORE_CASE)
    }
}

data class Fixture(
    val home: String,
    val away: String,
    val homeBadge: String?,
    val awayBadge: String?,
    val league: String?,
    val kickoffMs: Long,
    val venue: String?,
    val thumb: String?,
    val homeScore: Int?,
    val awayScore: Int?,
    val finished: Boolean,
) {
    val title: String get() = "$home v $away"
}

data class TeamCard(
    val topic: SportTopic,
    val name: String,
    val badge: String?,
    val fanart: String?,
    val next: Fixture?,
    val last: Fixture?,
    /** The guide listing that most likely shows [next], once it's in the guide. */
    val nextOnTv: SportListing?,
)

data class SportsFeed(
    val teams: List<TeamCard>,
    val onNow: List<SportListing>,
    val upcoming: List<SportListing>,
    val byTopic: Map<SportTopic, List<SportListing>>,
    val missed: List<SportListing>,
    val mutv: ChannelGroup?,
    val updatedAt: Long,
    /** False while the TV guide rows are still coming in (the screen shows placeholders for them). */
    val listingsLoaded: Boolean = true,
    /** False while catch-up is still being checked. */
    val missedLoaded: Boolean = true,
) {
    /** The same feed seen from [now]: finished programmes drop out, ones that have started move to "On now". */
    fun at(now: Long): SportsFeed {
        val all = (onNow + upcoming).filter { it.entry.endMs > now }
        return copy(
            onNow = all.filter { it.entry.startMs <= now },
            upcoming = all.filter { it.entry.startMs > now },
            byTopic = byTopic.mapValues { (_, list) -> list.filter { it.entry.endMs > now } }.filterValues { it.isNotEmpty() },
        )
    }
}

/**
 * Builds the Sport feed from two sources: the provider's own TV guide (what's actually on the channels
 * you can watch) and TheSportsDB's free fixtures API (next/last match for Man Utd and England, with badges).
 *
 * The guide is one request per channel and the provider only tolerates a few at a time, so a full build takes
 * 10-40 s on a Fire TV Stick. To keep the screen quick, [live] is updated in stages (team cards first, then the
 * busiest channels, then the rest, then catch-up) and the last complete feed is saved to [snapshotFile] so the
 * next launch shows it straight away while a fresh one builds.
 */
class SportsRepository(private val xtream: XtreamRepository, private val http: OkHttpClient, private val snapshotFile: File) {
    private val mutex = Mutex()
    @Volatile private var cached: SportsFeed? = null
    private val gson = Gson()

    private val _live = MutableStateFlow<SportsFeed?>(null)

    /** The newest feed, possibly part-built or restored from disk. */
    val live: StateFlow<SportsFeed?> = _live.asStateFlow()

    fun peek(): SportsFeed? = _live.value

    suspend fun feed(force: Boolean = false): SportsFeed = mutex.withLock {
        cached?.takeIf { !force && System.currentTimeMillis() - it.updatedAt < 20 * 60_000L }?.let { return it }
        if (_live.value == null) restore()?.let { _live.value = it }
        build().also {
            cached = it
            _live.value = it
            save(it)
        }
    }

    private suspend fun restore(): SportsFeed? = withContext(Dispatchers.IO) {
        runCatching {
            if (!snapshotFile.exists()) return@runCatching null
            val feed = snapshotFile.reader().use { gson.fromJson(it, SportsFeed::class.java) } ?: return@runCatching null
            // A day-old guide is mostly finished programmes; not worth showing.
            if (System.currentTimeMillis() - feed.updatedAt > 18 * 3_600_000L) null else feed.at(System.currentTimeMillis())
        }.getOrNull()
    }

    private suspend fun save(feed: SportsFeed) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(snapshotFile.parentFile, snapshotFile.name + ".tmp")
            tmp.writer().use { gson.toJson(feed, it) }
            tmp.renameTo(snapshotFile)
        }
    }

    /** Publish a part-built feed, unless a restored one would be replaced by something emptier. */
    private fun publish(feed: SportsFeed) {
        val shown = _live.value
        if (shown != null && shown.listingsLoaded && !feed.listingsLoaded) {
            // Keep the restored rows on screen; only refresh the team cards.
            _live.value = shown.copy(teams = feed.teams.map { t -> t.copy(nextOnTv = shown.teams.firstOrNull { it.topic == t.topic && it.next?.kickoffMs == t.next?.kickoffMs }?.nextOnTv) })
        } else {
            _live.value = feed
        }
    }

    private suspend fun build(): SportsFeed = coroutineScope {
        val teamsJob = async { teamCards() }
        val channelsJob = async {
            val categories = runCatching { xtream.categories(ContentType.LIVE) }.getOrDefault(emptyList())
            val streams = runCatching { xtream.allStreams(ContentType.LIVE) }.getOrDefault(emptyList())
            val mutv = ChannelGroup.group(streams.filter { it.name.contains("MUTV", ignoreCase = true) }).firstOrNull()
            sportsChannels(categories, streams) to mutv
        }
        val empty = SportsFeed(emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), null, System.currentTimeMillis(), listingsLoaded = false, missedLoaded = false)

        // Stage 1: fixtures come from TheSportsDB in a second or two.
        val teams = teamsJob.await()
        publish(empty.copy(teams = teams))

        // Stage 2: the guide, the UK channels first (they're sorted to the front) so the main rows fill early.
        val (channels, mutvFallback) = channelsJob.await()
        val mutv = channels.firstOrNull { it.name.contains("MUTV", ignoreCase = true) } ?: mutvFallback
        suspend fun guide(batch: List<ChannelGroup>) =
            batch.map { ch -> async { xtream.shortEpg(ch.epgStream.id, limit = 10).mapNotNull { e -> tag(ch, e) } } }.awaitAll().flatten()
        val listings = ArrayList(guide(channels.take(16)))
        if (channels.size > 16) {
            publish(assemble(teams, listings, mutv, missed = emptyList(), missedLoaded = false))
            listings += guide(channels.drop(16))
        }
        val withRows = assemble(teams, listings, mutv, missed = emptyList(), missedLoaded = false)
        publish(withRows)

        // Stage 3: catch-up, which is the heaviest request per channel.
        withRows.copy(missed = missed(channels), missedLoaded = true, updatedAt = System.currentTimeMillis())
    }

    private fun assemble(teamCards: List<TeamCard>, listings: List<SportListing>, mutv: ChannelGroup?, missed: List<SportListing>, missedLoaded: Boolean): SportsFeed {
        val now = System.currentTimeMillis()
        val onNow = listings.filter { it.entry.startMs <= now && it.entry.endMs > now }
            .sortedWith(compareByDescending<SportListing> { priority(it) }.thenBy { it.channel.num ?: Int.MAX_VALUE })
        val upcoming = listings.filter { it.entry.startMs in (now + 1)..(now + 36 * 3_600_000L) }
            .sortedWith(compareBy<SportListing> { it.entry.startMs }.thenByDescending { priority(it) })
        val byTopic = SportTopic.entries.associateWith { topic ->
            (onNow + upcoming).filter { topic in it.topics }.distinctBy { it.channel.key to it.entry.startMs }
        }.filterValues { it.isNotEmpty() }

        val teams = teamCards.map { card ->
            val next = card.next ?: return@map card
            val nearKickoff = listings.filter { kotlin.math.abs(it.entry.startMs - next.kickoffMs) <= 45 * 60_000L || (it.entry.startMs <= next.kickoffMs && it.entry.endMs > next.kickoffMs) }
            val tv = nearKickoff.filter { l ->
                val otherCode = card.topic == SportTopic.ENGLAND && SportTopic.RUGBY in l.topics
                !otherCode && (card.topic in l.topics || mentions(l, next.home) || mentions(l, next.away))
            }.maxByOrNull { priority(it) }
            card.copy(nextOnTv = tv)
        }
        return SportsFeed(teams, onNow, upcoming, byTopic, missed, mutv, now, listingsLoaded = true, missedLoaded = missedLoaded)
    }

    /** Recorded matches for the household's teams/competitions from the last few days (catch-up). */
    private suspend fun missed(channels: List<ChannelGroup>): List<SportListing> = coroutineScope {
        val archived = channels.filter { it.archiveDays > 0 }.take(14)
        val now = System.currentTimeMillis()
        archived.map { ch ->
            async {
                val stream = ch.variants.firstOrNull { it.archiveDays > 0 } ?: return@async emptyList()
                runCatching { xtream.catchUp(stream.id) }.getOrDefault(emptyList())
                    .filter { it.endMs < now }
                    .mapNotNull { e -> tag(ch, e)?.takeIf { l -> l.isEvent && (SportTopic.MAN_UTD in l.topics || SportTopic.ENGLAND in l.topics || l.topics.any { it in MAJOR }) } }
            }
        }.awaitAll().flatten()
            .sortedWith(compareByDescending<SportListing> { priority(it) }.thenByDescending { it.entry.startMs })
            .distinctBy { it.entry.title.lowercase() }
            .take(20)
    }

    private fun tag(ch: ChannelGroup, e: EpgEntry): SportListing? {
        val text = "${e.title} ${e.description.orEmpty()}"
        val topics = SportTopic.entries.filter { it.matches(text) }.toMutableSet()
        // MUTV is all Man Utd; dedicated F1/cricket/racing channels imply their sport.
        if (ch.name.contains("MUTV", true)) topics += SportTopic.MAN_UTD
        if (ch.name.contains("F1", true)) topics += SportTopic.F1
        // Bike races are "grand prix" too; only call them F1 on an F1 channel.
        if (SportTopic.MOTOGP in topics && !ch.name.contains("F1", true)) topics -= SportTopic.F1
        if (ch.name.contains("Cricket", true)) topics += SportTopic.CRICKET
        if (ch.name.contains("Racing", true)) topics += SportTopic.RACING
        // "England" on a cricket or rugby channel is still England, which is what we want.
        if (SportTopic.ENGLAND in topics && SportTopic.RUGBY in topics) topics += SportTopic.ENGLAND_RUGBY
        return if (topics.isEmpty()) null else SportListing(ch, e, topics)
    }

    private fun priority(l: SportListing): Int {
        var p = 0
        if (SportTopic.MAN_UTD in l.topics) p += 100
        if (SportTopic.ENGLAND in l.topics) p += 80
        if (l.topics.any { it in MAJOR }) p += 30
        if (l.isEvent) p += 50
        if (l.channel.name.startsWith("UK", ignoreCase = true)) p += 5
        return p
    }

    private fun mentions(l: SportListing, team: String): Boolean {
        val key = team.split(' ').firstOrNull { it.length >= 4 } ?: team
        return l.entry.title.contains(key, ignoreCase = true) || l.entry.description?.contains(key, ignoreCase = true) == true
    }

    /** UK sports channels with guide data, one per channel (quality variants folded), plus the big terrestrials. */
    private fun sportsChannels(categories: List<Category>, streams: List<com.shizington.tv.data.model.Stream>): List<ChannelGroup> {
        val sportCats = categories.filter { SPORT_CATEGORY.containsMatchIn(it.name) }.map { it.id }.toSet()
        val candidates = streams.filter { s ->
            s.epgChannelId != null && (s.categoryId in sportCats || TERRESTRIAL.containsMatchIn(s.name))
        }
        // Providers list the same channel many ways (HD, SD, "Hevc", "FHD 50FPS"). They share one guide, so
        // fetch it once: ~240 streams come down to ~100 guide requests, and "On now" stops showing duplicates.
        return ChannelGroup.group(candidates)
            .filterNot { PLUS_ONE.containsMatchIn(it.name) }
            .sortedWith(
                compareByDescending<ChannelGroup> { if (it.name.startsWith("UK", true)) 1 else 0 }
                    .thenBy { if (it.name.contains("hevc", true)) 1 else 0 }
                    .thenBy { it.num ?: Int.MAX_VALUE },
            )
            .distinctBy { it.epgStream.epgChannelId?.lowercase() ?: it.key }
            .take(110)
    }

    // ---- TheSportsDB (free tier: next and last fixture per team) ----

    private suspend fun teamCards(): List<TeamCard> = coroutineScope {
        TEAMS.map { (topic, id) ->
            async {
                val team = fetch("lookupteam.php?id=$id")?.get("teams").asArr()?.firstOrNull().asObj()
                val next = fetch("eventsnext.php?id=$id")?.get("events").asArr()?.mapNotNull { fixture(it.asObj()) }
                    ?.filter { !it.finished }?.minByOrNull { it.kickoffMs }
                val last = fetch("eventslast.php?id=$id")?.get("results").asArr()?.mapNotNull { fixture(it.asObj()) }
                    ?.maxByOrNull { it.kickoffMs }
                TeamCard(topic, team?.str("strTeam") ?: topic.label, team?.str("strBadge"), team?.str("strFanart1") ?: team?.str("strBanner"), next, last, null)
            }
        }.awaitAll()
    }

    private fun fixture(o: com.google.gson.JsonObject?): Fixture? {
        o ?: return null
        val ts = o.str("strTimestamp")?.let { runCatching { LocalDateTime.parse(it.take(19)).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
            ?: runCatching { LocalDateTime.parse("${o.str("dateEvent")}T${o.str("strTime")?.take(8) ?: "00:00:00"}").toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: return null
        val hs = o.str("intHomeScore")?.toIntOrNull()
        val aws = o.str("intAwayScore")?.toIntOrNull()
        return Fixture(
            // National rugby sides come back as "Australia Rugby"; the card already says it's rugby.
            home = o.str("strHomeTeam")?.removeSuffix(" Rugby") ?: return null,
            away = o.str("strAwayTeam")?.removeSuffix(" Rugby") ?: return null,
            homeBadge = o.str("strHomeTeamBadge"),
            awayBadge = o.str("strAwayTeamBadge"),
            league = o.str("strLeague"),
            kickoffMs = ts,
            venue = o.str("strVenue"),
            thumb = o.str("strThumb"),
            homeScore = hs,
            awayScore = aws,
            finished = hs != null && aws != null || o.str("strStatus").equals("FT", ignoreCase = true),
        )
    }

    private suspend fun fetch(path: String) = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("https://www.thesportsdb.com/api/v1/json/3/$path").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.let { JsonParser.parseString(it).asObj() } else null
            }
        }.getOrNull()
    }

    companion object {
        private val TEAMS = listOf(SportTopic.MAN_UTD to 133612L, SportTopic.ENGLAND to 133914L, SportTopic.ENGLAND_RUGBY to 137123L)
        private val MAJOR = setOf(SportTopic.PREMIER_LEAGUE, SportTopic.CHAMPIONS_LEAGUE, SportTopic.F1)
        private val SPORT_CATEGORY = Regex("""^uk[: ]+sport|uk sports|sky sports|live football|international football|ppv|live events|rugby|\bf1\b|motor sport|darts|tennis|wimbledon|wst|matchroom|ufc|world cup|cricket|boxing|golf|racing""", RegexOption.IGNORE_CASE)
        // The national feeds only: "BBC One Wales", "ITV Anglia East" etc. carry the same sport under another guide id.
        private val TERRESTRIAL = Regex("""^uk\s*(fhd|hd|sd)?\s*:\s*(bbc one|bbc two|itv ?1?|itv4|channel 4|channel 5|mutv|premier sports ?\d?)\s*(hd|fhd)?\s*$""", RegexOption.IGNORE_CASE)
        private val PLUS_ONE = Regex("""\+\s*1\b""")
    }
}
