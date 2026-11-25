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
        assertNull(b.zipUrl)
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

class CatalogFilterTest {
    private fun b(id: String, title: String, sec: Long?, genres: List<String> = emptyList(), lang: String = "English", author: String = "A Writer") =
        CatalogBook("t", id, title, listOf(author), lang, genres, sec, null, null, null, null, null)

    private val books = listOf(
        b("1", "Short Mystery", 2 * 3600L, listOf("Mystery")),
        b("2", "Long Mystery Romance", 20 * 3600L, listOf("Mystery", "Romance")),
        b("3", "Unknown Length", null, listOf("Mystery")),
        b("4", "Barsoom, Volume 3", 9 * 3600L, listOf("Science Fiction")),
        b("5", "Le Roman", 5 * 3600L, listOf("Romance"), lang = "French"),
    )

    @Test fun `length bounds drop books of unknown length`() {
        val r = CatalogFilter.apply(books, Filter(minSeconds = 3 * 3600L, maxSeconds = 10 * 3600L))
        assertEquals(listOf("4", "5"), r.map { it.id }.sorted())
    }

    @Test fun `genres combine with all or any, and can be excluded`() {
        assertEquals(listOf("2"), CatalogFilter.apply(books, Filter(genres = setOf("Mystery", "Romance"), genreMatch = GenreMatch.ALL)).map { it.id })
        assertEquals(4, CatalogFilter.apply(books, Filter(genres = setOf("Mystery", "Romance"))).size)
        assertEquals(listOf("1", "3"), CatalogFilter.apply(books, Filter(genres = setOf("Mystery"), excludeGenres = setOf("Romance"))).map { it.id }.sorted())
    }

    @Test fun `series only and standalone only split the catalog`() {
        assertEquals(listOf("4"), CatalogFilter.apply(books, Filter(series = SeriesFilter.SERIES_ONLY)).map { it.id })
        assertEquals(4, CatalogFilter.apply(books, Filter(series = SeriesFilter.STANDALONE_ONLY)).size)
    }

    @Test fun `text matches every word, language narrows, unknown lengths sort last both ways`() {
        assertEquals(listOf("2"), CatalogFilter.apply(books, Filter(text = "mystery romance")).map { it.id })
        assertEquals(listOf("5"), CatalogFilter.apply(books, Filter(languages = setOf("French"))).map { it.id })
        assertEquals("3", CatalogFilter.apply(books, Filter(sort = Sort.SHORTEST)).last().id)
        assertEquals("3", CatalogFilter.apply(books, Filter(sort = Sort.LONGEST)).last().id)
        assertEquals("2", CatalogFilter.apply(books, Filter(sort = Sort.LONGEST)).first().id)
    }

    @Test fun `the cache round-trips every field`() {
        val full = CatalogBook("librivox", "9", "T", listOf("A B", "C D"), "English", listOf("G1", "G2"), 100, 3, 1900,
            "https://p", "https://z", "line one\nline two")
        val back = CatalogCache.read(CatalogCache.write(books + full))
        assertEquals(books + full.copy(description = "line one line two"), back)
    }
}
