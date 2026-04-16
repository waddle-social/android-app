package social.waddle.android.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CatchUpScheduler {
    fun enqueue(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<WaddleCatchUpWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).build()
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                WaddleCatchUpWorker.UNIQUE_NAME,
                // UPDATE preserves the existing schedule if already enqueued but
                // picks up any change to the worker's constraints/interval.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun cancel(context: Context) {
        WorkManager
            .getInstance(context)
            .cancelUniqueWork(WaddleCatchUpWorker.UNIQUE_NAME)
    }

    // 15 min is the OS-enforced floor for periodic work.
    private const val INTERVAL_MINUTES = 15L
}
