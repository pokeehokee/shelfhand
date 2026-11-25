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
    /** A direct download: a zip of the book, or one audio file. */
    val downloadUrl: String?,
    val description: String?,
    /** A magnet link or a .torrent address, handed to a torrent app. */
    val torrentUrl: String? = null,
    /** The feed or catalog this came from, by its own name ("LibriVox", "Jane Doe's Stories"). */
    val collection: String? = null,
    /** For a whole serialized book read from a podcast-style feed: the feed to subscribe to. */
    val feedUrl: String? = null,
    /** A series some source actually recorded (Open Library); null means guess from the title. */
    val knownSeries: SeriesGuess? = null,
    /** Fields filled in by the background lookup rather than stated by the source: "series", "year". */
    val lookedUp: Set<String> = emptySet(),
    /** The row in the on-phone catalog, for loading the long description on demand. */
    val dbId: Long? = null,
) {
    /** What a source recorded, else guessed from the title, because most free catalogs do not record series. */
    val series: SeriesGuess? by lazy { knownSeries ?: SeriesGuess.of(title) }
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
enum class Sort { TITLE, SHORTEST, LONGEST, NEWEST_ADDED, AUTHOR, OLDEST_WORK, NEWEST_WORK }
enum class Access { ANY, DOWNLOAD, TORRENT }

data class Filter(
    val text: String = "",
    val minSeconds: Long? = null,
    val maxSeconds: Long? = null,
    val languages: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val genreMatch: GenreMatch = GenreMatch.ANY,
    val excludeGenres: Set<String> = emptySet(),
    val series: SeriesFilter = SeriesFilter.ANY,
    /** Which sources to show; empty means all of them. */
    val collections: Set<String> = emptySet(),
    /** Only books that can be downloaded directly, or only those with a torrent. */
    val access: Access = Access.ANY,
    val sort: Sort = Sort.TITLE,
)
