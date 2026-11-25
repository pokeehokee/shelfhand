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

    /**
     * A listable content type: "posts", or a custom type such as "audiobook". [api] is the REST root, either
     * `https://site/wp-json/` or, on a site without pretty links, `https://site/?rest_route=/`.
     */
    data class Type(val slug: String, val name: String, val restBase: String, val api: String)

    /** The REST roots to try for a site, from any address on it: the usual one, then the plain-links form. */
    fun apiRoots(siteUrl: String): List<String> = runCatching {
        val u = URI(siteUrl)
        if (u.scheme != "http" && u.scheme != "https") return emptyList()
        val origin = "${u.scheme}://${u.rawAuthority}"
        listOf("$origin/wp-json/", "$origin/?rest_route=/")
    }.getOrDefault(emptyList())

    private fun url(api: String, path: String, query: String) = api + path + (if ('?' in api) "&" else "?") + query

    fun typesUrl(api: String) = url(api, "wp/v2/types", "context=view")

    /** The listing address for one page of one type. */
    fun listUrl(t: Type, page: Int) = url(t.api, "wp/v2/${t.restBase}",
        "per_page=$PER_PAGE&page=$page&_embed=wp:term&_fields=id,guid,link,date,title,excerpt,content,_links,_embedded")

    /** The old single-type address, kept for the posts listing. */
    fun postsUrl(siteUrl: String, page: Int): String? = apiRoots(siteUrl).firstOrNull()?.let {
        listUrl(Type("post", "Posts", "posts", it), page)
    }

    // Types WordPress or common plugins use for layout, menus, shop orders and the like: never books.
    private val notBooks = setOf("page", "attachment", "nav_menu_item", "revision", "custom_css", "customize_changeset",
        "oembed_cache", "user_request", "elementor_library", "e-landing-page", "product_variation", "shop_order",
        "shop_coupon", "shop_order_refund", "ct_template", "fl-builder-template", "et_pb_layout", "acf-field-group", "acf-field")

    /** The types worth listing from a site's `/wp/v2/types` answer: posts, and any flat custom type. Null if not WordPress. */
    fun types(json: String, api: String): List<Type>? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!o.has("post")) return null
        return o.keys().asSequence().mapNotNull { k ->
            val t = o.optJSONObject(k) ?: return@mapNotNull null
            val slug = t.optString("slug").ifBlank { k }
            val base = t.optString("rest_base").ifBlank { return@mapNotNull null }
            if (slug != "post" && (slug in notBooks || slug.startsWith("wp_") || t.optBoolean("hierarchical"))) return@mapNotNull null
            Type(slug, Feeds.plainText(t.optString("name")).ifBlank { slug }, base, api)
        }.sortedBy { if (it.slug == "post") 0 else 1 }.toList()
    }

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
                // The guid is what the site's feed uses as each item's id, so the two merge instead of doubling up.
                source = "feed:$siteUrl",
                id = p.optJSONObject("guid")?.optString("rendered")?.ifBlank { null }?.let(Feeds::unescapeText)
                    ?: p.opt("id")?.toString() ?: title,
                title = title,
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
                terms.optJSONObject(t)?.takeIf { it.optString("taxonomy") !in setOf("post_tag", "post_format") }?.optString("name")?.let(Feeds::plainText)?.ifBlank { null }
            }
        }.filter { !it.equals("Uncategorized", true) }.distinct()
    }
}
