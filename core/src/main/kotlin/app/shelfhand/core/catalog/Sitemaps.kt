package app.shelfhand.core.catalog

import java.net.URI

/**
 * A site's own map of its pages (sitemap.xml), used to find the entries its feed and listing left out.
 *
 * The map only lists addresses, so each entry still takes one page load for its title and links; the
 * reader fetches just the ones the phone doesn't already have, a limited number per read.
 */
object Sitemaps {
    /** One address from a map. [posts] is true when it came from a map of posts rather than of everything. */
    data class Entry(val url: String, val posts: Boolean)

    /** A sitemap is either an index of further maps or a list of pages. */
    data class Parsed(val maps: List<String>, val pages: List<String>)

    const val ACCEPT = "application/xml, text/xml;q=0.9, */*;q=0.5"

    fun origin(url: String): String? = runCatching {
        val u = URI(url)
        if (u.scheme !in setOf("http", "https") || u.host.isNullOrBlank()) null
        else "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
    }.getOrNull()

    fun host(url: String): String? = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    /** The usual addresses, WordPress's own first, then the common plugin and generic ones. */
    fun candidates(siteUrl: String): List<String> = origin(siteUrl)?.let { o ->
        listOf("$o/wp-sitemap.xml", "$o/sitemap_index.xml", "$o/sitemap.xml")
    }.orEmpty()

    /** The maps a site's robots.txt names, on its own host only. */
    fun fromRobots(text: String, siteUrl: String): List<String> {
        val h = host(siteUrl) ?: return emptyList()
        return text.lineSequence().mapNotNull { l ->
            l.trim().takeIf { it.startsWith("sitemap:", ignoreCase = true) }?.substringAfter(':')?.trim()
        }.filter { host(it) == h }.distinct().toList()
    }

    private val loc = Regex("""<loc>\s*(?:<!\[CDATA\[)?\s*([^<\]]+?)\s*(?:]]>)?\s*</loc>""", RegexOption.IGNORE_CASE)

    fun parse(xml: String): Parsed {
        val head = xml.take(2000)
        val locs = loc.findAll(xml).map { Feeds.unescapeText(it.groupValues[1].trim()) }.filter { it.startsWith("http") }.toList()
        return if (head.contains("<sitemapindex", ignoreCase = true)) Parsed(locs, emptyList()) else Parsed(emptyList(), locs)
    }

    // Maps of things that are never books: categories, tags, authors, plain pages, media.
    private val notBookMaps = Regex(
        """taxonom|[-_/](users?|authors?|categor(y|ies)|tags?|post_tag|post_format|pages?|attachment|media|images?|videos?|product_cat|product_tag|news)[-_.]""",
        RegexOption.IGNORE_CASE,
    )

    fun wanted(mapUrl: String): Boolean = !notBookMaps.containsMatchIn(mapUrl.substringAfterLast('/').let { "/$it" }) &&
        !mapUrl.substringBefore('?').endsWith(".gz", ignoreCase = true)

    /** A map named for posts or a custom post type, as WordPress and its SEO plugins name them. */
    fun isPostMap(mapUrl: String): Boolean {
        val name = mapUrl.substringAfterLast('/').lowercase()
        return name.startsWith("wp-sitemap-posts-") || Regex("""^[a-z0-9_-]+-sitemap\d*\.xml""").matches(name.substringBefore('?')) &&
            !name.startsWith("page-sitemap")
    }

    private val notPage = Regex("""\.(jpe?g|png|gif|webp|svg|pdf|mp3|m4a|m4b|ogg|opus|zip|xml|txt|css|js)$""", RegexOption.IGNORE_CASE)

    /** The same address however a site writes it: no scheme, no www, no trailing slash, no fragment. */
    fun key(url: String): String = runCatching {
        val u = URI(url.trim())
        val path = (u.rawPath ?: "").trimEnd('/')
        (u.host?.lowercase()?.removePrefix("www.") ?: "") + path + (u.rawQuery?.let { "?$it" } ?: "")
    }.getOrDefault(url.trim().trimEnd('/'))

