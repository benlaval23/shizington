package com.shizington.tv.data.model

enum class ContentType { LIVE, MOVIE, SERIES, EPISODE }

data class Credentials(
    val server: String,
    val username: String,
    val password: String,
    /** Alternate hosts for the same account, tried in order when [server] is unreachable. */
    val backupServers: List<String> = emptyList(),
) {
    val servers: List<String> get() = (listOf(server) + backupServers).distinct()

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
    /** Provider timezone (e.g. "Europe/London"); catch-up start times must be expressed in it. */
    val timezone: String? = null,
) {
    fun daysLeft(now: Long = System.currentTimeMillis()): Long? =
        expiresAt?.let { ((it - now) / 86_400_000L).coerceAtLeast(0) }
}

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
    /** When it was added (movies) or last updated (series), epoch seconds. */
    val added: Long = 0,
    val trailer: String? = null,
    /** Live channels: days of catch-up the provider keeps, 0 when none. */
    val archiveDays: Int = 0,
    val hasCast: Boolean = false,
) {
    val key: String get() = "${type.name}:$id"
    val ratingValue: Double get() = rating?.toDoubleOrNull() ?: 0.0
    val genres: List<String> get() = genre?.split(',', '/', '&')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
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
    val trailer: String? = null,
    val country: String? = null,
    val ageRating: String? = null,
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
    val trailer: String? = null,
)

data class Season(
    val number: Int,
    val name: String,
    val cover: String?,
    val episodes: List<Episode>,
    val overview: String? = null,
    val airDate: String? = null,
)

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

data class EpgEntry(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    /** True when the provider has a recording of this programme (catch-up). */
    val hasArchive: Boolean = false,
) {
    val durationMinutes: Int get() = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(1)

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
    /** Other qualities of the same live channel (e.g. FHD / HD / SD), including this one. */
    val variants: List<ChannelVariant> = emptyList(),
    val quality: String? = null,
    /** Catch-up: plays the recording of [programme] instead of the live feed. */
    val archiveStartMs: Long? = null,
    val archiveMinutes: Int? = null,
    val programme: String? = null,
    val archiveDays: Int = 0,
    /** The variant the provider records for catch-up (not always the one being played). */
    val archiveStreamId: Long? = null,
) {
    val isCatchUp: Boolean get() = archiveStartMs != null
    val title: String
        get() = when {
            type == ContentType.EPISODE && seriesName != null -> "$seriesName · S${season ?: 0}E${episode ?: 0}"
            programme != null -> "$name · $programme"
            else -> name
        }
}

data class ChannelVariant(val id: Long, val quality: String?, val name: String, val icon: String?)

/**
 * One channel as the viewer sees it. Providers list each quality as its own stream
 * ("UK FHD : BBC One", "UK HD : BBC One", "UK SD : BBC One"); this folds them together.
 */
data class ChannelGroup(
    val key: String,
    val name: String,
    val num: Int?,
    val icon: String?,
    /** Best quality first. */
    val variants: List<Stream>,
) {
    val qualities: List<String> get() = variants.mapNotNull { qualityOf(it.name) }.distinct()
    val archiveDays: Int get() = variants.maxOf { it.archiveDays }
    /** Short-EPG is keyed by stream; prefer a variant the provider has guide data for. */
    val epgStream: Stream get() = variants.firstOrNull { it.epgChannelId != null } ?: variants.first()
    val ids: Set<Long> get() = variants.map { it.id }.toSet()

    fun preferred(quality: String): Stream = when (quality) {
        "sd" -> variants.lastOrNull { qualityOf(it.name) == "SD" } ?: variants.last()
        "hd" -> variants.firstOrNull { qualityOf(it.name) == "HD" } ?: variants.firstOrNull { qualityOf(it.name) != "FHD" && qualityOf(it.name) != "4K" } ?: variants.last()
        else -> variants.first()
    }

    fun toPlayable(quality: String): Playable {
        val s = preferred(quality)
        return Playable(
            type = ContentType.LIVE,
            id = s.id,
            name = name,
            icon = s.icon ?: icon,
            extension = null,
            num = num,
            variants = variants.map { ChannelVariant(it.id, qualityOf(it.name), name, it.icon ?: icon) },
            quality = qualityOf(s.name),
            archiveDays = archiveDays,
            archiveStreamId = variants.firstOrNull { it.archiveDays > 0 }?.id,
        )
    }

    companion object {
        private val pattern = Regex("""^\s*([A-Za-z]{2,8})\s*(4K|UHD|FHD|HD|SD|HEVC)?\s*[:|\-]\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
        private val rank = mapOf("4K" to 0, "UHD" to 0, "FHD" to 1, "HEVC" to 2, "HD" to 2, "SD" to 3)

        fun qualityOf(name: String): String? = pattern.find(name)?.groupValues?.get(2)?.uppercase()?.takeIf { it.isNotEmpty() }
            ?.let { if (it == "UHD") "4K" else it }

        /** Groups variants of the same channel, keeping the provider's order of first appearance. */
        fun group(streams: List<Stream>): List<ChannelGroup> {
            val groups = LinkedHashMap<String, MutableList<Stream>>()
            val names = HashMap<String, String>()
            for (s in streams) {
                val m = pattern.find(s.name)
                val (key, display) = if (m != null && m.groupValues[2].isNotEmpty()) {
                    val prefix = m.groupValues[1].uppercase()
                    val base = m.groupValues[3]
                    "$prefix|${base.lowercase()}" to "$prefix: $base"
                } else {
                    "id|${s.id}" to s.name
                }
                groups.getOrPut(key) { mutableListOf() } += s
                names.putIfAbsent(key, display)
            }
            return groups.map { (key, list) ->
                val sorted = list.sortedBy { rank[qualityOf(it.name)] ?: 2 }
                ChannelGroup(
                    key = key,
                    name = names.getValue(key),
                    num = list.mapNotNull { it.num }.minOrNull(),
                    icon = sorted.firstNotNullOfOrNull { it.icon },
                    variants = sorted,
                )
            }
        }
    }
}

data class PlaybackRequest(val items: List<Playable>, val index: Int, val startPositionMs: Long = 0L)

fun Stream.toPlayable() = Playable(type, id, name, icon, extension, num)

fun formatRating(raw: String?): String? {
    val v = raw?.toDoubleOrNull() ?: return null
    if (v <= 0.0) return null
    return "★ " + if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)
}
