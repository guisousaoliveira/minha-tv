package dev.guisousa.badwolf.mobile.player

import dev.guisousa.minhatv.core.DohDns
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Conexão do player mobile, usando DohDns do :core (com circuit breaker de 5 min se 1.1.1.1 falhar). */
object PlayerHttp {
    const val SAFARI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .dns(DohDns)
        .build()
}
