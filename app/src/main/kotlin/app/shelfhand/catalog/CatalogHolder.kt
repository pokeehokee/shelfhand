package app.shelfhand.catalog

import android.content.Context
import app.shelfhand.core.catalog.CatalogCache
import app.shelfhand.core.catalog.CatalogDb
import java.io.File

/** The one on-phone catalog database, opened once per process. */
object CatalogHolder {
    @Volatile private var db: CatalogDb? = null

    fun get(ctx: Context): CatalogDb = db ?: synchronized(this) {
        db ?: open(ctx.applicationContext).also { db = it }
    }

    private fun open(ctx: Context): CatalogDb {
        val dir = File(ctx.filesDir, "catalog").apply { mkdirs() }
        val d = CatalogDb(AndroidSql(File(dir, "catalog.db").path)).open()
        migrate(dir, d)
        return d
    }

    /** Moves the text-file caches of earlier versions into the database, once. */
    private fun migrate(dir: File, d: CatalogDb) {
        File(dir, "librivox.txt").takeIf { it.exists() }?.let { f ->
            runCatching { d.put(d.upsertSource(LIBRIVOX_URL, "LibriVox", "librivox"), CatalogCache.read(f.readText())) }
            f.delete()
        }
        val feeds = File(dir, "feeds")
        File(feeds, "sources.txt").takeIf { it.exists() }?.let { list ->
            list.readLines().forEach { line ->
                val p = line.split('\u001f')
                if (p.size == 3) runCatching {
                    val id = d.upsertSource(p[0], p[1], "feed", p[2])
                    val cache = feeds.listFiles()?.firstOrNull { it.name.endsWith(".txt") && it.name != "sources.txt" &&
                        CatalogCache.read(it.readText()).firstOrNull()?.source == "feed:" + p[0] }
                    cache?.let { d.put(id, CatalogCache.read(it.readText())) }
                }
            }
            feeds.deleteRecursively()
        }
    }

    const val LIBRIVOX_URL = "https://librivox.org/api/feed/audiobooks"
}
