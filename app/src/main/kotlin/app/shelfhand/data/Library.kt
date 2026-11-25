package app.shelfhand.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.shelfhand.core.FileNode
import app.shelfhand.core.LibraryScanner
import app.shelfhand.core.ScannedBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The library is the database. A scan reads the folder you chose and brings the database up to date. */
class Library(private val ctx: Context, private val db: ShelfDb) {
    private val prefs = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)

    var rootUri: Uri?
        get() = prefs.getString("root", null)?.let(Uri::parse)
        private set(v) { prefs.edit().putString("root", v?.toString()).apply() }

    /** Keep read access to the folder across reboots; without this the grant dies with the process. */
    fun setRoot(uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        rootUri = uri
    }

    data class ScanResult(val books: Int, val added: Int, val unknownDuration: Int)

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val root = rootUri?.let { DocumentFile.fromTreeUri(ctx, it) } ?: return@withContext ScanResult(0, 0, 0)
        val retriever = MediaMetadataRetriever()
        val scanned = try {
            LibraryScanner { node -> durationOf(retriever, Uri.parse(node.uri)) }.scan(DocNode(root))
        } finally { retriever.release() }

        val dao = db.dao()
        var added = 0
        scanned.forEach { s ->
            val existing = dao.bookByFolder(s.folderUri)
            if (existing == null) added++
            val id = upsert(dao, s, existing)
            dao.replaceChapters(id, s.files.mapIndexed { i, f -> ChapterEntity(id, i, f.uri, f.name, f.durationMs) })
        }
        if (scanned.isNotEmpty()) dao.markMissing(scanned.map { it.folderUri })
        ScanResult(scanned.size, added, scanned.count { it.totalMs == null })
    }

    private suspend fun upsert(dao: ShelfDao, s: ScannedBook, existing: BookEntity?): Long {
        val e = BookEntity(
            id = existing?.id ?: 0,
            folderUri = s.folderUri,
            title = s.identity.title,
            author = s.identity.author,
            series = s.identity.series,
            seriesIndex = s.identity.seriesIndex,
            coverUri = s.coverUri,
            totalMs = s.totalMs,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            present = true,
        )
        val r = dao.upsertBook(e)
        return existing?.id ?: r
    }

    private fun durationOf(r: MediaMetadataRetriever, uri: Uri): Long? = runCatching {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }.getOrNull()

    private class DocNode(private val f: DocumentFile) : FileNode {
        override val uri: String get() = f.uri.toString()
        override val name: String get() = f.name ?: ""
        override val isDirectory: Boolean get() = f.isDirectory
        override fun children(): List<FileNode> = f.listFiles().map(::DocNode)
    }
}
