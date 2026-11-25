package app.shelfhand.core.catalog

import java.io.IOException

/** A site answered with an error status. [retryAfterSeconds] is its Retry-After header, when it sent one. */
class HttpError(val code: Int, val retryAfterSeconds: Long? = null) : IOException("the site answered $code${reason(code)}") {
    private companion object {
        fun reason(code: Int) = when (code) {
            403 -> " (refused)"
            404, 410 -> " (not found)"
            429 -> " (too many requests)"
            in 500..599 -> " (server error)"
            else -> ""
        }
    }
}

/**
 * Reads a whole site: every page of its feed, then, for a WordPress site, its public listing of posts and of
 * any custom post types (some sites keep their books as an "audiobook" type the feed never shows).
 *
 * A hiccup partway no longer throws the whole import away: what was read is kept, and [Outcome.stopped] says
 * where and why it stopped, so the user hears "stopped at page 37" rather than a short list with no reason.
 */
object SiteReader {
    /** [fromMap] counts the entries only the site's map listed, each read from its own page. */
    data class Outcome(val title: String, val books: List<CatalogBook>, val pages: Int, val stopped: String?, val fromMap: Int = 0)

    /** [fetch] takes an address and an Accept header. [pause] runs between requests, to stay polite. */
    class Net(val fetch: (String, String) -> String, val pause: () -> Unit, val active: () -> Boolean = { true })

    const val FEED_ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5"
    const val MAX_PAGES = 5000
    /** Pages read one by one from a site's map in a single pass; the next update carries on from there. */
    const val MAP_PAGES_PER_READ = 1000

    /**
     * [site] is the page the user typed, when it was a page rather than the feed itself. [known] holds the
     * page addresses (see [Sitemaps.key]) the phone already has from this source, so a later update reads
     * only what is new in the site's map.
     */
    fun read(
        feedUrl: String, firstXml: String, net: Net,
        site: String? = null, known: Set<String> = emptySet(), progress: (String) -> Unit,
    ): Outcome {
        val first = Feeds.parsePage(firstXml, feedUrl)
        val title = first.title
        var wordpress = Feeds.isWordPress(firstXml)
        val items = LinkedHashMap<String, CatalogBook>()
        first.items.forEach { items[it.id] = it }
        var pageNo = 1
        var stopped: String? = null
        // No next link: a WordPress feed pages by number even when its generator line was removed, so ask once.
        var next = first.next ?: Feeds.pagedUrl(feedUrl, 2)
        var probing = first.next == null && !wordpress
        val seen = mutableSetOf(feedUrl)
        while (seen.add(next)) {
            if (!net.active()) { stopped = "cancelled"; break }
            if (pageNo >= MAX_PAGES) { stopped = "after $MAX_PAGES pages, the most it reads"; break }
            net.pause()
            val xml = try { net.fetch(next, FEED_ACCEPT) } catch (e: HttpError) {
                // Past the last page a WordPress feed answers 404; that is the normal end, not a failure.
                if (e.code !in setOf(400, 404, 410) && !probing) stopped = "at page ${pageNo + 1}: ${e.message}"
                break
            } catch (e: IOException) {
                if (!probing) stopped = "at page ${pageNo + 1}: ${e.message ?: "no answer"}"
                break
            }
            if (!Feeds.looksLikeFeed(xml)) break
            val page = try { Feeds.parsePage(xml, next) } catch (e: Exception) {
                if (!probing) stopped = "at page ${pageNo + 1}: the page didn't read as a feed"
                break
            }
            val before = items.size
            page.items.forEach { items.putIfAbsent(it.id, it) }
            if (items.size == before) break // a site that ignores the page number sends page one again
            if (probing) { probing = false; wordpress = true }
            pageNo++
            progress("$title: page $pageNo, ${"%,d".format(items.size)} entries")
            next = page.next ?: if (wordpress) Feeds.pagedUrl(feedUrl, pageNo + 1) else break
        }
        var books = first.finish(items.values.toList())

        // A WordPress site's own listing: posts when the feed did not reach its end, and custom types always.
        val types = wordpressTypes(feedUrl, net)
        if (types != null) {
            val have = books.mapTo(HashSet()) { it.id }
            val extra = mutableListOf<CatalogBook>()
            val wanted = types.filter { it.restBase != "posts" || stopped != null || pageNo == 1 }
            for (t in wanted) {
                val r = listing(feedUrl, title, t, net) { n -> progress("$title: reading ${t.name}, ${"%,d".format(n)} so far") }
                r.books.filterTo(extra) { have.add(it.id) }
                // The listing reached the end the feed could not, so the feed's stop no longer stands.
                if (t.restBase == "posts" && r.reachable && r.stop == null) stopped = null
                if (r.stop != null && (t.restBase == "posts" || stopped == null)) stopped = "reading ${t.name} ${r.stop}"
            }
            books = books + extra
        }

        // The site's map, for what both of the above missed. Only for the feed's own site: a podcast host's
        // map lists every show it hosts, not this one's episodes.
        var fromMap = 0
        val mapSite = when {
            types != null || wordpress -> feedUrl
            site != null && Sitemaps.host(site) == Sitemaps.host(feedUrl) -> site
            else -> null
        }
        if (mapSite != null && net.active()) {
            val have = HashSet(known).apply { books.forEach { b -> b.pageUrl?.let { add(Sitemaps.key(it)) } } }
            val ids = books.mapTo(HashSet()) { it.id }
            val r = fromSitemap(mapSite, feedUrl, title, have, net, progress)
            val added = r.books.filter { ids.add(it.id) }
            fromMap = added.size
            books = books + added
            if (r.stop != null && stopped == null) stopped = r.stop
        }
        return Outcome(title, books, pageNo, stopped, fromMap)
    }

