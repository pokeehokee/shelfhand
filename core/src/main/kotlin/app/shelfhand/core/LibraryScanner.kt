package app.shelfhand.core

/** The scanner's view of storage, so the rules can be tested without Android. */
interface FileNode {
    val uri: String
    val name: String
    val isDirectory: Boolean
    fun children(): List<FileNode>
}

/**
 * Walks a library root and returns one book per folder that directly holds audio files.
 * A folder holding only subfolders ("Author/") is a container, not a book.
 */
class LibraryScanner(
    /** Called after each folder with (folders read, books found), so a long scan shows it is moving. */
    private val progress: (Int, Int) -> Unit = { _, _ -> },
    private val durationOf: (FileNode) -> Long? = { null },
) {
    private var folders = 0


    fun scan(root: FileNode): List<ScannedBook> {
        val out = mutableListOf<ScannedBook>()
        walk(root, emptyList(), out)
        return out
    }

    private fun walk(dir: FileNode, path: List<String>, out: MutableList<ScannedBook>) {
        val kids = dir.children().filterNot { it.name.startsWith(".") }
        val audio = kids.filter { !it.isDirectory && isAudio(it.name) }.sortedWith(compareBy(NaturalOrder) { it.name })
        if (audio.isNotEmpty() && path.isNotEmpty()) {
            out += ScannedBook(
                folderUri = dir.uri,
                identity = PathParser.parse(path),
                files = audio.map { AudioFile(it.uri, it.name, durationOf(it)) },
                coverUri = kids.firstOrNull { !it.isDirectory && isCover(it.name) }?.uri,
            )
        }
        progress(++folders, out.size)
        kids.filter { it.isDirectory }.forEach { walk(it, path + it.name, out) }
    }

    companion object {
        val AUDIO = setOf("m4b", "m4a", "mp3", "aac", "ogg", "opus", "flac", "wav")
        fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO
        fun isCover(name: String): Boolean {
            val n = name.lowercase()
            return n.substringAfterLast('.') in setOf("jpg", "jpeg", "png", "webp") &&
                (n.startsWith("cover") || n.startsWith("folder") || n.startsWith("front"))
        }
    }
}
