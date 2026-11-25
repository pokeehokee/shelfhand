package app.shelfhand.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import app.shelfhand.core.FileNode
import app.shelfhand.core.LibraryScanner
import app.shelfhand.core.ScannedBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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

    /** False when Android no longer holds the grant (app data cleared, folder moved): the folder must be chosen again. */
    fun hasAccess(): Boolean {
        val root = rootUri ?: return false
        return ctx.contentResolver.persistedUriPermissions.any { it.uri == root && it.isReadPermission }
    }

    data class ScanResult(val books: Int, val added: Int)

    /**
     * Reads the folder tree and saves every book, without opening any audio file. Lengths come after,
     * in [readLengths]: opening each file is what made the first version sit on "Scanning" for minutes.
     */
    suspend fun scan(progress: (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val root = rootUri ?: error("No folder chosen")
        if (!hasAccess()) throw SecurityException("Shelfhand lost access to that folder. Choose it again.")
        val treeDocId = DocumentsContract.getTreeDocumentId(root)
        val scanned = LibraryScanner(progress = { folders, books ->
            if (folders % 5 == 0) progress("Reading folders: $folders read, $books books found")
        }).scan(TreeNode(root, treeDocId, "", true))

        progress("Saving ${scanned.size} books")
        val dao = db.dao()
        var added = 0
        scanned.forEach { s ->
            coroutineContext.ensureActive()
            val existing = dao.bookByFolder(s.folderUri)
            if (existing == null) added++
            val id = upsert(dao, s, existing)
            // Keep lengths already read for files that are still there, so a rescan does not re-open them.
            val known = dao.chapters(id).associate { it.uri to it.durationMs }
            dao.replaceChapters(id, s.files.mapIndexed { i, f -> ChapterEntity(id, i, f.uri, f.name, known[f.uri]) })
            dao.refreshBookLength(id)
        }
        // SQLite caps bound variables, so a library past that size skips marking missing books this time.
        if (scanned.isNotEmpty() && scanned.size < 900) dao.markMissing(scanned.map { it.folderUri })
        ScanResult(scanned.size, added)
    }

    /** Opens each file whose length is not known yet. Slow on big libraries, so it runs after the books are visible. */
    suspend fun readLengths(progress: (done: Int, total: Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val dao = db.dao()
        val todo = dao.chaptersWithoutLength()
        val retriever = MediaMetadataRetriever()
        var unreadable = 0
        try {
            todo.forEachIndexed { i, ch ->
                coroutineContext.ensureActive()
                val ms = runCatching {
                    retriever.setDataSource(ctx, Uri.parse(ch.uri))
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                }.getOrNull()
                if (ms != null && ms > 0) dao.setChapterLength(ch.bookId, ch.idx, ms) else unreadable++
                val lastOfBook = todo.getOrNull(i + 1)?.bookId != ch.bookId
                if (lastOfBook) dao.refreshBookLength(ch.bookId)
                if (i % 10 == 0 || i == todo.lastIndex) progress(i + 1, todo.size)
            }
        } finally { retriever.release() }
        unreadable
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
            totalMs = existing?.totalMs,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            present = true,
        )
        val r = dao.upsertBook(e)
        return existing?.id ?: r
    }

    /**
     * A folder read with one query that returns every child's name and type together.
     * DocumentFile asks the provider again for each name and each "is this a folder", which on a
     * library of a few hundred files is thousands of round trips.
     */
    private inner class TreeNode(
        private val tree: Uri,
        private val docId: String,
        override val name: String,
        override val isDirectory: Boolean,
    ) : FileNode {
        override val uri: String get() = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString()

        override fun children(): List<FileNode> {
            if (!isDirectory) return emptyList()
            val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val out = mutableListOf<FileNode>()
            ctx.contentResolver.query(q, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val n = c.getString(1) ?: continue
                    val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    out += TreeNode(tree, id, n, dir)
                }
            }
            return out
        }
    }
}
