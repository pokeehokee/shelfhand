package app.shelfhand

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a short record of every time Shelfhand closed on its own, so a crash explains itself on the next
 * launch instead of needing a bug report from Developer options.
 *
 * Two sources: the app's own handler, which catches a Kotlin or Java crash with its full stack trace, and
 * Android's exit records (Android 11 and later), which also cover what no handler can see: the app freezing
 * until Android closes it ("isn't responding"), a crash in native code such as the bundled SQLite, and being
 * killed for memory while on screen. Everything stays on the phone; nothing is sent anywhere.
 */
object CrashLog {
    data class Report(val at: Long, val kind: String, val text: String, val file: File) {
        val title: String get() = when (kind) {
            "crash" -> "Shelfhand crashed"
            "error" -> "Shelfhand hit an error and kept going"
            "anr" -> "Shelfhand froze and Android closed it"
            "native" -> "Shelfhand crashed in native code"
            "memory" -> "Android closed Shelfhand for memory"
            else -> "Shelfhand closed unexpectedly"
        }
    }

    private const val KEEP = 15
    private const val PREFS = "crashlog"

    private fun dir(ctx: Context) = File(ctx.filesDir, "crashes").apply { mkdirs() }

    /** Called once at startup: records any uncaught exception, then lets Android close the app as usual. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, System.currentTimeMillis(), "crash", "Thread: ${thread.name}\n\n" + trace(e)) }
            previous?.uncaughtException(thread, e) ?: run { Process.killProcess(Process.myPid()); System.exit(10) }
        }
    }

    /** An error that was caught and shown, kept so it can still be looked at later. */
    fun record(ctx: Context, where: String, e: Throwable) {
        runCatching { write(ctx.applicationContext, System.currentTimeMillis(), "error", "Where: $where\n\n" + trace(e)) }
    }

    /** Every report, newest first, after copying in any new exit records from Android. */
    fun reports(ctx: Context): List<Report> {
        runCatching { importExitRecords(ctx) }
        return dir(ctx).listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
            .mapNotNull { f ->
                val parts = f.nameWithoutExtension.split('-')
                val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                Report(at, parts.getOrNull(1) ?: "crash", runCatching { f.readText() }.getOrDefault(""), f)
            }
            .sortedByDescending { it.at }
    }

    /** The newest report of the app closing (not a caught error) that the user hasn't dismissed yet. */
    fun unseen(ctx: Context): Report? {
        val seen = prefs(ctx).getLong("seen", 0)
        return reports(ctx).firstOrNull { it.kind != "error" && it.at > seen }
    }

    fun markSeen(ctx: Context) = prefs(ctx).edit().putLong("seen", System.currentTimeMillis()).apply()

    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        markSeen(ctx)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun trace(e: Throwable) = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    private fun write(ctx: Context, at: Long, kind: String, body: String) {
        val head = buildString {
            append("Shelfhand ").append(version(ctx)).append(" on Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append("), ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(at))).append('\n')
        }
        File(dir(ctx), "$at-$kind.txt").writeText(head + body)
        dir(ctx).listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    private fun version(ctx: Context) = runCatching {
        @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** Copies Android's records of how the app last ended, for the endings a user would call a force close. */
    private fun importExitRecords(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val p = prefs(ctx)
        val after = p.getLong("exitsUpTo", 0)
        val own = dir(ctx).listFiles().orEmpty().mapNotNull { it.name.substringBefore('-').toLongOrNull() }
        var newest = after
        for (x in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)) {
            if (x.timestamp <= after) continue
            newest = maxOf(newest, x.timestamp)
            val onScreen = x.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            val kind = when (x.reason) {
                ApplicationExitInfo.REASON_ANR -> "anr"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "crash"
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> if (onScreen) "memory" else null
                ApplicationExitInfo.REASON_SIGNALED -> if (onScreen) "other" else null
                else -> null
            } ?: continue
            // The app's own handler already wrote this crash, with a better trace than Android's record.
            if (kind == "crash" && own.any { kotlin.math.abs(it - x.timestamp) < 15_000 }) continue
            val body = buildString {
                append("From Android's exit record\n")
                append("Reason: ").append(reasonName(x.reason)).append(" (status ").append(x.status).append(")\n")
                x.description?.let { append("Description: ").append(it).append('\n') }
                append("On screen: ").append(if (onScreen) "yes" else "no").append('\n')
                append("Memory: ").append(x.pss / 1024).append(" MB in use, ").append(x.rss / 1024).append(" MB resident\n")
                // Android keeps the thread dump of a freeze; it shows what the app was stuck on.
                if (x.reason == ApplicationExitInfo.REASON_ANR) runCatching {
                    x.traceInputStream?.bufferedReader()?.use { r -> append('\n').append(r.readText().take(60_000)) }
                }
            }
            write(ctx, x.timestamp, kind, body)
        }
        if (newest > after) p.edit().putLong("exitsUpTo", newest).apply()
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        else -> "code $r"
    }
}