    /** Reads the site's map and then each listed page the phone doesn't have yet, up to [MAP_PAGES_PER_READ]. */
    private fun fromSitemap(siteUrl: String, feedUrl: String, title: String, have: Set<String>, net: Net, progress: (String) -> Unit): Listing {
        val siteHost = Sitemaps.host(siteUrl) ?: return Listing(emptyList(), null, reachable = false)
        val entries = sitemapEntries(siteUrl, net)
        if (entries.isEmpty()) return Listing(emptyList(), null, reachable = false)
        val missing = entries.filter { Sitemaps.isPage(it.url, siteHost) && Sitemaps.key(it.url) !in have }
            .distinctBy { Sitemaps.key(it.url) }
        val out = mutableListOf<CatalogBook>()
        var failures = 0
        for ((i, e) in missing.withIndex()) {
            if (!net.active()) return Listing(out, "cancelled")
            if (i >= MAP_PAGES_PER_READ) return Listing(out, "with ${"%,d".format(missing.size - i)} more site-map pages still to read")
            net.pause()
            val html = try { net.fetch(e.url, "text/html,application/xhtml+xml") } catch (x: HttpError) {
                if (x.code in setOf(404, 410, 401, 403)) continue // gone or private: not an entry
                if (++failures >= 3) return Listing(out, "at site-map page ${i + 1}: ${x.message}")
                continue
            } catch (x: java.io.IOException) {
                if (++failures >= 3) return Listing(out, "at site-map page ${i + 1}: ${x.message ?: "no answer"}")
                continue
            }
            failures = 0
            Sitemaps.book(html, e.url, "feed:$feedUrl", title, e.posts)?.let { out += it }
            if ((i + 1) % 10 == 0 || i + 1 == missing.size)
                progress("$title: reading pages from the site map, ${"%,d".format(i + 1)} of ${"%,d".format(missing.size)}")
        }
        return Listing(out, null)
    }

