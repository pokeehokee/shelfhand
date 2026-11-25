package app.shelfhand.core.catalog

/**
 * Every catalog the user has added, kept on the phone in SQLite so a filter or sort is a query rather
 * than a pass over a list in memory.
 *
 * - Long descriptions sit in their own table and are read only when a title is opened.
 * - A magnet is stored as its info hash; the trackers are kept once per source, and the link is rebuilt
 *   when the user taps it.
 * - Text search is an external-content FTS5 index (`content='item'`): it holds only the inverted index,
 *   not a second copy of the text. Where the SQLite build has no FTS5 it falls back to FTS4, which
 *   supports external content the same way.
 * - Every value records where it came from: what a source stated is never overwritten by the lookup,
 *   and what the lookup filled in is marked.
 */
class CatalogDb(private val db: Sql, private val now: () -> Long = System::currentTimeMillis) {
    /** "fts5" or "fts4", whichever the SQLite in use supports. */
    lateinit var ftsKind: String
        private set

    fun open(): CatalogDb {
        db.exec("PRAGMA foreign_keys = ON")
        db.exec("""CREATE TABLE IF NOT EXISTS source(
            id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL, added_from TEXT,
            kind TEXT NOT NULL, trackers TEXT, updated_at INTEGER)""")
        db.exec("""CREATE TABLE IF NOT EXISTS item(
            id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL REFERENCES source(id) ON DELETE CASCADE,
            ext_id TEXT NOT NULL, title TEXT NOT NULL, authors TEXT NOT NULL DEFAULT '', language TEXT,
            genres TEXT NOT NULL DEFAULT '', seconds INTEGER, sections INTEGER, year INTEGER,
            page_url TEXT, download_url TEXT, info_hash TEXT, torrent_url TEXT, feed_url TEXT,
            series_name TEXT, series_index REAL, series_from TEXT, looked_up_year INTEGER,
            UNIQUE(source_id, ext_id))""")
        db.exec("CREATE INDEX IF NOT EXISTS item_seconds ON item(seconds)")
        db.exec("CREATE INDEX IF NOT EXISTS item_title ON item(title COLLATE NOCASE)")
        db.exec("""CREATE TABLE IF NOT EXISTS detail(item_id INTEGER PRIMARY KEY REFERENCES item(id) ON DELETE CASCADE, description TEXT)""")
        db.exec("""CREATE TABLE IF NOT EXISTS item_genre(
            item_id INTEGER NOT NULL REFERENCES item(id) ON DELETE CASCADE, genre TEXT NOT NULL,
            looked_up INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(item_id, genre)) WITHOUT ROWID""")
        db.exec("CREATE INDEX IF NOT EXISTS item_genre_genre ON item_genre(genre)")
        db.exec("""CREATE TABLE IF NOT EXISTS lookup(
            item_id INTEGER PRIMARY KEY REFERENCES item(id) ON DELETE CASCADE, status TEXT NOT NULL,
            tried_at INTEGER NOT NULL, ref TEXT)""")
        db.exec("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
        ftsKind = db.query("SELECT v FROM meta WHERE k='fts'") { it.string(0) }.firstOrNull() ?: createFts()
        return this
    }

    private fun createFts(): String {
        val kind = try {
            db.exec("CREATE VIRTUAL TABLE item_fts USING fts5(title, authors, genres, series_name, content='item', content_rowid='id', tokenize='unicode61 remove_diacritics 2')")
            db.exec("CREATE TRIGGER item_ai AFTER INSERT ON item BEGIN INSERT INTO item_fts(rowid, title, authors, genres, series_name) VALUES (new.id, new.title, new.authors, new.genres, new.series_name); END")
            db.exec("CREATE TRIGGER item_ad AFTER DELETE ON item BEGIN INSERT INTO item_fts(item_fts, rowid, title, authors, genres, series_name) VALUES ('delete', old.id, old.title, old.authors, old.genres, old.series_name); END")
            db.exec("CREATE TRIGGER item_au AFTER UPDATE OF title, authors, genres, series_name ON item BEGIN INSERT INTO item_fts(item_fts, rowid, title, authors, genres, series_name) VALUES ('delete', old.id, old.title, old.authors, old.genres, old.series_name); INSERT INTO item_fts(rowid, title, authors, genres, series_name) VALUES (new.id, new.title, new.authors, new.genres, new.series_name); END")
            "fts5"
        } catch (e: Exception) {
            if (!e.toString().contains("fts5", ignoreCase = true)) throw e
            db.exec("CREATE VIRTUAL TABLE item_fts USING fts4(content='item', title, authors, genres, series_name, tokenize=unicode61)")
            db.exec("CREATE TRIGGER item_ai AFTER INSERT ON item BEGIN INSERT INTO item_fts(docid, title, authors, genres, series_name) VALUES (new.id, new.title, new.authors, new.genres, new.series_name); END")
            db.exec("CREATE TRIGGER item_bd BEFORE DELETE ON item BEGIN DELETE FROM item_fts WHERE docid = old.id; END")
            db.exec("CREATE TRIGGER item_bu BEFORE UPDATE OF title, authors, genres, series_name ON item BEGIN DELETE FROM item_fts WHERE docid = old.id; END")
            db.exec("CREATE TRIGGER item_au AFTER UPDATE OF title, authors, genres, series_name ON item BEGIN INSERT INTO item_fts(docid, title, authors, genres, series_name) VALUES (new.id, new.title, new.authors, new.genres, new.series_name); END")
            "fts4"
        }
        db.exec("INSERT INTO meta(k, v) VALUES ('fts', ?)", listOf(kind))
        return kind
    }

    // ---- sources ----

    data class Source(val id: Long, val url: String, val title: String, val addedFrom: String?, val kind: String, val updatedAt: Long?, val items: Int)

    fun sources(): List<Source> = db.query(
        "SELECT s.id, s.url, s.title, s.added_from, s.kind, s.updated_at, (SELECT COUNT(*) FROM item i WHERE i.source_id = s.id) FROM source s ORDER BY s.id",
    ) { Source(it.long(0)!!, it.string(1)!!, it.string(2)!!, it.string(3), it.string(4)!!, it.long(5), it.long(6)!!.toInt()) }

    /** Creates the source, or renames it, and returns its id. */
    fun upsertSource(url: String, title: String, kind: String, addedFrom: String? = null): Long {
        db.exec("""INSERT INTO source(url, title, kind, added_from) VALUES (?, ?, ?, ?)
            ON CONFLICT(url) DO UPDATE SET title = excluded.title, added_from = COALESCE(source.added_from, excluded.added_from)""",
            listOf(url, title, kind, addedFrom))
        return db.query("SELECT id FROM source WHERE url = ?", listOf(url)) { it.long(0)!! }.single()
    }

    fun removeSource(id: Long) = db.exec("DELETE FROM source WHERE id = ?", listOf(id))

    // ---- writing ----

    /**
     * Adds or updates books from one source. Items a refresh no longer lists are kept: an RSS feed shows
     * only its latest entries, so each refresh adds to what the phone already holds.
     */
    fun put(sourceId: Long, books: List<CatalogBook>) = db.transaction {
        val trackers = (trackersOf(sourceId) + books.flatMap { b -> b.torrentUrl?.let(Magnet::parse)?.trackers.orEmpty() })
            .distinct().take(MAX_TRACKERS)
        db.exec("UPDATE source SET trackers = ?, updated_at = ? WHERE id = ?", listOf(trackers.joinToString("\n").ifEmpty { null }, now(), sourceId))
        for (b in books) {
            val magnet = b.torrentUrl?.let(Magnet::parse)
            val guess = SeriesGuess.of(b.title)
            db.exec("""INSERT INTO item(source_id, ext_id, title, authors, language, genres, seconds, sections, year,
                    page_url, download_url, info_hash, torrent_url, feed_url, series_name, series_index, series_from)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(source_id, ext_id) DO UPDATE SET title = excluded.title, authors = excluded.authors,
                    language = excluded.language, genres = excluded.genres, seconds = excluded.seconds,
                    sections = excluded.sections, year = excluded.year, page_url = excluded.page_url,
                    download_url = excluded.download_url, info_hash = excluded.info_hash, torrent_url = excluded.torrent_url,
                    feed_url = excluded.feed_url,
                    series_name = CASE WHEN item.series_from = 'lookup' THEN item.series_name ELSE excluded.series_name END,
                    series_index = CASE WHEN item.series_from = 'lookup' THEN item.series_index ELSE excluded.series_index END,
                    series_from = CASE WHEN item.series_from = 'lookup' THEN 'lookup' ELSE excluded.series_from END""",
                listOf(sourceId, b.id, b.title, b.authors.joinToString(SEP), b.language, b.genres.joinToString(SEP),
                    b.seconds, b.sections, b.year, b.pageUrl, b.downloadUrl, magnet?.infoHash,
                    b.torrentUrl?.takeIf { magnet == null }, b.feedUrl,
                    guess?.name, guess?.index, guess?.let { "title" }))
            val id = db.query("SELECT id FROM item WHERE source_id = ? AND ext_id = ?", listOf(sourceId, b.id)) { it.long(0)!! }.single()
            db.exec("DELETE FROM item_genre WHERE item_id = ? AND looked_up = 0", listOf(id))
            for (g in b.genres.distinct()) db.exec("INSERT OR REPLACE INTO item_genre(item_id, genre, looked_up) VALUES (?, ?, 0)", listOf(id, g))
            if (b.description != null) db.exec("INSERT OR REPLACE INTO detail(item_id, description) VALUES (?, ?)", listOf(id, b.description))
            else db.exec("DELETE FROM detail WHERE item_id = ?", listOf(id))
        }
    }

    private fun trackersOf(sourceId: Long): List<String> =
        db.query("SELECT trackers FROM source WHERE id = ?", listOf(sourceId)) { it.string(0) }.firstOrNull()
            ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    // ---- reading ----

    /** The page addresses already held for the source at [url], as [Sitemaps.key]s. */
    fun pageKeys(url: String): Set<String> =
        db.query("SELECT i.page_url FROM item i JOIN source s ON s.id = i.source_id WHERE s.url = ? AND i.page_url IS NOT NULL", listOf(url)) {
            Sitemaps.key(it.string(0)!!)
        }.toHashSet()

    /** The long description, read only when the user opens a title. */
    fun description(itemId: Long): String? =
        db.query("SELECT description FROM detail WHERE item_id = ?", listOf(itemId)) { it.string(0) }.firstOrNull()

    data class Page(val total: Int, val books: List<CatalogBook>)

    fun search(f: Filter, limit: Int = 500): Page {
        val (where, args) = where(f)
        val total = db.query("SELECT COUNT(*) FROM item i JOIN source s ON s.id = i.source_id WHERE $where", args) { it.long(0)!! }.single().toInt()
        val books = db.query("""SELECT i.id, i.ext_id, i.title, i.authors, i.language, i.genres, i.seconds, i.sections,
                COALESCE(i.year, i.looked_up_year), i.page_url, i.download_url, i.info_hash, i.torrent_url, i.feed_url,
                i.series_name, i.series_index, i.series_from, s.title, s.trackers, s.id, i.year IS NULL AND i.looked_up_year IS NOT NULL
            FROM item i JOIN source s ON s.id = i.source_id WHERE $where ORDER BY ${order(f.sort)} LIMIT ?""", args + limit) { r ->
            val title = r.string(2)!!
            val trackers = r.string(18)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
            CatalogBook(
                source = "db:" + r.long(19), id = r.string(1)!!, title = title,
                authors = r.string(3)!!.split(SEP).filter { it.isNotEmpty() }, language = r.string(4),
                genres = r.string(5)!!.split(SEP).filter { it.isNotEmpty() },
                seconds = r.long(6), sections = r.long(7)?.toInt(), year = r.long(8)?.toInt(),
                pageUrl = r.string(9), downloadUrl = r.string(10), description = null,
                torrentUrl = r.string(11)?.let { Magnet.link(it, title, trackers) } ?: r.string(12),
                collection = r.string(17), feedUrl = r.string(13),
                knownSeries = if (r.string(16) == "lookup") r.string(14)?.let { SeriesGuess(it, r.double(15)) } else null,
                lookedUp = buildSet { if (r.string(16) == "lookup") add("series"); if (r.long(20) == 1L) add("year") },
                dbId = r.long(0),
            )
        }
        return Page(total, books)
    }

    private fun where(f: Filter): Pair<String, List<Any?>> {
        val w = mutableListOf("1")
        val a = mutableListOf<Any?>()
        ftsQuery(f.text)?.let { q ->
            w += if (ftsKind == "fts5") "i.id IN (SELECT rowid FROM item_fts WHERE item_fts MATCH ?)"
            else "i.id IN (SELECT docid FROM item_fts WHERE item_fts MATCH ?)"
            a += q
        }
        if (f.collections.isNotEmpty()) { w += "s.title IN (${marks(f.collections.size)})"; a.addAll(f.collections) }
        // A book of unknown length is dropped once any length bound is set: we cannot say it fits.
        if (f.minSeconds != null || f.maxSeconds != null) w += "i.seconds IS NOT NULL"
        f.minSeconds?.let { w += "i.seconds >= ?"; a += it }
        f.maxSeconds?.let { w += "i.seconds <= ?"; a += it }
        // A source that does not say its language is not hidden by a language filter.
        if (f.languages.isNotEmpty()) { w += "(i.language IS NULL OR i.language IN (${marks(f.languages.size)}))"; a.addAll(f.languages) }
        if (f.genres.isNotEmpty()) {
            w += when (f.genreMatch) {
                GenreMatch.ANY -> "i.id IN (SELECT item_id FROM item_genre WHERE genre IN (${marks(f.genres.size)}))"
                GenreMatch.ALL -> "i.id IN (SELECT item_id FROM item_genre WHERE genre IN (${marks(f.genres.size)}) GROUP BY item_id HAVING COUNT(DISTINCT genre) = ${f.genres.size})"
            }
            a.addAll(f.genres)
        }
        if (f.excludeGenres.isNotEmpty()) { w += "i.id NOT IN (SELECT item_id FROM item_genre WHERE genre IN (${marks(f.excludeGenres.size)}))"; a.addAll(f.excludeGenres) }
        when (f.series) {
            SeriesFilter.ANY -> {}
            SeriesFilter.SERIES_ONLY -> w += "i.series_name IS NOT NULL"
            SeriesFilter.STANDALONE_ONLY -> w += "i.series_name IS NULL"
        }
        when (f.access) {
            Access.ANY -> {}
            Access.DOWNLOAD -> w += "i.download_url IS NOT NULL"
            Access.TORRENT -> w += "(i.info_hash IS NOT NULL OR i.torrent_url IS NOT NULL)"
        }
        return w.joinToString(" AND ") to a
    }

    private fun order(s: Sort): String = when (s) {
        Sort.TITLE -> "i.title COLLATE NOCASE"
        Sort.AUTHOR -> "i.authors = '', i.authors COLLATE NOCASE, i.title COLLATE NOCASE"
        // Unknown values go last in both directions.
        Sort.SHORTEST -> "i.seconds IS NULL, i.seconds"
        Sort.LONGEST -> "i.seconds IS NULL, i.seconds DESC"
        Sort.NEWEST_ADDED -> "i.id DESC"
        Sort.OLDEST_WORK -> "COALESCE(i.year, i.looked_up_year) IS NULL, COALESCE(i.year, i.looked_up_year)"
        Sort.NEWEST_WORK -> "COALESCE(i.year, i.looked_up_year) IS NULL, COALESCE(i.year, i.looked_up_year) DESC"
    }

    /** Genre names with how many books carry each, most common first, for the filter chips. */
    fun genreCounts(): List<Pair<String, Int>> =
        db.query("SELECT genre, COUNT(*) c FROM item_genre GROUP BY genre ORDER BY c DESC, genre") { it.string(0)!! to it.long(1)!!.toInt() }

    fun languageCounts(): List<Pair<String, Int>> =
        db.query("SELECT language, COUNT(*) c FROM item WHERE language IS NOT NULL GROUP BY language ORDER BY c DESC") { it.string(0)!! to it.long(1)!!.toInt() }

    fun count(): Int = db.query("SELECT COUNT(*) FROM item") { it.long(0)!!.toInt() }.single()

    // ---- the background lookup ----

    data class Pending(val itemId: Long, val title: String, val authors: List<String>)

    /** Books never looked up, then ones whose lookup failed more than [retryAfterMs] ago. */
    fun pendingLookups(limit: Int, retryAfterMs: Long = 7L * 24 * 3600 * 1000): List<Pending> = db.query(
        """SELECT i.id, i.title, i.authors FROM item i LEFT JOIN lookup l ON l.item_id = i.id
           WHERE l.item_id IS NULL OR (l.status = 'error' AND l.tried_at < ?)
           ORDER BY l.item_id IS NOT NULL, i.id LIMIT ?""",
        listOf(now() - retryAfterMs, limit),
    ) { Pending(it.long(0)!!, it.string(1)!!, it.string(2)!!.split(SEP).filter { s -> s.isNotEmpty() }) }

    fun lookupsDone(): Int = db.query("SELECT COUNT(*) FROM lookup WHERE status != 'error'") { it.long(0)!!.toInt() }.single()

    /**
     * Records a lookup. Only fills gaps: a year the source stated stays, and a series is set only where
     * the source said nothing and the title guess is all there was. Looked-up subjects are added as
     * genres marked as looked up, so they can be filtered on without being mistaken for the source's.
     */
    fun saveLookup(itemId: Long, status: String, found: Lookup? = null) = db.transaction {
        db.exec("INSERT OR REPLACE INTO lookup(item_id, status, tried_at, ref) VALUES (?, ?, ?, ?)", listOf(itemId, status, now(), found?.ref))
        if (found != null) {
            found.firstYear?.let { db.exec("UPDATE item SET looked_up_year = ? WHERE id = ? AND year IS NULL", listOf(it, itemId)) }
            found.series?.let { s ->
                db.exec("UPDATE item SET series_name = ?, series_index = ?, series_from = 'lookup' WHERE id = ? AND (series_from IS NULL OR series_from = 'title' OR series_from = 'lookup')",
                    listOf(s.name, s.index, itemId))
            }
            db.exec("DELETE FROM item_genre WHERE item_id = ? AND looked_up = 1", listOf(itemId))
            for (g in found.subjects) db.exec("INSERT OR IGNORE INTO item_genre(item_id, genre, looked_up) VALUES (?, ?, 1)", listOf(itemId, g))
        }
    }

    /** What the lookup found for one book. */
    data class Lookup(val ref: String, val firstYear: Int?, val series: SeriesGuess?, val subjects: List<String>)

    /** The genres of one book, each marked true where the lookup added it. */
    fun genresOf(itemId: Long): List<Pair<String, Boolean>> =
        db.query("SELECT genre, looked_up FROM item_genre WHERE item_id = ? ORDER BY looked_up, genre", listOf(itemId)) { it.string(0)!! to (it.long(1) == 1L) }

    companion object {
        const val SEP = "\u001e"
        const val MAX_TRACKERS = 30
        private fun marks(n: Int) = List(n) { "?" }.joinToString(",")

        /** Each word must match the start of a word, in any of title, author, genre or series. */
        fun ftsQuery(text: String): String? {
            val words = Regex("""[\p{L}\p{N}]+""").findAll(text).map { it.value }.toList()
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "\"$it\"*" }
        }
    }
}
