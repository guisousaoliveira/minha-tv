package dev.guisousa.badwolf.mobile

import android.app.Application
import dev.guisousa.badwolf.mobile.data.Repository

class BadWolfApp : Application(), coil.ImageLoaderFactory {
    // capas e logos aparecem com um fade suave em vez de "pipocar" na tela
    override fun newImageLoader() = coil.ImageLoader.Builder(this)
        .crossfade(180)
        .respectCacheHeaders(false)
        .build()

    /** Aparelho sem fuso configurado fica em UTC e o guia mostra a hora errada: usa o horário de Brasília. */
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
    }
}
