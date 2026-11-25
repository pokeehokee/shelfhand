package app.shelfhand.core

/** One playable file, as the library scanner sees it. Duration is read from the file, never assumed. */
data class AudioFile(val uri: String, val name: String, val durationMs: Long?)

/** What the folder path says about a book. Every field is nullable: an unknown is not an empty string. */
data class BookIdentity(
    val author: String?,
    val series: String?,
    val seriesIndex: Double?,
    val title: String,
)

data class ScannedBook(
    val folderUri: String,
    val identity: BookIdentity,
    /** Files in play order. */
    val files: List<AudioFile>,
    val coverUri: String?,
) {
    /** Null when any file's duration is unknown, so a total is never silently short. */
    val totalMs: Long? get() = if (files.any { it.durationMs == null }) null else files.sumOf { it.durationMs!! }
}
