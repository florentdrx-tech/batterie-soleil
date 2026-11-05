package be.florent.soleil

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        return try {
            Store.saveDays(ctx, Forecast.fetch(Store.load(ctx)))
            Store.autoCalibrate(ctx)
            SolarWidget.refreshAll(ctx)
            Result.success()
        } catch (e: Exception) {
            SolarWidget.refreshAll(ctx)
            Result.retry()
        }
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Mise à jour automatique toutes les heures. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.HOURS)
                .setConstraints(net).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("solar_periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun now(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(net).build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("solar_now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
