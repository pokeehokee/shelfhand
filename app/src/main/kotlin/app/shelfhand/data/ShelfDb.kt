package app.shelfhand.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index(value = ["folderUri"], unique = true)])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderUri: String,
    val title: String,
    val author: String?,
    val series: String?,
    val seriesIndex: Double?,
    val coverUri: String?,
    /** Null when a file's duration could not be read; the UI says "unknown", never 0. */
    val totalMs: Long?,
    val addedAt: Long,
    /** False when the folder was missing on the last scan. Kept so its history survives a move. */
    val present: Boolean = true,
)

/** One audio file, in play order. A single m4b is one chapter until embedded chapters are read. */
@Entity(primaryKeys = ["bookId", "idx"])
data class ChapterEntity(val bookId: Long, val idx: Int, val uri: String, val name: String, val durationMs: Long?)

@Entity
data class PositionEntity(
    @PrimaryKey val bookId: Long,
    val chapterIdx: Int,
    val offsetMs: Long,
    val globalMs: Long,
    val speed: Float,
    val updatedAt: Long,
)

/** Finished or abandoned, recorded by the player or set by you. Feeds the profile later. */
@Entity
data class VerdictEntity(
    @PrimaryKey val bookId: Long,
    val state: String,      // FINISHED | ABANDONED
    val inferred: Boolean,
    val at: Long,
)

data class BookRow(
    val id: Long, val title: String, val author: String?, val series: String?, val seriesIndex: Double?,
    val coverUri: String?, val totalMs: Long?, val globalMs: Long?, val updatedAt: Long?, val verdict: String?,
)

@Dao
interface ShelfDao {
    @Query("""SELECT b.id, b.title, b.author, b.series, b.seriesIndex, b.coverUri, b.totalMs,
              p.globalMs, p.updatedAt, v.state AS verdict
              FROM BookEntity b LEFT JOIN PositionEntity p ON p.bookId = b.id LEFT JOIN VerdictEntity v ON v.bookId = b.id
              WHERE b.present = 1
              ORDER BY COALESCE(p.updatedAt, 0) DESC, b.author, b.series, b.seriesIndex, b.title""")
    fun books(): Flow<List<BookRow>>

    @Query("SELECT * FROM BookEntity WHERE id = :id") suspend fun book(id: Long): BookEntity?
    @Query("SELECT * FROM BookEntity WHERE folderUri = :uri") suspend fun bookByFolder(uri: String): BookEntity?
    @Query("SELECT * FROM ChapterEntity WHERE bookId = :id ORDER BY idx") suspend fun chapters(id: Long): List<ChapterEntity>
    @Query("SELECT * FROM PositionEntity WHERE bookId = :id") suspend fun position(id: Long): PositionEntity?
    @Query("SELECT * FROM PositionEntity ORDER BY updatedAt DESC LIMIT 1") suspend fun lastPlayed(): PositionEntity?
    @Query("SELECT * FROM VerdictEntity WHERE bookId = :id") suspend fun verdict(id: Long): VerdictEntity?

    @Upsert suspend fun upsertBook(b: BookEntity): Long
    @Upsert suspend fun upsertPosition(p: PositionEntity)
    @Upsert suspend fun upsertVerdict(v: VerdictEntity)
    @Query("DELETE FROM VerdictEntity WHERE bookId = :id") suspend fun clearVerdict(id: Long)
    @Query("DELETE FROM ChapterEntity WHERE bookId = :id") suspend fun clearChapters(id: Long)
    @Upsert suspend fun upsertChapters(c: List<ChapterEntity>)
    @Query("UPDATE BookEntity SET present = 0 WHERE folderUri NOT IN (:seen)") suspend fun markMissing(seen: List<String>)

    @Transaction
    suspend fun replaceChapters(bookId: Long, c: List<ChapterEntity>) { clearChapters(bookId); upsertChapters(c) }
}

@Database(entities = [BookEntity::class, ChapterEntity::class, PositionEntity::class, VerdictEntity::class], version = 1, exportSchema = true)
abstract class ShelfDb : RoomDatabase() {
    abstract fun dao(): ShelfDao
    companion object {
        fun open(ctx: Context) = Room.databaseBuilder(ctx, ShelfDb::class.java, "shelf.db").build()
    }
}