    /** Every page address in the site's map, following an index into its maps of posts and custom types. */
    private fun sitemapEntries(siteUrl: String, net: Net): List<Sitemaps.Entry> {
        val origin = Sitemaps.origin(siteUrl) ?: return emptyList()
        val fromRobots = try { Sitemaps.fromRobots(net.fetch("$origin/robots.txt", "text/plain"), siteUrl) } catch (e: java.io.IOException) { emptyList() }
        for (root in (fromRobots + Sitemaps.candidates(siteUrl)).distinct()) {
            if (!net.active()) return emptyList()
            val xml = try { net.pause(); net.fetch(root, Sitemaps.ACCEPT) } catch (e: java.io.IOException) { continue }
            val top = Sitemaps.parse(xml)
            if (top.maps.isEmpty() && top.pages.isEmpty()) continue
            val out = top.pages.map { Sitemaps.Entry(it, posts = false) }.toMutableList()
            val queue = ArrayDeque(top.maps.filter(Sitemaps::wanted).map { it to 1 })
            val seen = mutableSetOf(root)
            var maps = 0
            while (queue.isNotEmpty() && maps < MAX_MAPS && net.active()) {
                val (m, depth) = queue.removeFirst()
                if (!seen.add(m)) continue
                maps++
                val inner = try { net.pause(); Sitemaps.parse(net.fetch(m, Sitemaps.ACCEPT)) } catch (e: java.io.IOException) { continue }
                val posts = Sitemaps.isPostMap(m)
                inner.pages.forEach { out += Sitemaps.Entry(it, posts) }
                if (depth < 3) inner.maps.filter(Sitemaps::wanted).forEach { queue.addLast(it to depth + 1) }
            }
            return out
        }
        return emptyList()
    }

    private const val MAX_MAPS = 300

    /** [reachable] is false when the type's listing refused its first page, so it says nothing about the site. */
    private class Listing(val books: List<CatalogBook>, val stop: String?, val reachable: Boolean = true)

    private fun listing(siteUrl: String, title: String, t: WordPress.Type, net: Net, progress: (Int) -> Unit): Listing {
        val out = LinkedHashMap<String, CatalogBook>()
        var page = 1
        while (page <= MAX_PAGES) {
            if (!net.active()) return Listing(out.values.toList(), "cancelled")
            val url = WordPress.listUrl(t, page)
            net.pause()
            val json = try { net.fetch(url, "application/json") } catch (e: HttpError) {
                // A refusal on the first page means the type isn't public; WordPress answers 400 past the last page.
                if (page == 1) return Listing(emptyList(), null, reachable = false)
                return Listing(out.values.toList(), if (e.code == 400 || e.code == 404) null else "at page $page: ${e.message}")
            } catch (e: IOException) {
                if (page == 1) return Listing(emptyList(), null, reachable = false)
                return Listing(out.values.toList(), "at page $page: ${e.message ?: "no answer"}")
            }
            val posts = WordPress.parse(json, siteUrl, title)
            if (posts.isEmpty()) { if (page == 1) return Listing(emptyList(), null, reachable = false); break }
            val before = out.size
            posts.forEach { out.putIfAbsent(it.id, it) }
            if (out.size == before) break
            progress(out.size)
            if (posts.size < WordPress.PER_PAGE) break
            page++
        }
        return Listing(out.values.toList(), null)
    }

    /** The site's listable types, trying the usual REST address and then the plain-links one. Null if not WordPress. */
    private fun wordpressTypes(siteUrl: String, net: Net): List<WordPress.Type>? {
        for (api in WordPress.apiRoots(siteUrl)) {
            if (!net.active()) return null
            val json = try { net.fetch(WordPress.typesUrl(api), "application/json") } catch (e: IOException) { continue }
            WordPress.types(json, api)?.let { return it }
        }
        return null
    }

    /** One readable line for the user about how a read ended. */
    fun summary(o: Outcome): String = buildString {
        append("${"%,d".format(o.books.size)} ${if (o.books.size == 1) "entry" else "entries"}")
        if (o.pages > 1) append(" from ${"%,d".format(o.pages)} feed pages")
        if (o.fromMap > 0) append(", ${"%,d".format(o.fromMap)} of them found through the site map")
        o.stopped?.let { append(". It stopped $it, so some may be missing; Update all feeds tries again and keeps what's here") }
    }
}
