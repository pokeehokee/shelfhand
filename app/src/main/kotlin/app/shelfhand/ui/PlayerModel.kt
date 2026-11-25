package app.shelfhand.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.shelfhand.CrashLog
import app.shelfhand.core.BookIdentity
import app.shelfhand.core.Listening
import app.shelfhand.core.ShelfBook
import app.shelfhand.core.Timeline
import app.shelfhand.core.UpNext
import app.shelfhand.data.BookRow
import app.shelfhand.data.ChapterEntity
import app.shelfhand.data.Library
import app.shelfhand.data.VerdictEntity
import app.shelfhand.playback.PlaybackService
import app.shelfhand.shelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val bookId: Long,
    val title: String,
    val author: String?,
    val chapters: List<ChapterEntity>,
    val chapterIdx: Int = 0,
    val chapterPosMs: Long = 0,
    val chapterDurMs: Long = 0,
    val bookPosMs: Long = 0,
    val bookTotalMs: Long = 0,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val sleepEndsAt: Long = -1,
    val sleepEndOfChapter: Boolean = false,
    val ended: Boolean = false,
)

class PlayerModel(app: Application) : AndroidViewModel(app) {
    private val dao = app.shelf.db.dao()
    val library: Library = app.shelf.library

    val books: StateFlow<List<BookRow>> = dao.books().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val now = MutableStateFlow<NowPlaying?>(null)
    val upNext = MutableStateFlow<List<BookRow>>(emptyList())
    val scanMessage = MutableStateFlow<String?>(null)

    private var controller: MediaController? = null

    /** A failure in the player shows as a line and goes in the crash reports, rather than closing the app. */
    private val guard = CoroutineExceptionHandler { _, e ->
        CrashLog.record(app, "Player", e)
        scanMessage.value = "The player hit a problem: ${e.message ?: e.javaClass.simpleName}"
    }

    init {
        if (library.rootUri != null && !library.hasAccess())
            scanMessage.value = "Shelfhand lost access to your audiobook folder. Choose it again."
        viewModelScope.launch(guard) {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val c = MediaController.Builder(app, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) offerUpNext()
                }
            })
            // Reopen where you left off, paused.
            if (c.mediaItemCount == 0) dao.lastPlayed()?.let { open(it.bookId, play = false) } else adopt(c)
            while (isActive) { refresh(); delay(500) }
        }
    }

    private suspend fun adopt(c: MediaController) {
        val id = PlaybackService.bookIdOf(c.currentMediaItem) ?: return
        val b = dao.book(id) ?: return
        now.value = NowPlaying(id, b.title, b.author, dao.chapters(id))
        refresh()
    }

    private var scanJob: Job? = null

    /** Folder walk first, so books appear in seconds; then each file's length, which is the slow part. */
    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch(guard) {
            scanMessage.value = "Reading folders..."
            val r = try {
                library.scan { scanMessage.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                scanMessage.value = e.message ?: "Shelfhand lost access to that folder. Choose it again."; return@launch
            } catch (e: Exception) {
                scanMessage.value = "The scan stopped: ${e.message ?: e.javaClass.simpleName}"; return@launch
            }
            if (r.books == 0) {
                scanMessage.value = "No audiobooks found in that folder. Choose the folder that holds your book folders, or the books themselves."
                return@launch
            }
            val found = "${r.books} books, ${r.added} new"
            scanMessage.value = found
            val unreadable = try {
                library.readLengths { done, total -> scanMessage.value = "$found. Reading lengths: $done of $total files" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanMessage.value = "$found. Stopped reading lengths: ${e.message ?: e.javaClass.simpleName}"; return@launch
            }
            scanMessage.value = if (unreadable > 0) "$found. $unreadable files have no readable length." else found
        }
    }

    fun open(bookId: Long, play: Boolean = true) = viewModelScope.launch(guard) {
        val c = controller ?: return@launch
        val b = dao.book(bookId) ?: return@launch
        val chapters = dao.chapters(bookId)
        if (chapters.isEmpty()) return@launch
        val pos = dao.position(bookId)
        val items = chapters.map { ch ->
            MediaItem.Builder()
                .setMediaId(PlaybackService.mediaId(bookId, ch.idx))
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(ch.uri.toUri()).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(b.title)
                        .setArtist(b.author)
                        .setAlbumTitle(b.series ?: b.title)
                        .setSubtitle(ch.name)
                        .setArtworkUri(b.coverUri?.toUri())
                        .build()
                ).build()
        }
        c.setMediaItems(items, pos?.chapterIdx ?: 0, pos?.offsetMs ?: 0L)
        c.setPlaybackSpeed(pos?.speed ?: 1f)
        c.prepare()
        if (play) c.play()
        now.value = NowPlaying(bookId, b.title, b.author, chapters)
        upNext.value = emptyList()
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun back30() { controller?.seekBack() }
    fun fwd30() { controller?.seekForward() }
    fun seekInChapter(ms: Long) { controller?.seekTo(ms) }
    fun jumpTo(idx: Int) { controller?.seekTo(idx, 0) }
    fun speed(s: Float) { controller?.setPlaybackSpeed(Listening.clampSpeed(s)) }

    /** minutes > 0 countdown, 0 off, -1 end of chapter, -2 add five minutes. */
    fun sleep(minutes: Int) = viewModelScope.launch(guard) {
        val c = controller ?: return@launch
        val r = c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY),
            Bundle().apply { putInt(PlaybackService.ARG_MINUTES, minutes) },
        ).await()
        now.value = now.value?.copy(
            sleepEndsAt = r.extras.getLong(PlaybackService.ARG_ENDS_AT, -1),
            sleepEndOfChapter = r.extras.getBoolean(PlaybackService.ARG_END_OF_CHAPTER, false),
        )
    }

    fun markFinished(bookId: Long, finished: Boolean) = viewModelScope.launch(guard) {
        if (finished) dao.upsertVerdict(VerdictEntity(bookId, "FINISHED", inferred = false, at = System.currentTimeMillis()))
        else dao.clearVerdict(bookId)
    }

    private fun refresh() {
        val c = controller ?: return
        val n = now.value ?: return
        val idx = c.currentMediaItemIndex
        val durs = n.chapters.map { it.durationMs ?: 0L }
        val t = Timeline(durs)
        now.value = n.copy(
            chapterIdx = idx,
            chapterPosMs = c.currentPosition,
            chapterDurMs = c.duration.takeIf { it > 0 } ?: durs.getOrElse(idx) { 0L },
            bookPosMs = t.toGlobal(idx, c.currentPosition),
            bookTotalMs = t.totalMs,
            playing = c.isPlaying,
            speed = c.playbackParameters.speed,
            ended = c.playbackState == Player.STATE_ENDED,
        )
    }

    private fun offerUpNext() {
        val n = now.value ?: return
        val rows = books.value
        val done = rows.firstOrNull { it.id == n.bookId } ?: return
        val shelf = rows.map {
            ShelfBook(it.id, BookIdentity(it.author, it.series, it.seriesIndex, it.title),
                finished = it.verdict == "FINISHED" || it.id == n.bookId, positionMs = it.globalMs ?: 0, lastPlayedAt = it.updatedAt)
        }
        val picks = UpNext.after(shelf.first { it.id == done.id }, shelf).map { it.id }
        upNext.value = picks.mapNotNull { id -> rows.firstOrNull { it.id == id } }
    }

    fun dismissUpNext() { upNext.value = emptyList() }

    override fun onCleared() { controller?.release(); super.onCleared() }
}
