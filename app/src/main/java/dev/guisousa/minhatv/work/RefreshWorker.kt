package dev.guisousa.minhatv.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.guisousa.minhatv.data.Repository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/**
 * Atualiza as playlists em segundo plano, mesmo com o app fechado.
 * Roda como serviço em primeiro plano (notificação) porque listas grandes levam mais
 * de 10 minutos, o limite de um trabalho comum do Android.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        Repository.init(applicationContext)
        Repository.awaitLoaded()
        // Se o Android não deixar virar serviço, segue assim mesmo:
        // o que não terminar continua na próxima rodada.
        runCatching { setForeground(foregroundInfo("Atualizando listas…")) }
        val progress = launch {
            Repository.status.collectLatest { s ->
                if (s != null) runCatching { setForeground(foregroundInfo(s)) }
            }
        }
        try {
            Repository.refreshAll(onlyStale = true)
        } finally {
            progress.cancel()
        }
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Atualizando listas…")

    private fun foregroundInfo(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Atualização das listas", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Minha TV")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "refresh"
        private const val NOTIFICATION_ID = 42

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
