package app.shelfhand.core.catalog

/**
 * One book in a free catalog. Only what the source's own API reports; a value the source
 * leaves out is null, never zero, so a filter on length never treats "unknown" as "short".
 */
data class CatalogBook(
    val source: String,
    val id: String,
    val title: String,
    val authors: List<String>,
    val language: String?,
    val genres: List<String>,
    val seconds: Long?,
    val sections: Int?,
    val year: Int?,
    val pageUrl: String?,
    val zipUrl: String?,
    val description: String?,
) {
    /** Guessed from the title, because no free catalog we read records series. */
    val series: SeriesGuess? by lazy { SeriesGuess.of(title) }
}

data class SeriesGuess(val name: String, val index: Double?) {
    companion object {
        private val patterns = listOf(
            // "The Lost World (Book 2)", "Barsoom, Volume 3", "Tarzan #4"
            Regex("""^(.+?)[\s,:(\-]+(?:book|vol\.?|volume|part|no\.?|number|#)\s*(\d+(?:\.\d+)?)\)?\s*$""", RegexOption.IGNORE_CASE),
            // "Tom Swift 12: Tom Swift and His Airship", "Oz 3 - Ozma of Oz"
            Regex("""^(.+?)\s+(\d+(?:\.\d+)?)\s*[:\-]\s+.+$"""),
            // "Anne of Green Gables Series, Book 1: ..."
            Regex("""^(.+?)\s+series[\s,]+(?:book\s*)?(\d+(?:\.\d+)?)\b.*$""", RegexOption.IGNORE_CASE),
        )
        private val roman = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10)
        private val romanVol = Regex("""^(.+?)[\s,:(\-]+(?:vol\.?|volume|part|book)\s+([ivx]+)\)?\s*$""", RegexOption.IGNORE_CASE)

        fun of(title: String): SeriesGuess? {
            val t = title.trim()
            for (p in patterns) {
                val m = p.matchEntire(t) ?: continue
                val name = m.groupValues[1].trim().trimEnd(',', ':', '-', '(').trim()
                // A bare number after a long title is usually a year or part of the name ("Nineteen 84"), so need a name.
                if (name.length < 2) continue
                if (m.groupValues[2].length == 4) continue // "Poems 1912: ..." is a year, not book 1912
                return SeriesGuess(name, m.groupValues[2].toDoubleOrNull())
            }
            romanVol.matchEntire(t)?.let { m ->
                val n = roman[m.groupValues[2].lowercase()] ?: return@let
                return SeriesGuess(m.groupValues[1].trim().trimEnd(',', ':', '-').trim(), n.toDouble())
            }
            return null
        }
    }
}

enum class SeriesFilter { ANY, SERIES_ONLY, STANDALONE_ONLY }
enum class GenreMatch { ANY, ALL }
enum class Sort { TITLE, SHORTEST, LONGEST, NEWEST_ADDED, AUTHOR }

data class Filter(
    val text: String = "",
    val minSeconds: Long? = null,
    val maxSeconds: Long? = null,
    val languages: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val genreMatch: GenreMatch = GenreMatch.ANY,
    val excludeGenres: Set<String> = emptySet(),
    val series: SeriesFilter = SeriesFilter.ANY,
    val sort: Sort = Sort.TITLE,
)

object CatalogFilter {
    fun apply(books: List<CatalogBook>, f: Filter): List<CatalogBook> {
        val words = f.text.lowercase().split(' ').filter { it.isNotBlank() }
        val lengthSet = f.minSeconds != null || f.maxSeconds != null
        val out = books.filter { b ->
            (words.isEmpty() || words.all { w ->
                b.title.lowercase().contains(w) || b.authors.any { it.lowercase().contains(w) } ||
                    b.genres.any { it.lowercase().contains(w) }
            }) &&
                // A book of unknown length is dropped once any length bound is set: we cannot say it fits.
                (!lengthSet || (b.seconds != null &&
                    (f.minSeconds == null || b.seconds >= f.minSeconds) &&
                    (f.maxSeconds == null || b.seconds <= f.maxSeconds))) &&
                (f.languages.isEmpty() || b.language in f.languages) &&
                (f.genres.isEmpty() || when (f.genreMatch) {
                    GenreMatch.ANY -> b.genres.any { it in f.genres }
                    GenreMatch.ALL -> b.genres.containsAll(f.genres)
                }) &&
                b.genres.none { it in f.excludeGenres } &&
                when (f.series) {
                    SeriesFilter.ANY -> true
                    SeriesFilter.SERIES_ONLY -> b.series != null
                    SeriesFilter.STANDALONE_ONLY -> b.series == null
                }
        }
        return when (f.sort) {
            Sort.TITLE -> out.sortedBy { it.title.lowercase().removePrefix("the ") }
            Sort.AUTHOR -> out.sortedBy { it.authors.firstOrNull()?.substringAfterLast(' ')?.lowercase() ?: "￿" }
            // Unknown lengths go last in both directions.
            Sort.SHORTEST -> out.sortedWith(compareBy(nullsLast()) { it.seconds })
            Sort.LONGEST -> out.sortedWith(compareBy<CatalogBook, Long?>(nullsLast(reverseOrder())) { it.seconds })
            Sort.NEWEST_ADDED -> out.sortedByDescending { it.id.toLongOrNull() ?: 0L }
        }
    }

    /** Genre names with how many books carry each, most common first, for the filter chips. */
    fun genreCounts(books: List<CatalogBook>): List<Pair<String, Int>> =
        books.flatMap { it.genres }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    fun languageCounts(books: List<CatalogBook>): List<Pair<String, Int>> =
        books.mapNotNull { it.language }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key to it.value }
}
