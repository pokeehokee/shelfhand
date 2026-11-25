package app.shelfhand.catalog

import android.content.Context
import app.shelfhand.core.catalog.CatalogBook
import app.shelfhand.core.catalog.CatalogDb
import app.shelfhand.core.catalog.Feeds
import app.shelfhand.core.catalog.WordPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Sites and feeds the user added by address: RSS, podcast, Atom and OPDS. A page address works when the
 * page lists its feed. Every page the feed links to is read, and a WordPress feed is paged by number,
 * so the phone holds the site's whole archive rather than only its latest entries.
 */
class FeedStore(ctx: Context) {
    private val db = CatalogHolder.get(ctx)
    private val accept = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, text/html;q=0.8, */*;q=0.5"

    fun sources(): List<CatalogDb.Source> = db.sources().filter { it.kind == "feed" }

    /** Finds the feed at [address], reads all of it, and returns the source and how many books it gave. */
    suspend fun add(address: String, progress: (String) -> Unit): Pair<CatalogDb.Source, Int> = withContext(Dispatchers.IO) {
        val url = normalise(address)
        val first = Http.get(url, accept = accept)
        val (feedUrl, xml) = if (Feeds.looksLikeFeed(first)) url to first else {
            val found = Feeds.discover(first, url)
            if (found.isEmpty()) throw IOException("That page doesn't list a feed. Try the site's RSS, podcast or OPDS address instead.")
            found.firstNotNullOfOrNull { f -> runCatching { Http.get(f, accept = accept) }.getOrNull()?.takeIf(Feeds::looksLikeFeed)?.let { f to it } }
                ?: throw IOException("The page lists a feed, but it didn't load.")
        }
        val n = readAll(feedUrl, xml, url, progress)
        sources().first { it.url == feedUrl } to n
    }

    /** Re-reads every feed in full. One that fails keeps what it had, and its error is returned. */
    suspend fun refreshAll(progress: (String) -> Unit): List<String> = withContext(Dispatchers.IO) {
        sources().mapNotNull { s ->
            runCatching { readAll(s.url, Http.get(s.url, accept = accept), s.addedFrom, progress) }
                .exceptionOrNull()?.let { "${s.title}: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) { db.removeSource(id) }

    private suspend fun readAll(feedUrl: String, firstXml: String, addedFrom: String?, progress: (String) -> Unit): Int {
        val first = Feeds.parsePage(firstXml, feedUrl)
        val wordpress = Feeds.isWordPress(firstXml)
        val items = LinkedHashMap<String, CatalogBook>()
        first.items.forEach { items[it.id] = it }
        var next = first.next ?: if (wordpress) Feeds.pagedUrl(feedUrl, 2) else null
        var pageNo = 1
        val seen = mutableSetOf(feedUrl)
        while (next != null && pageNo < MAX_PAGES && seen.add(next)) {
            coroutineContext.ensureActive()
            val xml = try { Http.get(next, accept = accept) } catch (e: Http.Status) { break } // WordPress answers 404 past the last page
            if (!Feeds.looksLikeFeed(xml)) break
            val page = Feeds.parsePage(xml, next)
            val before = items.size
            page.items.forEach { items.putIfAbsent(it.id, it) }
            pageNo++
            progress("${first.title}: page $pageNo, ${items.size} entries")
            if (items.size == before) break // a site that ignores the page number sends page one again
            next = page.next ?: if (wordpress) Feeds.pagedUrl(feedUrl, pageNo + 1) else null
        }
        var books = first.finish(items.values.toList())
        // A WordPress feed that would not page: try the site's public posts listing, which pages by design.
        if (wordpress && pageNo == 1) wordpressListing(feedUrl, first.title, progress)?.let { if (it.size > books.size) books = it }
        if (books.isEmpty()) throw IOException("The feed loaded but has no items.")
        val src = db.upsertSource(feedUrl, first.title.ifBlank { URL(feedUrl).host }, "feed", addedFrom)
        books.chunked(500).forEach { db.put(src, it) }
        return books.size
    }

    private suspend fun wordpressListing(siteUrl: String, title: String, progress: (String) -> Unit): List<CatalogBook>? {
        val out = LinkedHashMap<String, CatalogBook>()
        var page = 1
        while (page <= MAX_PAGES) {
            coroutineContext.ensureActive()
            val url = WordPress.postsUrl(siteUrl, page) ?: return null
            val json = try { Http.get(url, accept = "application/json") } catch (e: IOException) { break }
            val posts = WordPress.parse(json, siteUrl, title)
            if (posts.isEmpty()) break
            val before = out.size
            posts.forEach { out.putIfAbsent(it.id, it) }
            if (out.size == before) break
            progress("$title: ${out.size} posts")
            if (posts.size < WordPress.PER_PAGE) break
            page++
        }
        return out.values.toList().ifEmpty { null }
    }

    private fun normalise(address: String): String {
        val a = address.trim()
        val withScheme = if ("://" in a) a else "https://$a"
        val u = runCatching { URL(withScheme) }.getOrNull() ?: throw IOException("That isn't a web address.")
        if (u.protocol != "https" && u.protocol != "http") throw IOException("Only web addresses (http or https) can be added.")
        return withScheme
    }

    private companion object { const val MAX_PAGES = 2000 }
}
