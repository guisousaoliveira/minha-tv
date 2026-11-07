package dev.guisousa.minhatv

import android.app.Application
import dev.guisousa.minhatv.data.Repository

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
        dev.guisousa.minhatv.data.Conta.init(this)
        // Não há mais atualização periódica em segundo plano (o app confere se as listas mudaram ao abrir):
        // cancela a que versões antigas deixaram agendada. WorkManager demora para iniciar: fora da thread principal.
        Thread { runCatching { androidx.work.WorkManager.getInstance(this).cancelUniqueWork("refresh-playlists") } }.start()
    }
}
