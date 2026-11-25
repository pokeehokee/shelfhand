package app.shelfhand.core.catalog

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiteReaderTest {
    private val site = "https://w.example"

    /** A WordPress-style feed page of posts [from]..[to], each with the guid WordPress gives it. */
    private fun page(from: Int, to: Int, generator: Boolean = true, title: (Int) -> String = { "Book $it" }) =
        """<?xml version="1.0"?><rss version="2.0"><channel><title>W</title><link>$site</link>
        ${if (generator) "<generator>https://wordpress.org/?v=6.8</generator>" else ""}
        ${(from..to).joinToString("") { "<item><title>${title(it)}</title><link>$site/b$it/</link><guid isPermaLink=\"false\">$site/?p=$it</guid></item>" }}
        </channel></rss>"""

    private fun posts(ids: IntRange, type: String = "post") = ids.joinToString(",", "[", "]") {
        """{"id":$it,"guid":{"rendered":"${if (type == "post") "$site/?p=$it" else "$site/?post_type=$type&#038;p=$it"}"},
            "link":"$site/b$it/","date":"2024-01-01T00:00:00","title":{"rendered":"${type.replaceFirstChar(Char::titlecase)} $it"},
            "excerpt":{"rendered":""},"content":{"rendered":""}}"""
    }

    private val types = """{"post":{"slug":"post","name":"Posts","rest_base":"posts","hierarchical":false},
        "page":{"slug":"page","name":"Pages","rest_base":"pages","hierarchical":true},
        "attachment":{"slug":"attachment","name":"Media","rest_base":"media","hierarchical":false},
        "wp_block":{"slug":"wp_block","name":"Patterns","rest_base":"blocks","hierarchical":false},
        "audiobook":{"slug":"audiobook","name":"Audiobooks","rest_base":"audiobooks","hierarchical":false}}"""

    private class FakeNet(val pages: Map<String, () -> String>) {
        val asked = mutableListOf<String>()
        val net = SiteReader.Net(fetch = { url, _ -> asked += url; (pages[url] ?: throw HttpError(404))() }, pause = {})
    }

    private fun feed(n: Int) = if (n == 1) "$site/feed/" else "$site/feed/?paged=$n"

    @Test fun `a wordpress feed is read to its last page and nothing is reported missing`() {
        val f = FakeNet(mapOf(feed(2) to { page(11, 20) }, feed(3) to { page(21, 25) }))
        val o = SiteReader.read(feed(1), page(1, 10), f.net) {}
        assertEquals(25, o.books.size)
        assertEquals(3, o.pages)
        assertNull(o.stopped)
    }

    @Test fun `a feed with its generator line removed still pages, and a refusal partway keeps what was read`() {
        val f = FakeNet(mapOf(feed(2) to { page(11, 20, generator = false) }, feed(3) to { throw HttpError(429, 30) }))
        val o = SiteReader.read(feed(1), page(1, 10, generator = false), f.net) {}
        assertEquals(20, o.books.size)
        assertTrue(o.stopped!!.contains("page 3") && o.stopped!!.contains("429"), o.stopped)
        assertTrue(SiteReader.summary(o).contains("stopped at page 3"))
    }

    @Test fun `when the feed stops early the site's own listing fills the rest without doubling up`() {
        val api = "$site/wp-json/"
        val f = FakeNet(mapOf(
            feed(2) to { throw IOException("timeout") },
            WordPress.typesUrl(api) to { types },
            WordPress.listUrl(WordPress.Type("post", "Posts", "posts", api), 1) to { posts(1..100) },
            WordPress.listUrl(WordPress.Type("post", "Posts", "posts", api), 2) to { posts(101..130) },
        ))
        val o = SiteReader.read(feed(1), page(1, 10), f.net) {}
        assertEquals(130, o.books.size)
        assertEquals(130, o.books.map { it.id }.toSet().size)
        assertNull(o.stopped) // the listing reached the end the feed could not
        assertTrue(f.asked.none { "/pages" in it || "/media" in it || "/blocks" in it })
    }

    @Test fun `books kept as a custom post type are read too`() {
        val api = "$site/wp-json/"
        val f = FakeNet(mapOf(
            WordPress.typesUrl(api) to { types },
            WordPress.listUrl(WordPress.Type("audiobook", "Audiobooks", "audiobooks", api), 1) to { posts(500..540, "audiobook") },
        ))
        val o = SiteReader.read(feed(1), page(1, 10), f.net) {}
        assertEquals(51, o.books.size)
        assertTrue(o.books.any { it.title == "Audiobook 520" && it.id == "$site/?post_type=audiobook&p=520" })
    }

    @Test fun `a site without pretty links is listed through rest_route`() {
        val plain = "$site/?rest_route=/"
        val f = FakeNet(mapOf(
            WordPress.typesUrl(plain) to { types },
            WordPress.listUrl(WordPress.Type("audiobook", "Audiobooks", "audiobooks", plain), 1) to { posts(1..3, "audiobook") },
        ))
        assertEquals("$site/?rest_route=/wp/v2/types&context=view", WordPress.typesUrl(plain))
        assertEquals(13, SiteReader.read(feed(1), page(1, 10), f.net) {}.books.size)
    }

    @Test fun `a plain podcast feed is one page and a probe that repeats page one is not an error`() {
        val pod = "https://pod.example/rss"
        val f = FakeNet(mapOf("$pod?paged=2" to { page(1, 10, generator = false) }))
        val o = SiteReader.read(pod, page(1, 10, generator = false), f.net) {}
        assertEquals(10, o.books.size)
        assertEquals(1, o.pages)
        assertNull(o.stopped)
    }

    @Test fun `a catalog of numbered parts without audio files stays many books`() {
        val o = SiteReader.read(feed(1), page(1, 10) { "Saga, Part $it" }, FakeNet(emptyMap()).net) {}
        assertEquals(10, o.books.size)
    }

    @Test fun `the comments feed a page lists is tried last`() {
        val html = """<link rel="alternate" type="application/rss+xml" title="C" href="/comments/feed/">
            <link rel="alternate" type="application/rss+xml" title="F" href="/feed/">"""
        assertEquals(listOf("$site/feed/", "$site/comments/feed/"), Feeds.discover(html, "$site/"))
    }
}
