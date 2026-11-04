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

    /**
     * TV sem fuso configurado fica em GMT/UTC e o guia mostra a hora errada (3 h adiantada).
     * Nesse caso o app usa o horário de Brasília.
     */
    private fun useBrazilTimeIfUnset() {
        val id = java.util.TimeZone.getDefault().id
        if (id in setOf("GMT", "UTC", "Etc/UTC", "Etc/GMT", "Etc/GMT0", "Etc/Universal", "Etc/Zulu", "Universal", "Zulu", "GMT0")) {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"))
        }
    }

    override fun onCreate() {
        super.onCreate()
        useBrazilTimeIfUnset()
        Repository.init(this)
        // WorkManager demora para iniciar: agenda fora da thread principal
        Thread { runCatching { RefreshWorker.schedule(this, Repository.refreshHours) } }.start()
    }
}
