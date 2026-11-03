package dev.guisousa.minhatv

import android.app.Application
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.work.RefreshWorker

class MinhaTvApp : Application(), coil.ImageLoaderFactory {
    // capas e logos aparecem com um fade suave em vez de "pipocar" na tela
    override fun newImageLoader() = coil.ImageLoader.Builder(this)
        .crossfade(180)
        .respectCacheHeaders(false)
        .build()

    override fun onCreate() {
        super.onCreate()
        Repository.init(this)
        // WorkManager demora para iniciar: agenda fora da thread principal
        Thread { runCatching { RefreshWorker.schedule(this, Repository.refreshHours) } }.start()
    }
}
