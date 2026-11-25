package app.shelfhand.catalog

import android.content.Context
import app.shelfhand.core.catalog.LibriVox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Downloads the whole LibriVox catalog into the on-phone database, a page at a time. */
class CatalogStore(ctx: Context) {
    // Opened on first use, which is always off the main thread.
    private val db by lazy { CatalogHolder.get(ctx) }

    val updatedAt: Long? get() = db.sources().firstOrNull { it.kind == "librivox" }?.updatedAt
    val count: Int get() = db.sources().firstOrNull { it.kind == "librivox" }?.items ?: 0

    suspend fun refresh(progress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val src = db.upsertSource(CatalogHolder.LIBRIVOX_URL, "LibriVox", "librivox")
        var offset = 0
        var n = 0
        while (true) {
            coroutineContext.ensureActive()
            val page = LibriVox.parse(Http.get(LibriVox.pageUrl(offset), allowErrorBody = true))
            if (page.isEmpty()) break
            db.put(src, page)
            n += page.size
            progress(n)
            offset += LibriVox.PAGE
            if (offset > 200_000) break // a guard against a feed that never ends
        }
        n
    }
}
