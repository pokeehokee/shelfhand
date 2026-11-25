package app.shelfhand.catalog

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.shelfhand.core.catalog.Lookups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Fills in details Open Library has and the source left out: first published year, series, subjects.
 * Nightly, on Wi-Fi while charging, about one request a second, a few thousand books a night.
 */
class LookupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = CatalogHolder.get(applicationContext)
        val r = Lookups.run(db, fetch = { Http.get(it, accept = "application/json") }, pause = { Thread.sleep(1_100) },
            max = PER_RUN, stop = { isStopped })
        if (r.stoppedEarly && !isStopped) Result.retry() else Result.success()
    }

    companion object {
        private const val PER_RUN = 3_000

        fun schedule(ctx: Context) {
            val c = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("catalog-lookup", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LookupWorker>(1, TimeUnit.DAYS).setConstraints(c).build())
        }

        /** Runs a pass now on any connection, for when the user asks. */
        fun runNow(ctx: Context) {
            val c = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("catalog-lookup-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LookupWorker>().setConstraints(c).build())
        }
    }
}
