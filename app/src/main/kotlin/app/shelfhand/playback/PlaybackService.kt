package app.shelfhand.playback

import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.shelfhand.core.Listening
import app.shelfhand.core.SleepTimer
import app.shelfhand.core.Timeline
import app.shelfhand.data.PositionEntity
import app.shelfhand.data.VerdictEntity
import app.shelfhand.shelf
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays one book at a time. Owns the position record: saved every 10 s while playing, on every
 * pause and on chapter change. Owns "finished": recorded when playback reaches the book's tail.
 */
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    private var bookId: Long? = null
    private var timeline: Timeline? = null
    private var pausedAt: Long? = null
    private var sleep: SleepTimer = SleepTimer.Off
    private var ticker: Job? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)   // headphones out -> pause
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.addListener(listener)
        session = MediaSession.Builder(this, player).setCallback(callback).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        save()
        ticker?.cancel()
        session?.run { player.release(); release() }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                pausedAt?.let { p ->
                    val back = Listening.rewindOnResume(System.currentTimeMillis() - p)
                    if (back > 0) player.seekTo((player.currentPosition - back).coerceAtLeast(0))
                }
                pausedAt = null
                startTicker()
            } else {
                pausedAt = System.currentTimeMillis()
                ticker?.cancel()
                save()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            bookIdOf(mediaItem)?.let { if (it != bookId) load(it) }
            save()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) { save(); markFinishedIfDone(force = true) }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                delay(1_000)
                applySleep()
                if (++n % 10 == 0) { save(); markFinishedIfDone(force = false) }
            }
        }
    }

    private fun applySleep() {
        val now = System.currentTimeMillis()
        when (val t = sleep) {
            is SleepTimer.Countdown -> {
                player.volume = SleepTimer.volume(t, now)
                if (SleepTimer.expired(t, now)) { player.pause(); player.volume = 1f; sleep = SleepTimer.Off }
            }
            else -> Unit
        }
    }

    private fun load(id: Long) {
        bookId = id
        scope.launch {
            val chapters = shelf.db.dao().chapters(id)
            // Unknown durations count as 0 here; the position is still saved per file, so nothing is lost.
            timeline = Timeline(chapters.map { it.durationMs ?: 0L })
        }
    }

    private fun save() {
        val id = bookId ?: return
        val idx = player.currentMediaItemIndex
        val off = player.currentPosition
        val global = timeline?.toGlobal(idx, off) ?: off
        val speed = player.playbackParameters.speed
        scope.launch(Dispatchers.IO) {
            shelf.db.dao().upsertPosition(PositionEntity(id, idx, off, global, speed, System.currentTimeMillis()))
        }
    }

    private fun markFinishedIfDone(force: Boolean) {
        val id = bookId ?: return
        val t = timeline ?: return
        val global = t.toGlobal(player.currentMediaItemIndex, player.currentPosition)
        if (force || Listening.isFinished(global, t.totalMs)) {
            scope.launch(Dispatchers.IO) {
                val dao = shelf.db.dao()
                if (dao.verdict(id) == null) dao.upsertVerdict(VerdictEntity(id, "FINISHED", inferred = true, at = System.currentTimeMillis()))
            }
        }
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(cmds).build()
        }

        /** Items sent from the UI lose their URI on the way across; it travels in requestMetadata. */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }.toMutableList()
        )

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_SLEEP) {
                val minutes = args.getInt(ARG_MINUTES, 0)
                val now = System.currentTimeMillis()
                sleep = when {
                    minutes == SLEEP_END_OF_CHAPTER -> SleepTimer.EndOfChapter
                    minutes == SLEEP_EXTEND -> SleepTimer.extend(sleep, now)
                    minutes > 0 -> SleepTimer.Countdown(now + minutes * 60_000L)
                    else -> SleepTimer.Off
                }
                player.pauseAtEndOfMediaItems = sleep is SleepTimer.EndOfChapter
                if (sleep is SleepTimer.Off) player.volume = 1f
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                    putLong(ARG_ENDS_AT, (sleep as? SleepTimer.Countdown)?.endsAtMs ?: -1L)
                    putBoolean(ARG_END_OF_CHAPTER, sleep is SleepTimer.EndOfChapter)
                }))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    companion object {
        const val CMD_SLEEP = "shelfhand.SLEEP"
        const val ARG_MINUTES = "minutes"
        const val ARG_ENDS_AT = "endsAt"
        const val ARG_END_OF_CHAPTER = "endOfChapter"
        const val SLEEP_END_OF_CHAPTER = -1
        const val SLEEP_EXTEND = -2

        fun mediaId(bookId: Long, idx: Int) = "book:$bookId:$idx"
        fun bookIdOf(item: MediaItem?): Long? = item?.mediaId?.split(':')?.getOrNull(1)?.toLongOrNull()
    }
}
