package app.shelfhand.core

/** Sleep timer as plain state, driven by a clock the caller passes in. */
sealed interface SleepTimer {
    data object Off : SleepTimer
    data class Countdown(val endsAtMs: Long) : SleepTimer
    /** Stop when the current chapter (file) ends. */
    data object EndOfChapter : SleepTimer

    companion object {
        const val FADE_MS = 10_000L

        fun remaining(t: SleepTimer, nowMs: Long): Long? = (t as? Countdown)?.let { (it.endsAtMs - nowMs).coerceAtLeast(0) }

        /** Volume 1.0 until the last [FADE_MS], then a linear fade to 0. */
        fun volume(t: SleepTimer, nowMs: Long): Float {
            val left = remaining(t, nowMs) ?: return 1f
            return if (left >= FADE_MS) 1f else left.toFloat() / FADE_MS
        }

        fun expired(t: SleepTimer, nowMs: Long) = remaining(t, nowMs) == 0L

        /** Shaking the phone adds time; it never shortens a running timer. */
        fun extend(t: SleepTimer, nowMs: Long, byMs: Long = 5 * 60_000L): SleepTimer = when (t) {
            is Countdown -> Countdown(maxOf(t.endsAtMs, nowMs) + byMs)
            else -> t
        }
    }
}
