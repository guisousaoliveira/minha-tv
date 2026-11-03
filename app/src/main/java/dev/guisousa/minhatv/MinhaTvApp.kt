package dev.guisousa.minhatv

import android.app.Application
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.work.RefreshWorker

class MinhaTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repository.init(this)
        // WorkManager demora para iniciar: agenda fora da thread principal
        Thread { runCatching { RefreshWorker.schedule(this, Repository.refreshHours) } }.start()
    }
}
