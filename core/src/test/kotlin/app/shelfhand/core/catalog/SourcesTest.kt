package app.shelfhand.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MagnetTest {
    @Test fun `hex and base32 hashes normalise to the same lower-case hex`() {
        val hex = Magnet.parse("magnet:?xt=urn:btih:C12FE1C06BBA254A9DC9F519B335AA7C1367A88A&dn=Book&tr=udp%3A%2F%2Fa%3A1&tr=udp%3A%2F%2Fa%3A1")!!
        assertEquals("c12fe1c06bba254a9dc9f519b335aa7c1367a88a", hex.infoHash)
        assertEquals(listOf("udp://a:1"), hex.trackers)
        assertEquals("Book", hex.name)
        assertEquals(hex.infoHash, Magnet.parse("magnet:?xt=urn:btih:YEX6DQDLXISUVHOJ6UM3GNNKPQJWPKEK")!!.infoHash)
    }

    @Test fun `anything else is not a magnet`() {
        assertNull(Magnet.parse("https://x/a.torrent"))
        assertNull(Magnet.parse("magnet:?xt=urn:btmh:1220abc"))
        assertNull(Magnet.parse("magnet:?xt=urn:btih:nothex"))
    }
}

class FeedPagingTest {
    @Test fun `an atom or rss next link is followed, and wordpress feeds are recognised`() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>C</title><link rel="next" href="?page=2"/>
            <entry><id>1</id><title>A</title></entry></feed>"""
        assertEquals("https://c.example/opds?page=2", Feeds.parsePage(atom, "https://c.example/opds").next)
        val wp = """<?xml version="1.0"?><rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>W</title>
            <atom:link href="https://w.example/feed/" rel="self"/><link>https://w.example</link>
            <generator>https://wordpress.org/?v=6.8</generator><item><title>Book One</title></item></channel></rss>"""
        val page = Feeds.parsePage(wp, "https://w.example/feed/")
        assertNull(page.next)
        assertTrue(Feeds.isWordPress(wp))
        assertEquals("https://w.example/feed/?paged=3", Feeds.pagedUrl("https://w.example/feed/?paged=2", 3))
        assertEquals("https://w.example/feed/?cat=4&paged=2", Feeds.pagedUrl("https://w.example/feed/?cat=4", 2))
    }

    @Test fun `a serialized podcast spread over pages still becomes one book`() {
        fun page(n: Int, next: Boolean) = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:atom="http://www.w3.org/2005/Atom"><channel>
            <title>Serial</title><itunes:author>Jo</itunes:author><itunes:type>serial</itunes:type>
            ${if (next) """<atom:link rel="next" href="/feed?p=2"/>""" else ""}
            <item><title>Chapter ${n * 2 - 1}</title><itunes:duration>60</itunes:duration></item>
            <item><title>Chapter ${n * 2}</title><itunes:duration>60</itunes:duration></item></channel></rss>"""
        val p1 = Feeds.parsePage(page(1, true), "https://s.example/feed")
        val p2 = Feeds.parsePage(page(2, false), "https://s.example/feed?p=2")
        assertEquals("https://s.example/feed?p=2", p1.next)
        val book = p1.finish(p1.items + p2.items).single()
        assertEquals(240L, book.seconds)
        assertEquals(4, book.sections)
    }
}

class WordPressTest {
    @Test fun `posts give titles, categories, the audio link and a magnet from the post`() {
        val json = """[{"id":12,"date":"2024-05-01T10:00:00","link":"https://w.example/the-book/",
            "title":{"rendered":"The Sea&#8217;s Book, Book 2"},
            "excerpt":{"rendered":"<p>Short blurb &amp; more</p>"},
            "content":{"rendered":"<p><a href=\"https://w.example/files/sea.m4b\">Listen</a> <a href=\"magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&amp;dn=sea\">Torrent</a></p>"},
            "_embedded":{"wp:term":[[{"taxonomy":"category","name":"Fantasy"},{"taxonomy":"category","name":"Uncategorized"}],[{"taxonomy":"post_tag","name":"tag"}]]}}]"""
        val b = WordPress.parse(json, "https://w.example/", "W").single()
        assertEquals("The Sea’s Book, Book 2", b.title)
        assertEquals(listOf("Fantasy"), b.genres)
        assertEquals("https://w.example/files/sea.m4b", b.downloadUrl)
        assertEquals("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=sea", b.torrentUrl)
        assertEquals("Short blurb & more", b.description)
        assertEquals(2024, b.year)
        assertEquals(SeriesGuess("The Sea’s Book", 2.0), b.series)
    }

    @Test fun `past the last page and odd addresses give nothing`() {
        assertTrue(WordPress.parse("""{"code":"rest_post_invalid_page_number"}""", "https://w.example/", "W").isEmpty())
        assertNull(WordPress.postsUrl("ftp://w.example/", 1))
        assertEquals("https://w.example/wp-json/wp/v2/posts?per_page=100&page=3&_embed=wp:term&_fields=id,guid,link,date,title,excerpt,content,_links,_embedded",
            WordPress.postsUrl("https://w.example/books/feed/", 3))
    }
}

class OpenLibraryTest {
    private val docs = OpenLibrary.parse("""{"docs":[
        {"key":"/works/OL1W","title":"A Princess of Mars","author_name":["Edgar Rice Burroughs"],"first_publish_year":1912,
         "subject":["Fiction","Accessible book","Science fiction","Mars (Planet), fiction","Protected DAISY","1912"],"series":["Barsoom ; 1"]},
        {"key":"/works/OL2W","title":"Emma","author_name":["Someone Else"],"first_publish_year":2001}]}""")

    @Test fun `title and author surname must both match`() {
        val m = OpenLibrary.match("A Princess of Mars (version 2)", listOf("Edgar Rice Burroughs"), docs)!!
        assertEquals("/works/OL1W", m.ref)
        assertEquals(1912, m.firstYear)
        assertEquals(SeriesGuess("Barsoom", 1.0), m.series)
        assertEquals(listOf("Fiction", "Science fiction", "Mars (Planet)"), m.subjects)
        assertNull(OpenLibrary.match("Emma", listOf("Jane Austen"), docs))
        assertNull(OpenLibrary.match("A Princess of Mars", emptyList(), docs))
    }

    @Test fun `series strings in their common shapes`() {
        assertEquals(SeriesGuess("Tarzan", 2.0), OpenLibrary.series("Tarzan (2)"))
        assertEquals(SeriesGuess("Oz", 4.0), OpenLibrary.series("Oz, #4"))
        assertEquals(SeriesGuess("Barsoom", null), OpenLibrary.series("Barsoom series"))
    }

    @Test fun `the pass stops after three failures in a row and retries them later`() {
        val db = CatalogDb(JdbcSql()).open()
        val src = db.upsertSource("https://x", "X", "feed")
        db.put(src, (1..5).map { CatalogBook("t", "$it", "Book $it", listOf("Ann Bell"), null, emptyList(), null, null, null, null, null, null) })
        val r = Lookups.run(db, { throw java.io.IOException("offline") }, {}, max = 10)
        assertEquals(3, r.tried)
        assertTrue(r.stoppedEarly)
        assertEquals(listOf("Book 4", "Book 5"), db.pendingLookups(10).map { it.title }) // the three failures wait a week
        val ok = Lookups.run(db, { """{"docs":[{"key":"/works/OL9W","title":"Book 4","author_name":["Ann Bell"],"first_publish_year":1950}]}""" }, {}, max = 10)
        assertEquals(1, ok.matched)
        assertEquals(1950, db.search(Filter(text = "book 4")).books.single().year)
    }
}
