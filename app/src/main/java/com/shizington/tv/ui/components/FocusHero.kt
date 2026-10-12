package com.shizington.tv.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shizington.tv.AppContainer
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.Stream
import com.shizington.tv.data.model.formatRating
import com.shizington.tv.data.tmdb.TmdbTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Banner that follows focus and upgrades itself with TMDB artwork once focus settles. */
class FocusHero(private val c: AppContainer, private val scope: CoroutineScope) {
    var hero by mutableStateOf<HeroData?>(null)
        private set
    private var job: Job? = null

    fun show(s: Stream, reason: String? = null, tmdbId: Long? = null, label: String? = null) {
        job?.cancel()
        hero = HeroData(
            key = s.key,
            label = label ?: if (s.type == ContentType.SERIES) "Series" else "Movie",
            title = s.name,
            image = s.backdrop ?: s.icon,
            meta = listOfNotNull(s.year, s.runtimeMin?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") }, formatRating(s.rating), s.genres.firstOrNull()),
            plot = s.plot,
            reason = reason,
        )
        job = scope.launch {
            delay(450)
            val type = if (s.type == ContentType.SERIES) ContentType.SERIES else ContentType.MOVIE
            val id = tmdbId ?: c.tmdb.findId(type, s.id, s.name, s.year?.toIntOrNull()) ?: return@launch
            val d = c.tmdb.details(type, id) ?: return@launch
            if (hero?.key == s.key) hero = hero?.copy(logo = d.logo, image = d.backdrop ?: hero?.image, meta = listOfNotNull(d.certification) + (hero?.meta ?: emptyList()))
        }
    }

    fun show(t: TmdbTitle, label: String, reason: String?) {
        job?.cancel()
        hero = HeroData(
            key = "tmdb:${t.id}",
            label = label,
            title = t.title,
            image = t.backdrop ?: t.poster,
            meta = listOfNotNull(t.releaseDate?.let { formatDate(it) }, t.vote.takeIf { it > 0 }?.let { "★ %.1f".format(it) }),
            plot = t.overview,
            reason = reason,
        )
        job = scope.launch {
            delay(450)
            val d = c.tmdb.details(t.type, t.id) ?: return@launch
            if (hero?.key == "tmdb:${t.id}") hero = hero?.copy(logo = d.logo, image = d.backdrop ?: hero?.image, meta = listOfNotNull(d.certification) + (hero?.meta ?: emptyList()))
        }
    }

    fun set(h: HeroData?) {
        job?.cancel()
        hero = h
    }

    private fun formatDate(iso: String): String = runCatching {
        java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK))
    }.getOrDefault(iso)
}
