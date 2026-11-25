package app.shelfhand.core

/**
 * Reads author, series and title from where a book sits under the library root.
 *
 * Understood layouts (segments relative to the root, last one is the book folder):
 *  - `Author/Series 03 - Title`      series and index in the folder name
 *  - `Author/Series/03 - Title`      series as its own folder
 *  - `Author/Series/Book 3 - Title`  "Book N" prefix
 *  - `Author/Title`
 *  - `Author - Title`                 single folder
 *  - `Title`
 * Anything else falls back to the folder name as the title and no author.
 */
object PathParser {
    private val indexPrefix = Regex("""^(?:book|vol(?:ume)?|part|#)?\s*(\d+(?:\.\d+)?)\s*[-–.:]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val seriesThenIndex = Regex("""^(.+?)\s+(?:book\s*|#)?(\d+(?:\.\d+)?)\s*[-–:]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val authorDashTitle = Regex("""^(.+?)\s+[-–]\s+(.+)$""")
    private val noise = Regex("""\s*[\[(](?:unabridged|abridged|m4b|mp3|\d+\s*kbps)[\])]\s*""", RegexOption.IGNORE_CASE)

    fun parse(segments: List<String>): BookIdentity {
        val s = segments.map { clean(it) }.filter { it.isNotEmpty() }
        require(s.isNotEmpty()) { "a book folder has a name" }
        val leaf = s.last()
        return when (s.size) {
            1 -> authorDashTitle.matchEntire(leaf)?.let { BookIdentity(it.groupValues[1], null, null, it.groupValues[2]) }
                ?: BookIdentity(null, null, null, leaf)
            2 -> fromLeaf(author = s[0], series = null, leaf = leaf)
            else -> fromLeaf(author = s[s.size - 3], series = s[s.size - 2], leaf = leaf)
        }
    }

    private fun fromLeaf(author: String, series: String?, leaf: String): BookIdentity {
        if (series != null) {
            indexPrefix.matchEntire(leaf)?.let { return BookIdentity(author, series, it.groupValues[1].toDouble(), it.groupValues[2]) }
            return BookIdentity(author, series, null, leaf)
        }
        seriesThenIndex.matchEntire(leaf)?.let {
            return BookIdentity(author, it.groupValues[1], it.groupValues[2].toDouble(), it.groupValues[3])
        }
        return BookIdentity(author, null, null, leaf)
    }

    private fun clean(s: String) = s.replace(noise, " ").replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
}
