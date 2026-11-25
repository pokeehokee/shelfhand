package app.shelfhand.catalog

import android.content.Context
import app.shelfhand.core.catalog.CatalogDb
import app.shelfhand.core.catalog.Feeds
import app.shelfhand.core.catalog.SiteReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Sites and feeds the user added by address: RSS, podcast, Atom and OPDS. A page address works when the
 * page lists its feed. Every page the feed links to is read, a WordPress feed is paged by number, and a
 * WordPress site's own listing fills in what the feed leaves out, so the phone holds the whole archive.
 * What was read is kept even when the site stops answering partway; the outcome says where it stopped.
 */
class FeedStore(ctx: Context) {
    // Opened on first use, which is always off the main thread.
    private val db by lazy { CatalogHolder.get(ctx) }
    private val accept = SiteReader.FEED_ACCEPT + ", text/html;q=0.8"

    fun sources(): List<CatalogDb.Source> = db.sources().filter { it.kind == "feed" }

    /** Finds the feed at [address], reads all of it, and returns the source and how the read went. */
    suspend fun add(address: String, progress: (String) -> Unit): Pair<CatalogDb.Source, SiteReader.Outcome> = withContext(Dispatchers.IO) {
        val url = normalise(address)
        val first = Http.get(url, accept = accept)
        val (feedUrl, xml) = if (Feeds.looksLikeFeed(first)) url to first else {
            val found = Feeds.discover(first, url)
            if (found.isEmpty()) throw IOException("That page doesn't list a feed. Try the site's RSS, podcast or OPDS address instead.")
            found.firstNotNullOfOrNull { f -> runCatching { Http.get(f, accept = accept) }.getOrNull()?.takeIf(Feeds::looksLikeFeed)?.let { f to it } }
                ?: throw IOException("The page lists a feed, but it didn't load.")
        }
        val o = readAll(feedUrl, xml, url, progress)
        sources().first { it.url == feedUrl } to o
    }

    /** Re-reads every feed in full. One that fails keeps what it had; the returned lines say what went wrong. */
    suspend fun refreshAll(progress: (String) -> Unit): List<String> = withContext(Dispatchers.IO) {
        sources().mapNotNull { s ->
            runCatching { readAll(s.url, Http.get(s.url, accept = accept), s.addedFrom, progress) }.fold(
                onSuccess = { o -> o.stopped?.let { "${s.title} stopped $it" } },
                onFailure = { e -> if (e is CancellationException) throw e; "${s.title}: ${e.message ?: e.javaClass.simpleName}" },
            )
        }
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) { db.removeSource(id) }

    private suspend fun readAll(feedUrl: String, firstXml: String, addedFrom: String?, progress: (String) -> Unit): SiteReader.Outcome {
        val job = coroutineContext[Job]
        val net = SiteReader.Net(fetch = { u, a -> Http.get(u, accept = a) }, pause = { Thread.sleep(PAUSE_MS) }, active = { job?.isActive != false })
        // The page the user typed, when it wasn't the feed, is the site whose map is read; what the phone
        // already holds from this source is skipped, so each update reads only new pages from the map.
        val site = addedFrom?.takeIf { it != feedUrl }
        val o = SiteReader.read(feedUrl, firstXml, net, site = site, known = db.pageKeys(feedUrl), progress = progress)
        coroutineContext.ensureActive()
        if (o.books.isEmpty()) throw IOException("The feed loaded but has no items.")
        progress("${o.title}: saving ${"%,d".format(o.books.size)} entries")
        val src = db.upsertSource(feedUrl, o.title.ifBlank { URL(feedUrl).host }, "feed", addedFrom)
        o.books.chunked(500).forEach { db.put(src, it) }
        return o
    }

    private fun normalise(address: String): String {
        val a = address.trim()
        val withScheme = if ("://" in a) a else "https://$a"
        val u = runCatching { URL(withScheme) }.getOrNull() ?: throw IOException("That isn't a web address.")
        if (u.protocol != "https" && u.protocol != "http") throw IOException("Only web addresses (http or https) can be added.")
        return withScheme
    }

    // A third of a second between pages: fast enough for a big archive, slow enough not to trip a site's rate limit.
    private companion object { const val PAUSE_MS = 350L }
}
