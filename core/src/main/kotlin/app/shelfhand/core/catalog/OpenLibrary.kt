package app.shelfhand.core.catalog

import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Fills gaps in a free catalog from Open Library's public search. A match needs the title, with its
 * subtitle and reading notes dropped, to equal Open Library's, and an author surname to appear in its
 * author list; anything less is recorded as no match rather than guessed.
 */
object OpenLibrary {
    private const val FIELDS = "key,title,author_name,first_publish_year,subject,series"

    fun searchUrl(title: String, author: String?): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://openlibrary.org/search.json?title=${e(core(title))}" +
            (author?.let { "&author=${e(it)}" } ?: "") + "&limit=5&fields=$FIELDS"
    }

    data class Doc(val key: String, val title: String, val authors: List<String>, val firstYear: Int?, val subjects: List<String>, val series: List<String>)

    fun parse(json: String): List<Doc> {
        val docs = JSONObject(json).optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.optJSONObject(i) ?: return@mapNotNull null
            fun list(k: String) = d.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty()
            Doc(d.optString("key").ifBlank { return@mapNotNull null }, d.optString("title"), list("author_name"),
                if (d.has("first_publish_year")) d.optInt("first_publish_year").takeIf { it in 100..2100 } else null,
                list("subject"), list("series"))
        }
    }

    /** The best matching record turned into what the catalog stores, or null for no confident match. */
    fun match(title: String, authors: List<String>, docs: List<Doc>): CatalogDb.Lookup? {
        if (authors.isEmpty()) return null // a title alone matches too many books
        val want = core(title).let(::norm)
        val surnames = authors.mapNotNull { a -> norm(a).split(' ').lastOrNull { it.length > 1 } }
        val doc = docs.firstOrNull { d ->
            norm(core(d.title)) == want && d.authors.any { a -> val n = norm(a); surnames.any { s -> n.split(' ').contains(s) } }
        } ?: return null
        return CatalogDb.Lookup(doc.key, doc.firstYear, doc.series.firstNotNullOfOrNull(::series), subjects(doc.subjects))
    }

    // Open Library subjects mix genres with shelving notes; keep the ones a listener would filter on.
    private val junk = Regex("""(?i)accessible book|protected daisy|in library|large type|lending library|open library|staff picks|nyt:|overdrive|internet archive|translations into|reading level|^fiction, general$|\d{4}""")

    fun subjects(raw: List<String>): List<String> = raw.asSequence()
        .map { it.trim().trimEnd('.') }
        .filter { it.length in 3..40 && !junk.containsMatchIn(it) }
        .map { s -> s.split(", ").firstOrNull()?.replaceFirstChar { c -> c.titlecase() } ?: s }
        .distinctBy { it.lowercase() }
        .take(8).toList()

    /** "Barsoom series ; 3", "Tarzan (2)", "Oz, #4" or plain "Barsoom". */
    fun series(raw: String): SeriesGuess? {
        val s = raw.trim()
        Regex("""^(.+?)\s*(?:[;,(#]|\bno\.?|\bbook|\bvol\.?)\s*#?\s*(\d+(?:\.\d+)?)\)?\s*$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            return SeriesGuess(clean(m.groupValues[1]), m.groupValues[2].toDoubleOrNull())
        }
        return clean(s).takeIf { it.length >= 2 }?.let { SeriesGuess(it, null) }
    }

    private fun clean(s: String) = s.trim().trimEnd(',', ';', ':', '(', '-').trim().replace(Regex("""(?i)\s+series$"""), "")

    /** The title without its subtitle or a reading note: "Emma (version 2)" and "Emma: A Novel" are both "Emma". */
    fun core(title: String): String = title
        .replace(Regex("""\s*[(\[][^)\]]*[)\]]\s*"""), " ")
        .substringBefore(":").substringBefore(" - ").trim()

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "").lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        .removePrefix("the ").removePrefix("a ").removePrefix("an ")
}
