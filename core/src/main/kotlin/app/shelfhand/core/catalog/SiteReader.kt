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
    data class Outcome(val title: String, val books: List<CatalogBook>, val pages: Int, val stopped: String?)

    /** [fetch] takes an address and an Accept header. [pause] runs between requests, to stay polite. */
    class Net(val fetch: (String, String) -> String, val pause: () -> Unit, val active: () -> Boolean = { true })

    const val FEED_ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5"
    const val MAX_PAGES = 5000

    fun read(feedUrl: String, firstXml: String, net: Net, progress: (String) -> Unit): Outcome {
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
        return Outcome(title, books, pageNo, stopped)
    }

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
        o.stopped?.let { append(". It stopped $it, so some may be missing; Update all feeds tries again and keeps what's here") }
    }
}
