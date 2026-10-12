package com.shizington.tv.data.search

import com.shizington.tv.data.catalog.CatalogIndex
import com.shizington.tv.data.model.Stream

/**
 * Forgiving title search: ignores accents, punctuation and word order ("amelie" finds "Amélie",
 * "spiderman" finds "Spider-Man"), and tolerates a typo per word once exact matches run out.
 */
class SearchIndex(private val streams: List<Stream>) {
    private val names: Array<String> = Array(streams.size) { CatalogIndex.normalize(streams[it].name) }
    private val squashed: Array<String> = Array(streams.size) { names[it].replace(" ", "") }
    private val people: Array<String?> by lazy { Array(streams.size) { i -> streams[i].people?.let { CatalogIndex.normalize(it) } } }

    val source: List<Stream> get() = streams

    fun titles(query: String, limit: Int = 60): List<Stream> {
        val q = CatalogIndex.normalize(query)
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(' ')
        val qSquashed = q.replace(" ", "")
        val scored = ArrayList<Pair<Int, Int>>()
        for (i in names.indices) {
            val score = score(names[i], squashed[i], q, qSquashed, tokens)
            if (score > 0) scored += i to score
        }
        // Only reach for typo tolerance when the straightforward matches are thin.
        if (scored.size < 8 && tokens.any { it.length >= 4 }) {
            val have = scored.mapTo(HashSet()) { it.first }
            for (i in names.indices) {
                if (i in have) continue
                if (fuzzyTokens(names[i], tokens)) scored += i to 20
            }
        }
        return scored.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { names[it.first].length })
            .take(limit).map { streams[it.first] }
    }

    /**
     * Titles whose cast or director include the person in the query, e.g. "tom hanks".
     * A typo per word is forgiven; the name actually matched comes back so the UI can say so.
     */
    fun byPerson(query: String, limit: Int = 40): PersonMatch? {
        val q = CatalogIndex.normalize(query)
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        if (q.length < 4 || tokens.size < 2) return null
        val exact = collect(" $q ", limit)
        if (exact.isNotEmpty()) return PersonMatch(null, exact)
        // No exact hit: look for a credited name within one edit per word of the query.
        for (i in streams.indices) {
            val raw = streams[i].people ?: continue
            for (person in raw.split(',')) {
                val words = CatalogIndex.normalize(person).split(' ').filter { it.isNotEmpty() }
                if (words.size != tokens.size) continue
                if (words.indices.all { k -> words[k] == tokens[k] || (tokens[k].length >= 3 && editDistanceAtMostOne(words[k], tokens[k])) }) {
                    val name = words.joinToString(" ")
                    return PersonMatch(person.trim(), collect(" $name ", limit))
                }
            }
        }
        return null
    }

    private fun collect(needle: String, limit: Int): List<Stream> {
        val out = ArrayList<Stream>()
        for (i in streams.indices) {
            val p = people[i] ?: continue
            if (" $p ".contains(needle)) {
                out += streams[i]
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun score(name: String, squashedName: String, q: String, qSquashed: String, tokens: List<String>): Int {
        if (name == q) return 100
        if (name.startsWith(q)) return 90
        if (" $name".contains(" $q")) return 80
        if (qSquashed.length >= 3 && squashedName.startsWith(qSquashed)) return 75
        if (name.contains(q)) return 60
        if (qSquashed.length >= 4 && squashedName.contains(qSquashed)) return 55
        if (tokens.size > 1) {
            val words = " $name"
            if (tokens.all { words.contains(" $it") }) return 50
        }
        return 0
    }

    private fun fuzzyTokens(name: String, tokens: List<String>): Boolean {
        val words = name.split(' ')
        return tokens.all { t ->
            words.any { w ->
                w.startsWith(t) || (t.length >= 4 && kotlin.math.abs(w.length - t.length) <= 1 && editDistanceAtMostOne(w, t))
            }
        }
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                // Same length: a substitution, or two swapped letters ("teh" → "the").
                i + 1 < a.length && a[i] == b[j + 1] && a[i + 1] == b[j] -> { i += 2; j += 2 }
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}

/** [correctedName] is set when the query had a typo and we matched the closest credited name. */
data class PersonMatch(val correctedName: String?, val titles: List<Stream>)
