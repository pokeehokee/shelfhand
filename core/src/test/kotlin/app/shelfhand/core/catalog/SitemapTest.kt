package app.shelfhand.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SitemapTest {
    private val site = "https://w.example"

    private fun feed(from: Int, to: Int) =
        """<?xml version="1.0"?><rss version="2.0"><channel><title>W</title><link>$site</link>
        <generator>https://wordpress.org/?v=6.8</generator>
        ${(from..to).joinToString("") { "<item><title>Book $it</title><link>$site/b$it/</link><guid isPermaLink=\"false\">$site/?p=$it</guid></item>" }}
        </channel></rss>"""

    private fun urlset(vararg urls: String) =
        """<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${urls.joinToString("") { "<url><loc>$it</loc></url>" }}</urlset>"""

    private fun index(vararg maps: String) =
        """<?xml version="1.0" encoding="UTF-8"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${maps.joinToString("") { "<sitemap><loc>$it</loc></sitemap>" }}</sitemapindex>"""

    private fun post(n: Int, extra: String = "") = """<!doctype html><html><head><title>Book $n – W</title>
        <meta property="og:site_name" content="W"><meta property="og:title" content="Book $n">
        <meta property="og:description" content="About book $n &amp; more"><meta property="article:published_time" content="2023-02-01T00:00:00+00:00">
        <link rel='shortlink' href='$site/?p=$n' /></head><body><a href="/files/b$n.mp3">Listen</a>$extra</body></html>"""

    private class FakeNet(val pages: Map<String, () -> String>) {
        val asked = mutableListOf<String>()
        val net = SiteReader.Net(fetch = { url, _ -> asked += url; (pages[url] ?: throw HttpError(404))() }, pause = {})
    }

    private val wpMaps = mapOf(
        "$site/wp-sitemap.xml" to { index("$site/wp-sitemap-posts-post-1.xml", "$site/wp-sitemap-posts-page-1.xml",
            "$site/wp-sitemap-taxonomies-category-1.xml", "$site/wp-sitemap-users-1.xml") },
        "$site/wp-sitemap-posts-post-1.xml" to { urlset(*(1..12).map { "$site/b$it/" }.toTypedArray()) },
    )

    @Test fun `the site map finds the posts the feed left out, reading only those pages`() {
        val f = FakeNet(wpMaps + mapOf("$site/b11/" to { post(11) }, "$site/b12/" to { post(12) }))
        val o = SiteReader.read("$site/feed/", feed(1, 10), f.net) {}
        assertEquals(12, o.books.size)
        assertEquals(2, o.fromMap)
        assertNull(o.stopped)
        val b = o.books.single { it.title == "Book 12" }
        assertEquals("$site/?p=12", b.id) // the short link is the feed's guid, so a later feed read merges with it
        assertEquals("$site/files/b12.mp3", b.downloadUrl)
        assertEquals("About book 12 & more", b.description)
        assertEquals(2023, b.year)
        assertTrue(f.asked.none { "posts-page" in it || "taxonomies" in it || "users" in it || it.endsWith("/b3/") })
        assertTrue(SiteReader.summary(o).contains("2 of them found through the site map"))
    }

    @Test fun `pages the phone already holds are not read again`() {
        val f = FakeNet(wpMaps + mapOf("$site/b12/" to { post(12) }))
        val o = SiteReader.read("$site/feed/", feed(1, 10), f.net, known = setOf(Sitemaps.key("http://www.w.example/b11"))) {}
        assertEquals(1, o.fromMap)
        assertTrue(f.asked.none { it.endsWith("/b11/") })
    }

    @Test fun `a big map is read a slice at a time and says how much is left`() {
        val many = (1..SiteReader.MAP_PAGES_PER_READ + 50).map { "$site/x$it/" }
        val f = FakeNet(mapOf("$site/sitemap.xml" to { urlset(*many.toTypedArray()) }) +
            many.associateWith { u -> { post(u.filter(Char::isDigit).toInt() + 1000) } })
        val o = SiteReader.read("$site/feed/", feed(1, 1), f.net) {}
        assertEquals(SiteReader.MAP_PAGES_PER_READ, o.fromMap)
        assertTrue(o.stopped!!.contains("50 more site-map pages"), o.stopped)
    }

    @Test fun `a general map keeps entries and drops about pages, and robots names the map`() {
        val plain = "https://s.example"
        val f = FakeNet(mapOf(
            "$plain/robots.txt" to { "User-agent: *\nSitemap: $plain/maps/all.xml\nSitemap: https://other.example/x.xml" },
            "$plain/maps/all.xml" to { urlset("$plain/about/", "$plain/books/sea/", "$plain/logo.png", "https://other.example/y/") },
            "$plain/about/" to { "<html><head><title>About us</title></head><body>Hi</body></html>" },
            "$plain/books/sea/" to { """<html><head><meta property="og:type" content="article"><meta property="og:title" content="The Sea">
                <meta name="author" content="Jo Ink"></head><body><a href="magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&amp;dn=sea">t</a></body></html>""" },
        ))
        val rss = """<rss version="2.0"><channel><title>S</title><item><title>One</title><link>$plain/books/one/</link></item></channel></rss>"""
        val o = SiteReader.read("$plain/feed.xml", rss, f.net, site = "$plain/") {}
        assertEquals(listOf("One", "The Sea"), o.books.map { it.title })
        assertEquals(listOf("Jo Ink"), o.books[1].authors)
        assertTrue(o.books[1].torrentUrl!!.startsWith("magnet:?xt=urn:btih:c12f"))
        assertTrue(f.asked.none { "other.example" in it || "logo.png" in it })
    }

    @Test fun `a podcast host's map is never read`() {
        val f = FakeNet(emptyMap())
        val rss = """<rss version="2.0"><channel><title>P</title><item><title>Ep 1</title></item></channel></rss>"""
        SiteReader.read("https://host.example/show/rss", rss, f.net, site = "https://my.example/") {}
        assertTrue(f.asked.none { "sitemap" in it || "robots" in it })
    }

    @Test fun `addresses match however they are written`() {
        assertEquals(Sitemaps.key("https://www.W.example/b1/"), Sitemaps.key("http://w.example/b1"))
        assertTrue(Sitemaps.wanted("$site/post-sitemap2.xml"))
        assertTrue(!Sitemaps.wanted("$site/page-sitemap.xml") && !Sitemaps.wanted("$site/category-sitemap.xml"))
        assertTrue(Sitemaps.isPostMap("$site/audiobook-sitemap.xml") && !Sitemaps.isPostMap("$site/sitemap.xml"))
    }
}
