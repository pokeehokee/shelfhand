package app.shelfhand.core

data class ShelfBook(
    val id: Long,
    val identity: BookIdentity,
    val finished: Boolean,
    val positionMs: Long,
    val lastPlayedAt: Long?,
)

/** What to offer when a book ends. */
object UpNext {
    fun after(done: ShelfBook, shelf: List<ShelfBook>): List<ShelfBook> {
        val rest = shelf.filter { it.id != done.id && !it.finished }
        val series = done.identity.series
        val idx = done.identity.seriesIndex
        val nextInSeries = if (series != null && idx != null) rest
            .filter { sameSeries(it, done) && (it.identity.seriesIndex ?: -1.0) > idx }
            .sortedBy { it.identity.seriesIndex }
            .take(1) else emptyList()
        // Then books already started, most recent first, then same author, then anything unplayed.
        val started = rest.filter { it.positionMs > 0 }.sortedByDescending { it.lastPlayedAt ?: 0 }
        val sameAuthor = rest.filter { it.identity.author != null && it.identity.author.equals(done.identity.author, true) }
        return (nextInSeries + started + sameAuthor + rest).distinctBy { it.id }.take(5)
    }

    private fun sameSeries(a: ShelfBook, b: ShelfBook) =
        a.identity.series.equals(b.identity.series, ignoreCase = true) &&
            (a.identity.author == null || b.identity.author == null || a.identity.author.equals(b.identity.author, true))
}
