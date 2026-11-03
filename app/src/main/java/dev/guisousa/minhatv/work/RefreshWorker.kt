package dev.guisousa.minhatv.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.guisousa.minhatv.data.Repository
import java.util.concurrent.TimeUnit

/** Atualiza as playlists em segundo plano, mesmo com o app fechado. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Repository.init(applicationContext)
        Repository.awaitLoaded()
        Repository.refreshAll()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, hours: Int) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("refresh-playlists", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
