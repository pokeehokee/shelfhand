package app.shelfhand.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibriVoxParseTest {
    private val page = """
    {"books":[
      {"id":"52","title":"The Count of Monte Cristo","description":"<p>Edmond &amp; the island</p>","language":"English",
       "copyright_year":"1844","num_sections":"117","totaltimesecs":189433,"url_librivox":"https://librivox.org/the-count/",
       "url_zip_file":"https://archive.org/x.zip",
       "authors":[{"id":"1","first_name":"Alexandre","last_name":"Dumas"}],
       "genres":[{"id":"1","name":"Action & Adventure Fiction"},{"id":"2","name":"Historical Fiction"}]},
      {"id":"53","title":"Short Poem","language":"English","totaltimesecs":"0","num_sections":"1",
       "authors":[],"genres":[],"url_zip_file":null,"description":null},
      {"id":"","title":"no id"}
    ]}""".trimIndent()

    @Test fun `reads the fields the feed carries and strips html`() {
        val b = LibriVox.parse(page)
        assertEquals(2, b.size)
        with(b[0]) {
            assertEquals("The Count of Monte Cristo", title)
            assertEquals(listOf("Alexandre Dumas"), authors)
            assertEquals(189433L, seconds)
            assertEquals(117, sections)
            assertEquals(listOf("Action & Adventure Fiction", "Historical Fiction"), genres)
            assertEquals("Edmond & the island", description)
        }
    }

    @Test fun `a zero or missing length is unknown, and json null is not the word null`() {
        val b = LibriVox.parse(page)[1]
        assertNull(b.seconds)
        assertNull(b.downloadUrl)
        assertNull(b.description)
    }

    @Test fun `past the last page the api answers with an error, which means no more books`() {
        assertEquals(emptyList(), LibriVox.parse("""{"error":"Audiobooks could not be found"}"""))
    }

    @Test fun `the page url asks for the fields filters need`() {
        val u = LibriVox.pageUrl(2000)
        assertTrue("offset=2000" in u && "extended=1" in u && "totaltimesecs" in java.net.URLDecoder.decode(u, "UTF-8"))
    }
}

class SeriesGuessTest {
    @Test fun `common series title shapes`() {
        assertEquals(SeriesGuess("Barsoom", 3.0), SeriesGuess.of("Barsoom, Volume 3"))
        assertEquals(SeriesGuess("Tom Swift", 12.0), SeriesGuess.of("Tom Swift 12: Tom Swift and His Airship"))
        assertEquals(SeriesGuess("The Lost World", 2.0), SeriesGuess.of("The Lost World (Book 2)"))
        assertEquals(SeriesGuess("Plutarch's Lives", 2.0), SeriesGuess.of("Plutarch's Lives Vol. II"))
    }

    @Test fun `standalones and years are not series`() {
        assertNull(SeriesGuess.of("The Count of Monte Cristo"))
        assertNull(SeriesGuess.of("Poems 1912: A Selection"))
        assertNull(SeriesGuess.of("Twenty Thousand Leagues Under the Sea"))
    }
}

class CatalogCacheTest {
    private val books = listOf(CatalogBook("t", "1", "Short Mystery", listOf("A Writer"), "English", listOf("Mystery"), 7200, null, null, null, null, null))

    @Test fun `the cache round-trips every field`() {
        val full = CatalogBook("librivox", "9", "T", listOf("A B", "C D"), "English", listOf("G1", "G2"), 100, 3, 1900,
            "https://p", "https://z", "line one\nline two", "magnet:?xt=urn:btih:abc", "Jane's Stories", "https://f/rss")
        val back = CatalogCache.read(CatalogCache.write(books + full))
        assertEquals(books + full.copy(description = "line one line two"), back)
    }
}
