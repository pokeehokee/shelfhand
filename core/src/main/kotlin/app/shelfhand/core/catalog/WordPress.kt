package app.shelfhand.core.catalog

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * WordPress's public posts listing, `/wp-json/wp/v2/posts`: 100 posts a page with a total count. Used
 * when a site's feed will not page. It has no enclosures, so audio, zip and torrent links are read from
 * each post's own links, and categories come from the embedded terms.
 */
object WordPress {
    const val PER_PAGE = 100

    /** The listing address for a site, from any address on it. Null if it is not a web address. */
    fun postsUrl(siteUrl: String, page: Int): String? = runCatching {
        val u = URI(siteUrl)
        if (u.scheme != "http" && u.scheme != "https") return null
        "${u.scheme}://${u.rawAuthority}/wp-json/wp/v2/posts?per_page=$PER_PAGE&page=$page&_embed=wp:term" +
            "&_fields=id,link,date,title,excerpt,content,_links,_embedded"
    }.getOrNull()

    private val magnet = Regex("""magnet:\?[^"'<>\s]+""")
    private val href = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val audio = Regex("""\.(mp3|m4a|m4b|ogg|opus|zip)(\?|$)""", RegexOption.IGNORE_CASE)

    fun parse(json: String, siteUrl: String, siteTitle: String): List<CatalogBook> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList() // past the last page WordPress answers an error object
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = Feeds.plainText(p.optJSONObject("title")?.optString("rendered") ?: "").ifBlank { return@mapNotNull null }
            val content = p.optJSONObject("content")?.optString("rendered") ?: ""
            val links = href.findAll(content).map { Feeds.unescapeText(it.groupValues[1]) }.toList()
            val torrent = magnet.find(content)?.value?.let(Feeds::unescapeText)
                ?: links.firstOrNull { it.substringBefore('?').endsWith(".torrent", true) }?.let { Feeds.resolve(siteUrl, it) }
            CatalogBook(
                source = "feed:$siteUrl", id = p.opt("id")?.toString() ?: title, title = title,
                authors = emptyList(), language = null,
                genres = terms(p.optJSONObject("_embedded")),
                seconds = null, sections = null,
                year = p.optString("date").take(4).toIntOrNull(),
                pageUrl = p.optString("link").ifBlank { null }?.let { Feeds.resolve(siteUrl, it) },
                downloadUrl = links.firstOrNull { audio.containsMatchIn(it) }?.let { Feeds.resolve(siteUrl, it) },
                description = Feeds.plainText(p.optJSONObject("excerpt")?.optString("rendered") ?: "").ifBlank { null },
                torrentUrl = torrent, collection = siteTitle,
            )
        }
    }

    private fun terms(embedded: JSONObject?): List<String> {
        val groups = embedded?.optJSONArray("wp:term") ?: return emptyList()
        return (0 until groups.length()).flatMap { g ->
            val terms = groups.optJSONArray(g) ?: return@flatMap emptyList()
            (0 until terms.length()).mapNotNull { t ->
                terms.optJSONObject(t)?.takeIf { it.optString("taxonomy") == "category" }?.optString("name")?.let(Feeds::plainText)?.ifBlank { null }
            }
        }.filter { !it.equals("Uncategorized", true) }.distinct()
    }
}
