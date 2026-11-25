package app.shelfhand.catalog

import android.content.Context
import app.shelfhand.core.catalog.CatalogBook
import app.shelfhand.core.catalog.CatalogCache
import app.shelfhand.core.catalog.Feeds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A site or feed the user added by its address. [feedUrl] is the feed that was found there. */
data class FeedSource(val feedUrl: String, val title: String, val addedFrom: String)

/**
 * Feeds the user added by address: RSS, podcast, Atom and OPDS. A page address works too,
 * as long as the page advertises its feed the standard way.
 */
class FeedStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "catalog/feeds")
    private val list = File(dir, "sources.txt")

    suspend fun sources(): List<FeedSource> = withContext(Dispatchers.IO) {
        if (!list.exists()) emptyList() else list.readLines().mapNotNull { line ->
            val p = line.split('\u001f')
            if (p.size == 3 && p[0].startsWith("http")) FeedSource(p[0], p[1], p[2]) else null
        }
    }

    suspend fun load(): List<CatalogBook> = withContext(Dispatchers.IO) {
        sources().flatMap { s -> cacheOf(s.feedUrl).takeIf { it.exists() }?.let { CatalogCache.read(it.readText()) } ?: emptyList() }
    }

    /** Fetches the address, finds its feed if it is a page, and saves the books. */
    suspend fun add(address: String): FeedSource = withContext(Dispatchers.IO) {
        val url = normalise(address)
        val first = get(url)
        val (feedUrl, xml) = if (Feeds.looksLikeFeed(first)) url to first else {
            val found = Feeds.discover(first, url)
            if (found.isEmpty()) throw IOException("That page doesn't list a feed. Try the site's RSS, podcast or OPDS address instead.")
            found.firstNotNullOfOrNull { f -> runCatching { get(f) }.getOrNull()?.takeIf(Feeds::looksLikeFeed)?.let { f to it } }
                ?: throw IOException("The page lists a feed, but it didn't load.")
        }
        val feed = Feeds.parse(xml, feedUrl)
        if (feed.books.isEmpty()) throw IOException("The feed loaded but has no items.")
        save(feedUrl, feed.books)
        val src = FeedSource(feedUrl, feed.title.ifBlank { URL(feedUrl).host }, url)
        writeSources(sources().filter { it.feedUrl != feedUrl } + src)
        src
    }

    /** Re-reads every feed. One that fails keeps its last copy, and its error is returned. */
    suspend fun refreshAll(): List<String> = withContext(Dispatchers.IO) {
        sources().mapNotNull { s ->
            runCatching { save(s.feedUrl, Feeds.parse(get(s.feedUrl), s.feedUrl).books) }
                .exceptionOrNull()?.let { "${s.title}: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    suspend fun remove(feedUrl: String) = withContext(Dispatchers.IO) {
        cacheOf(feedUrl).delete()
        writeSources(sources().filter { it.feedUrl != feedUrl })
    }

    private fun normalise(address: String): String {
        val a = address.trim()
        val withScheme = if ("://" in a) a else "https://$a"
        val u = runCatching { URL(withScheme) }.getOrNull() ?: throw IOException("That isn't a web address.")
        if (u.protocol != "https" && u.protocol != "http") throw IOException("Only web addresses (http or https) can be added.")
        return withScheme
    }

    private fun save(feedUrl: String, books: List<CatalogBook>) {
        dir.mkdirs()
        val tmp = File(dir, "${key(feedUrl)}.tmp")
        tmp.writeText(CatalogCache.write(books))
        tmp.renameTo(cacheOf(feedUrl))
    }

    private fun writeSources(s: List<FeedSource>) {
        dir.mkdirs()
        list.writeText(s.joinToString("\n") { listOf(it.feedUrl, it.title, it.addedFrom).joinToString("\u001f") { v -> v.replace('\u001f', ' ').replace('\n', ' ') } })
    }

    private fun cacheOf(feedUrl: String) = File(dir, "${key(feedUrl)}.txt")
    private fun key(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    private fun get(url: String): String {
        var current = url
        repeat(5) { // follow redirects by hand so an https page can't hand us to a non-web scheme
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Shelfhand audiobook player")
            c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8, */*;q=0.5")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: throw IOException("Redirect with no address")
                    current = Feeds.resolve(current, next) ?: throw IOException("Redirected somewhere that isn't a web address")
                    return@repeat
                }
                if (code !in 200..299) throw IOException("The site answered $code")
                val bytes = c.inputStream.use { s ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = s.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) throw IOException("The feed is larger than 20 MB")
                    }
                    out.toByteArray()
                }
                return String(bytes, Charsets.UTF_8)
            } finally { c.disconnect() }
        }
        throw IOException("Too many redirects")
    }

    private companion object { const val MAX_BYTES = 20 * 1024 * 1024 }
}
