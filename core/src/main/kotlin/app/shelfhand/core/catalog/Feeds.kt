package app.shelfhand.core.catalog

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Any site that publishes a standard feed can be a source: RSS (podcasts, audiobook blogs,
 * torrent feeds), Atom, and OPDS catalogs, which are Atom. The app reads what the feed says
 * about each item and nothing else; it does not scrape pages.
 */
object Feeds {
    data class Feed(val title: String, val books: List<CatalogBook>)

    /**
     * One page of a feed. [next] is the feed's own next-page link (RFC 5005 / OPDS `rel="next"`), when it
     * has one. [finish] turns the items of every page together into books, because a serialized book
     * posted as a podcast can only be recognised once all its episodes are in.
     */
    class Page(val title: String, val items: List<CatalogBook>, val next: String?, val finish: (List<CatalogBook>) -> List<CatalogBook>)

    /** True when the text looks like a feed rather than a web page. */
    fun looksLikeFeed(text: String): Boolean {
        val head = text.trimStart().take(2000).lowercase()
        return head.startsWith("<?xml") && ("<rss" in head || "<feed" in head || "<rdf:rdf" in head) ||
            head.startsWith("<rss") || head.startsWith("<feed")
    }

    /**
     * Feed addresses a web page advertises in its head: `<link rel="alternate" type="application/rss+xml" href=...>`,
     * the same thing a browser or podcast app looks for. Relative addresses resolve against the page.
     */
    fun discover(html: String, pageUrl: String): List<String> {
        val types = listOf("application/rss+xml", "application/atom+xml", "application/rdf+xml", "application/opds", "application/x-opds", "application/feed+json")
        return Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { m ->
            val tag = m.value
            val rel = attr(tag, "rel")?.lowercase() ?: return@mapNotNull null
            val type = attr(tag, "type")?.lowercase() ?: ""
            val href = attr(tag, "href") ?: return@mapNotNull null
            if (("alternate" in rel || "opds" in rel) && types.any { type.startsWith(it) } && !type.contains("json"))
                resolve(pageUrl, href) else null
        }.distinct()
            // A WordPress page also lists its comments feed; that is never the catalog, so try it last.
            .sortedBy { if (Regex("""/comments/feed|[?&]feed=comments""", RegexOption.IGNORE_CASE).containsMatchIn(it)) 1 else 0 }
            .toList()
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)
            ?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).let(::unescape) }

    fun resolve(base: String, href: String): String? = runCatching {
        val h = href.trim()
        // java.net.URI follows RFC 2396 here and drops the path for a bare "?query"; RFC 3986 keeps it.
        val u = if (h.startsWith("?")) URI(base.substringBefore('#').substringBefore('?') + h) else URI(base).resolve(h)
        u.toString().takeIf { u.scheme == "http" || u.scheme == "https" }
    }.getOrNull()

    /** True for a feed WordPress generated, which pages with `?paged=N` even when it says nothing about it. */
    fun isWordPress(xml: String): Boolean =
        Regex("""<generator[^>]*>[^<]*wordpress\.org""", RegexOption.IGNORE_CASE).containsMatchIn(xml.take(8000)) ||
            Regex("""<generator[^>]*uri="https?://wordpress\.org""", RegexOption.IGNORE_CASE).containsMatchIn(xml.take(8000))

    /** The feed address with its `paged` query set to [n]. */
    fun pagedUrl(feedUrl: String, n: Int): String {
        val base = feedUrl.substringBefore('#')
        val query = base.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !it.startsWith("paged=") }
        return base.substringBefore('?') + "?" + (query + "paged=$n").joinToString("&")
    }

    /** Reads a one-page feed. A feed whose items are the chapters of one book becomes one entry for the whole book. */
    fun parse(xml: String, feedUrl: String): Feed = parsePage(xml, feedUrl).let { Feed(it.title, it.finish(it.items)) }

    /** Reads one page of a feed, leaving the items as they are; see [Page]. */
    fun parsePage(xml: String, feedUrl: String): Page {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // No outside files or DTDs: a feed is someone else's text.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        val source = "feed:" + feedUrl
        return when (root.localName ?: root.nodeName) {
            "feed" -> atom(root, source, feedUrl)
            else -> rss(root, source, feedUrl)
        }
    }

    private fun nextLink(parent: Element, feedUrl: String): String? =
        parent.children("link").firstOrNull { it.getAttribute("rel").equals("next", ignoreCase = true) }
            ?.getAttribute("href")?.ifBlank { null }?.let { resolve(feedUrl, it) }

    private fun rss(root: Element, source: String, feedUrl: String): Page {
        val channel = root.child("channel") ?: root
        val title = channel.text("title") ?: host(feedUrl)
        val lang = channel.text("language")?.let(::languageName)
        val chAuthor = channel.text("author") ?: channel.text("creator")
        val chCats = channel.children("category").mapNotNull { it.getAttribute("text").ifBlank { it.textContent.trim() }.ifBlank { null } }
        val items = channel.children("item").ifEmpty { root.children("item") }.mapNotNull { it ->
            val t = it.text("title") ?: return@mapNotNull null
            val link = it.text("link")
            val enc = it.children("enclosure").firstOrNull()
            val encUrl = enc?.getAttribute("url")?.ifBlank { null }
            val encType = enc?.getAttribute("type")?.lowercase() ?: ""
            val magnet = it.text("magnetURI") ?: listOfNotNull(link, encUrl).firstOrNull { u -> u.startsWith("magnet:") }
            val torrentFile = encUrl?.takeIf { encType.contains("bittorrent") || it.lowercase().substringBefore('?').endsWith(".torrent") }
                ?: link?.takeIf { it.lowercase().substringBefore('?').endsWith(".torrent") }
            val download = encUrl?.takeIf { it != torrentFile && !it.startsWith("magnet:") }
            CatalogBook(
                source = source,
                id = it.text("guid") ?: link ?: encUrl ?: t,
                title = t,
                authors = listOfNotNull(it.text("author")?.let(::cleanAuthor) ?: it.text("creator") ?: chAuthor),
                language = lang,
                genres = (it.children("category").mapNotNull { c -> c.getAttribute("text").ifBlank { c.textContent.trim() }.ifBlank { null } } + chCats).distinct(),
                seconds = it.text("duration")?.let(::duration),
                sections = null,
                year = it.text("pubDate")?.let(::year) ?: it.text("date")?.take(4)?.toIntOrNull(),
                pageUrl = link?.takeIf { l -> l.startsWith("http") && l != torrentFile },
                downloadUrl = download,
                description = (it.text("summary") ?: it.text("description"))?.let(::plain),
                torrentUrl = magnet ?: torrentFile,
                collection = title,
            )
        }
        val type = channel.text("type")
        val desc = channel.text("description")?.let(::plain)
        val site = channel.children("link").firstOrNull { it.namespaceURI.isNullOrEmpty() }?.textContent?.trim()?.ifBlank { null }
        return Page(title, items, nextLink(channel, feedUrl)) { all -> maybeOneBook(title, all, type, chAuthor, lang, chCats, desc, site, feedUrl, source) }
    }

    private fun atom(root: Element, source: String, feedUrl: String): Page {
        val title = root.text("title") ?: host(feedUrl)
        val feedAuthor = root.child("author")?.text("name")
        val items = root.children("entry").mapNotNull { e ->
            val t = e.text("title") ?: return@mapNotNull null
            val links = e.children("link")
            fun link(pred: (rel: String, type: String) -> Boolean) = links.firstOrNull { l ->
                pred(l.getAttribute("rel").lowercase().ifBlank { "alternate" }, l.getAttribute("type").lowercase())
            }?.getAttribute("href")?.let { resolve(feedUrl, it) ?: it.takeIf { h -> h.startsWith("magnet:") } }
            val torrent = link { _, type -> type.contains("bittorrent") }
                ?: links.map { it.getAttribute("href") }.firstOrNull { it.startsWith("magnet:") }
            val download = link { rel, type -> (rel == "enclosure" || rel.contains("acquisition")) && !type.contains("bittorrent") }
            CatalogBook(
                source = source,
                id = e.text("id") ?: download ?: t,
                title = t,
                authors = e.children("author").mapNotNull { it.text("name") }.ifEmpty { listOfNotNull(feedAuthor) },
                language = (e.text("language") ?: root.getAttribute("xml:lang").ifBlank { null })?.let(::languageName),
                genres = e.children("category").mapNotNull { (it.getAttribute("label").ifBlank { it.getAttribute("term") }).ifBlank { null } },
                seconds = e.text("duration")?.let(::duration) ?: e.text("extent")?.let(::duration),
                sections = null,
                year = (e.text("issued") ?: e.text("published") ?: e.text("updated"))?.take(4)?.toIntOrNull(),
                pageUrl = link { rel, type -> rel == "alternate" && (type.isEmpty() || type.contains("html")) },
                downloadUrl = download,
                description = (e.text("summary") ?: e.text("content"))?.let(::plain),
                torrentUrl = torrent,
                collection = title,
            )
        }
        return Page(title, items, nextLink(root, feedUrl)) { it }
    }

    /**
     * Indie authors often publish one audiobook as a podcast: each episode is a chapter. Listing every
     * chapter as its own "book" would bury the book, so such a feed becomes one entry whose length is the
     * sum of its parts. Signs: the feed says it is serial, or most episode titles name a chapter or part.
     */
    private fun maybeOneBook(
        title: String, items: List<CatalogBook>, type: String?, author: String?, lang: String?, cats: List<String>,
        description: String?, link: String?, feedUrl: String, source: String,
    ): List<CatalogBook> {
        if (items.size < 3) return items
        val chapterish = items.count { CHAPTER.containsMatchIn(it.title) }
        val serial = type.equals("serial", ignoreCase = true)
        if (!serial && chapterish * 2 < items.size) return items
        // Titles alone are not enough: a site's catalog of "Book Name, Part 2" posts is many books, not one.
        // Without the feed saying it is serial, every chapter must also be its own audio file.
        if (!serial && items.count { it.downloadUrl != null } * 10 < items.size * 9) return items
        if (items.any { it.torrentUrl != null }) return items // a torrent feed lists whole books, whatever the titles say
        val secs = items.map { it.seconds }
        return listOf(CatalogBook(
            source = source,
            id = "feed",
            title = title,
            authors = listOfNotNull(author).ifEmpty { items.flatMap { it.authors }.distinct().take(3) },
            language = lang,
            genres = (cats + items.flatMap { it.genres }).distinct(),
            // The whole length only when every part says how long it is.
            seconds = if (secs.all { it != null }) secs.sumOf { it!! } else null,
            sections = items.size,
            year = items.mapNotNull { it.year }.minOrNull(),
            pageUrl = link?.takeIf { it.startsWith("http") },
            downloadUrl = null,
            description = description,
            collection = title,
            feedUrl = feedUrl,
        ))
    }

    private val CHAPTER = Regex("""\b(chapter|ch\.|part|episode|ep\.|section)\s*\d+|^\d+\s*[-:.]""", RegexOption.IGNORE_CASE)

    /** itunes:duration is seconds, MM:SS or HH:MM:SS; anything else is unknown, not zero. */
    fun duration(s: String): Long? {
        val t = s.trim()
        t.toLongOrNull()?.let { return it.takeIf { v -> v > 0 } }
        val parts = t.split(':').map { it.trim().toLongOrNull() ?: return null }
        if (parts.size !in 2..3) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p }.takeIf { it > 0 }
    }

    private fun year(pubDate: String) = Regex("""\b(19|20)\d{2}\b""").find(pubDate)?.value?.toIntOrNull()

    private fun cleanAuthor(s: String) = s.replace(Regex("""^\S+@\S+\s*\((.*)\)$"""), "$1").trim().ifBlank { null }

    private fun languageName(code: String): String {
        val c = code.trim().lowercase().substringBefore('-').substringBefore('_')
        return LANG[c] ?: code.trim()
    }
    private val LANG = mapOf("en" to "English", "de" to "German", "fr" to "French", "es" to "Spanish", "it" to "Italian",
        "nl" to "Dutch", "pt" to "Portuguese", "ru" to "Russian", "pl" to "Polish", "ja" to "Japanese", "zh" to "Chinese")

    private fun host(url: String) = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

    private fun plain(s: String) = unescape(s.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim().ifBlank { null }

    private fun unescape(s: String) = s
        .replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { m ->
            val v = m.groupValues[1]
            val cp = if (v.startsWith("x")) v.substring(1).toIntOrNull(16) else v.toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&hellip;", "…").replace("&amp;", "&")

    /** HTML reduced to plain text, entities decoded; empty when nothing is left. */
    fun plainText(s: String): String = plain(s) ?: ""
    fun unescapeText(s: String): String = unescape(s)

    // Namespace-blind lookups: feeds mix itunes:, media:, dc:, torrent: and plain names freely.
    private fun Element.children(name: String): List<Element> {
        val out = mutableListOf<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && ((n.localName ?: n.nodeName.substringAfter(':')) == name)) out += n as Element
        }
        return out
    }
    private fun Element.child(name: String) = children(name).firstOrNull()
    private fun Element.text(name: String) = children(name).firstNotNullOfOrNull { it.textContent?.trim()?.ifBlank { null } }
}
