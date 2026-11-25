package app.shelfhand.core

/** A place in a book as one number, and back again. Chapters are files in play order. */
class Timeline(private val durations: List<Long>) {
    init { require(durations.all { it >= 0 }) }
    val totalMs: Long = durations.sum()

    fun toGlobal(index: Int, offsetMs: Long): Long = durations.take(index).sum() + offsetMs.coerceIn(0, durations.getOrElse(index) { 0 })

    /** Index and offset for a global position; past the end clamps to the end of the last file. */
    fun fromGlobal(globalMs: Long): Pair<Int, Long> {
        var left = globalMs.coerceIn(0, totalMs)
        durations.forEachIndexed { i, d ->
            if (left < d || i == durations.lastIndex) return i to left.coerceAtMost(d)
            left -= d
        }
        return 0 to 0
    }
}

object Listening {
    /** Finished when inside the last 2% (credits and outros), or the last 60 s, whichever is larger. */
    fun isFinished(positionMs: Long, totalMs: Long): Boolean {
        if (totalMs <= 0) return false
        val tail = maxOf(totalMs / 50, 60_000L)
        return positionMs >= totalMs - tail
    }

    /** Abandoned: under half way and untouched for 45 days. */
    fun isAbandoned(positionMs: Long, totalMs: Long, idleMs: Long): Boolean =
        totalMs > 0 && positionMs < totalMs / 2 && idleMs >= 45L * 24 * 3600 * 1000

    /**
     * How far to step back when playback resumes, so you hear the end of the last sentence again.
     * Grows with the length of the pause, capped at 30 s.
     */
    fun rewindOnResume(pausedMs: Long): Long = when {
        pausedMs < 10_000 -> 0
        pausedMs < 60_000 -> 2_000
        pausedMs < 10 * 60_000 -> 5_000
        pausedMs < 60 * 60_000 -> 10_000
        pausedMs < 24 * 3600_000L -> 20_000
        else -> 30_000
    }

    /** Speeds are clamped to what stays intelligible and rounded to 0.05. */
    fun clampSpeed(s: Float): Float = (Math.round(s.coerceIn(0.5f, 3.0f) * 20f) / 20f)
}
