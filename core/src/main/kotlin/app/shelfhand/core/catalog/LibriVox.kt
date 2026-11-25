package app.shelfhand.core.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * LibriVox's public catalog API (librivox.org/api/feed/audiobooks). Every book on LibriVox is
 * public domain and the site publishes this feed so apps can list it, which is why it is a
 * source and an arbitrary website is not.
 */
object LibriVox {
    const val SOURCE = "librivox"
    const val PAGE = 1000

    private const val FIELDS =
        "{id,title,description,language,copyright_year,num_sections,totaltimesecs,url_librivox,url_zip_file,authors,genres}"

    fun pageUrl(offset: Int, limit: Int = PAGE): String =
        "https://librivox.org/api/feed/audiobooks?format=json&extended=1&limit=$limit&offset=$offset&fields=" +
            java.net.URLEncoder.encode(FIELDS, "UTF-8")

    /**
     * One page of the feed. Past the last book the API answers with an `error` object rather than
     * an empty list, so both mean "no more", and anything else unreadable throws.
     */
    fun parse(json: String): List<CatalogBook> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("books") ?: return if (root.has("error")) emptyList()
            else throw IllegalArgumentException("LibriVox answered without a books list")
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::book) }
    }

    private fun book(o: JSONObject): CatalogBook? {
        val id = o.s("id").ifBlank { return null }
        val title = o.s("title").trim().ifBlank { return null }
        return CatalogBook(
            source = SOURCE,
            id = id,
            title = title,
            authors = names(o.optJSONArray("authors")),
            language = o.s("language").trim().ifBlank { null },
            genres = o.optJSONArray("genres")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.s("name")?.trim()?.ifBlank { null } }.distinct()
            } ?: emptyList(),
            // Sent as a number or a numeric string depending on the record; 0 means not recorded.
            seconds = o.s("totaltimesecs")?.toLongOrNull()?.takeIf { it > 0 },
            sections = o.s("num_sections")?.toIntOrNull()?.takeIf { it > 0 },
            year = o.s("copyright_year")?.toIntOrNull()?.takeIf { it in 1000..2100 },
            pageUrl = o.s("url_librivox").ifBlank { null },
            downloadUrl = o.s("url_zip_file").ifBlank { null },
            description = o.s("description").ifBlank { null }?.let(::stripTags),
            collection = "LibriVox",
        )
    }

    private fun names(a: JSONArray?): List<String> = a?.let {
        (0 until it.length()).mapNotNull { i ->
            val p = it.optJSONObject(i) ?: return@mapNotNull null
            listOf(p.s("first_name"), p.s("last_name")).map(String::trim).filter(String::isNotEmpty)
                .joinToString(" ").ifBlank { null }
        }
    } ?: emptyList()

    /** A JSON null reads as "" here; Android's optString would hand back the word "null". */
    private fun JSONObject.s(k: String): String = if (!has(k) || isNull(k)) "" else opt(k).toString()

    private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#039;", "'").replace(Regex("\\s+"), " ").trim()
}

/** A compact line-per-book cache, so the app does not re-download a catalog to start up. */
object CatalogCache {
    private const val SEP = '\u001f'
    private const val LIST = '\u001e'
    private const val VERSION = "v2"
    private const val FIELDS = 15

    fun write(books: List<CatalogBook>): String = buildString {
        append(VERSION).append('\n')
        books.forEach { b ->
            append(listOf(
                b.source, b.id, b.title, b.authors.joinToString(LIST.toString()), b.language ?: "",
                b.genres.joinToString(LIST.toString()), b.seconds?.toString() ?: "", b.sections?.toString() ?: "",
                b.year?.toString() ?: "", b.pageUrl ?: "", b.downloadUrl ?: "", b.description ?: "",
                b.torrentUrl ?: "", b.collection ?: "", b.feedUrl ?: "",
            ).joinToString(SEP.toString()) { it.replace('\n', ' ').replace('\r', ' ').replace(SEP, ' ') })
            append('\n')
        }
    }

    /** An older or damaged cache reads as empty, which means "download again", never a crash. */
    fun read(text: String): List<CatalogBook> {
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull() != VERSION) return emptyList()
        return lines.drop(1).filter { it.isNotEmpty() }.mapNotNull { line ->
            val f = line.split(SEP)
            if (f.size < FIELDS) return@mapNotNull null
            fun list(s: String) = if (s.isEmpty()) emptyList() else s.split(LIST)
            CatalogBook(
                source = f[0], id = f[1], title = f[2], authors = list(f[3]), language = f[4].ifEmpty { null },
                genres = list(f[5]), seconds = f[6].toLongOrNull(), sections = f[7].toIntOrNull(), year = f[8].toIntOrNull(),
                pageUrl = f[9].ifEmpty { null }, downloadUrl = f[10].ifEmpty { null }, description = f[11].ifEmpty { null },
                torrentUrl = f[12].ifEmpty { null }, collection = f[13].ifEmpty { null }, feedUrl = f[14].ifEmpty { null },
            )
        }
    }
}
