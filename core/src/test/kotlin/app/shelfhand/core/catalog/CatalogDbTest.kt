package app.shelfhand.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogDbTest {
    private fun b(id: String, title: String, sec: Long?, genres: List<String> = emptyList(), lang: String? = "English",
                  author: String = "A Writer", desc: String? = null, torrent: String? = null, download: String? = null) =
        CatalogBook("t", id, title, listOf(author), lang, genres, sec, null, null, null, download, desc, torrent)

    private val books = listOf(
        b("1", "Short Mystery", 2 * 3600L, listOf("Mystery"), desc = "A very long description"),
        b("2", "Long Mystery Romance", 20 * 3600L, listOf("Mystery", "Romance"), download = "https://x/2.zip"),
        b("3", "Unknown Length", null, listOf("Mystery")),
        b("4", "Barsoom, Volume 3", 9 * 3600L, listOf("Science Fiction")),
        b("5", "Le Roman", 5 * 3600L, listOf("Romance"), lang = "French", author = "Émile Zola"),
        b("6", "No Language Given", 3600L, lang = null, torrent = "magnet:?xt=urn:btih:ABCDEF0123456789ABCDEF0123456789ABCDEF01&dn=x&tr=udp%3A%2F%2Ft.example%3A80"),
    )

    private fun db(): Pair<CatalogDb, Long> {
        val db = CatalogDb(JdbcSql()).open()
        val src = db.upsertSource("https://site.example/feed", "Site", "feed")
        db.put(src, books)
        return db to src
    }

    private fun ids(db: CatalogDb, f: Filter) = db.search(f).books.map { it.id }.sorted()

    @Test fun `uses an external content fts5 index`() = assertEquals("fts5", db().first.ftsKind)

    @Test fun `length bounds drop books of unknown length`() =
        assertEquals(listOf("4", "5"), ids(db().first, Filter(minSeconds = 3 * 3600L, maxSeconds = 10 * 3600L)))

    @Test fun `genres combine with all or any, and can be excluded`() {
        val d = db().first
        assertEquals(listOf("2"), ids(d, Filter(genres = setOf("Mystery", "Romance"), genreMatch = GenreMatch.ALL)))
        assertEquals(4, d.search(Filter(genres = setOf("Mystery", "Romance"))).total)
        assertEquals(listOf("1", "3"), ids(d, Filter(genres = setOf("Mystery"), excludeGenres = setOf("Romance"))))
    }

    @Test fun `series only and standalone only split the catalog`() {
        val d = db().first
        assertEquals(listOf("4"), ids(d, Filter(series = SeriesFilter.SERIES_ONLY)))
        assertEquals(5, d.search(Filter(series = SeriesFilter.STANDALONE_ONLY)).total)
    }

    @Test fun `text matches word starts across fields, accents ignored`() {
        val d = db().first
        assertEquals(listOf("2"), ids(d, Filter(text = "myst roman")))
        assertEquals(listOf("5"), ids(d, Filter(text = "emile")))
        assertEquals(listOf("4"), ids(d, Filter(text = "science barsoom")))
        assertEquals(6, d.search(Filter(text = " \"*( ")).total) // nothing searchable means no text filter, not an error
    }

    @Test fun `language narrows but never hides a book that states none`() =
        assertEquals(listOf("5", "6"), ids(db().first, Filter(languages = setOf("French"))))

    @Test fun `unknown lengths sort last in both directions`() {
        val d = db().first
        assertEquals("3", d.search(Filter(sort = Sort.SHORTEST)).books.last().id)
        assertEquals("3", d.search(Filter(sort = Sort.LONGEST)).books.last().id)
        assertEquals("2", d.search(Filter(sort = Sort.LONGEST)).books.first().id)
    }

    @Test fun `descriptions are not loaded with results, only on request`() {
        val d = db().first
        val one = d.search(Filter(text = "short")).books.single()
        assertNull(one.description)
        assertEquals("A very long description", d.description(one.dbId!!))
    }

    @Test fun `a magnet is stored as its hash and rebuilt with the source's trackers`() {
        val d = db().first
        val t = d.search(Filter(access = Access.TORRENT)).books.single()
        assertEquals("magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01&dn=No%20Language%20Given&tr=udp%3A%2F%2Ft.example%3A80", t.torrentUrl)
        assertEquals(listOf("2"), ids(d, Filter(access = Access.DOWNLOAD)))
    }

    @Test fun `a refresh updates items and keeps ones the feed no longer lists`() {
        val (d, src) = db()
        d.put(src, listOf(b("1", "Short Mystery Renamed", 2 * 3600L, listOf("Thriller"))))
        assertEquals(6, d.count())
        assertEquals(listOf("1"), ids(d, Filter(text = "renamed")))
        assertEquals(listOf("1"), ids(d, Filter(genres = setOf("Thriller"))))
        assertEquals(0, d.search(Filter(text = "short mystery renamed", genres = setOf("Mystery"))).total)
        assertNull(d.description(d.search(Filter(text = "renamed")).books.single().dbId!!))
    }

    @Test fun `removing a source removes its books and their index entries`() {
        val (d, src) = db()
        d.removeSource(src)
        assertEquals(0, d.count())
        assertEquals(0, d.search(Filter(text = "mystery")).total)
        assertTrue(d.genreCounts().isEmpty())
    }

    @Test fun `the lookup fills gaps, marks them, and never overwrites the source`() {
        val (d, _) = db()
        val id = d.search(Filter(text = "short")).books.single().dbId!!
        d.saveLookup(id, "found", CatalogDb.Lookup("/works/OL1W", 1901, SeriesGuess("Detective Tales", 2.0), listOf("Detective and mystery stories")))
        val got = d.search(Filter(text = "short")).books.single()
        assertEquals(1901, got.year)
        assertEquals(SeriesGuess("Detective Tales", 2.0), got.series)
        assertEquals(setOf("series", "year"), got.lookedUp)
        assertEquals(listOf("1"), ids(d, Filter(genres = setOf("Detective and mystery stories"))))
        assertEquals(listOf("1"), ids(d, Filter(text = "detective"))) // the looked-up series is searchable
        // A refresh from the source keeps the looked-up series.
        d.put(d.sources().single().id, listOf(b("1", "Short Mystery", 2 * 3600L, listOf("Mystery"))))
        assertEquals(SeriesGuess("Detective Tales", 2.0), d.search(Filter(text = "short")).books.single().series)
        assertTrue(d.pendingLookups(10).none { it.itemId == id })
    }
}