    /** True for an address worth a page load: on the site's own host, not its home page, not a file. */
    fun isPage(url: String, siteHost: String): Boolean = runCatching {
        val u = URI(url)
        host(url) == siteHost && !(u.path ?: "").trim('/').isEmpty() && !notPage.containsMatchIn((u.path ?: "").trimEnd('/'))
    }.getOrDefault(false)

    // ---- reading one page ----

    private val metaTag = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val linkTag = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val titleTag = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val src = Regex("""\b(?:href|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val magnet = Regex("""magnet:\?[^"'<>\s]+""")
    private val audio = Regex("""\.(mp3|m4a|m4b|ogg|opus|zip)(\?|$)""", RegexOption.IGNORE_CASE)

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let(Feeds::unescapeText)?.trim()

    private fun metas(html: String): List<Pair<String, String>> = metaTag.findAll(html.take(200_000)).mapNotNull { m ->
        val t = m.value
        val k = (attr(t, "property") ?: attr(t, "name") ?: return@mapNotNull null).lowercase()
        val v = attr(t, "content")?.ifBlank { null } ?: return@mapNotNull null
        k to v
    }.toList()

    /**
     * The book a page describes, from the tags most sites put in their head (Open Graph, the article
     * date, WordPress's short link) and the audio, torrent and magnet links in the page.
     *
     * A page from a map of posts is kept as an entry; a page from a general map is kept only when it
     * looks like one (an article, or something to download), so "About" and "Contact" stay out.
     */
    fun book(html: String, url: String, source: String, collection: String, fromPostMap: Boolean): CatalogBook? {
        val m = metas(html)
        fun meta(k: String) = m.firstOrNull { it.first == k }?.second
        val siteName = meta("og:site_name")
        val title = (meta("og:title") ?: titleTag.find(html)?.groupValues?.get(1)?.let(Feeds::plainText))
            ?.let { t -> siteName?.let { s -> t.removeSuffix(s).trimEnd().trimEnd('|', '–', '-', '—', ':').trim() } ?: t }
            ?.ifBlank { null } ?: return null
        val links = src.findAll(html).map { Feeds.unescapeText(it.groupValues[1]) }.toList()
        val download = links.firstOrNull { audio.containsMatchIn(it) }?.let { Feeds.resolve(url, it) }
        val torrent = magnet.find(html)?.value?.let(Feeds::unescapeText)
            ?: links.firstOrNull { it.substringBefore('?').endsWith(".torrent", true) }?.let { Feeds.resolve(url, it) }
        val type = meta("og:type")?.lowercase()
        val looksLikeEntry = type == "article" || type == "book" || type?.startsWith("music") == true || download != null || torrent != null
        if (!fromPostMap && !looksLikeEntry) return null
        // WordPress's short link is the post's guid, which is the id its feed gives the same post.
        val shortlink = linkTag.findAll(html.take(200_000)).map { it.value }
            .firstOrNull { attr(it, "rel")?.lowercase()?.split(' ')?.contains("shortlink") == true }?.let { attr(it, "href") }
        return CatalogBook(
            source = source,
            id = shortlink?.takeIf { it.startsWith("http") } ?: url,
            title = title,
            authors = listOfNotNull(meta("author")?.takeIf { !it.startsWith("http") }),
            language = null,
            genres = m.filter { it.first == "article:section" }.map { it.second }.distinct(),
            seconds = null, sections = null,
            year = (meta("article:published_time") ?: meta("og:updated_time"))?.take(4)?.toIntOrNull(),
            pageUrl = url,
            downloadUrl = download,
            description = (meta("og:description") ?: meta("description"))?.let(Feeds::plainText)?.ifBlank { null },
            torrentUrl = torrent,
            collection = collection,
        )
    }
}
