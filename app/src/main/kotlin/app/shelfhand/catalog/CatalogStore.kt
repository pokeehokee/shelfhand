package app.shelfhand.catalog

import android.content.Context
import app.shelfhand.core.catalog.CatalogBook
import app.shelfhand.core.catalog.CatalogCache
import app.shelfhand.core.catalog.LibriVox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Downloads the LibriVox catalog once, keeps it on the phone, and filters it locally. */
class CatalogStore(ctx: Context) {
    private val file = File(ctx.filesDir, "catalog/librivox.txt")

    val updatedAt: Long? get() = file.takeIf { it.exists() }?.lastModified()

    suspend fun load(): List<CatalogBook> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else CatalogCache.read(file.readText())
    }

    /** Reads every page; only replaces the saved copy once the whole catalog has arrived. */
    suspend fun refresh(progress: (Int) -> Unit): List<CatalogBook> = withContext(Dispatchers.IO) {
        val all = mutableListOf<CatalogBook>()
        var offset = 0
        while (true) {
            coroutineContext.ensureActive()
            val page = LibriVox.parse(get(LibriVox.pageUrl(offset)))
            if (page.isEmpty()) break
            all += page
            progress(all.size)
            offset += LibriVox.PAGE
            if (offset > 200_000) break // a guard against a feed that never ends
        }
        val books = all.distinctBy { it.id }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "librivox.tmp")
        tmp.writeText(CatalogCache.write(books))
        tmp.renameTo(file)
        books
    }

    private fun get(url: String): String {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000
                c.readTimeout = 90_000
                c.setRequestProperty("User-Agent", "Shelfhand audiobook player")
                try {
                    // LibriVox answers 404 with an error body past the last page.
                    val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    if (c.responseCode == 404 && "error" in body) return body
                    if (c.responseCode !in 200..299) throw java.io.IOException("LibriVox answered ${c.responseCode}")
                    return body
                } finally { c.disconnect() }
            } catch (e: Exception) {
                last = e
                Thread.sleep(2_000L * (attempt + 1))
            }
        }
        throw last ?: java.io.IOException("LibriVox did not answer")
    }
}
