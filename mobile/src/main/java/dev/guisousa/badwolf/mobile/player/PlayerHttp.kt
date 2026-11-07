// Copiado de app/src/main/java/dev/guisousa/minhatv/player/PlayerHttp.kt (vai para o módulo core depois)
package dev.guisousa.badwolf.mobile.player

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Conexão do player, com os mesmos ajustes do Saimo TV. */
object PlayerHttp {
    const val SAFARI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** DNS pela Cloudflare (1.1.1.1); se não responder, usa o DNS normal da rede. */
    private val doh: Dns = run {
        val cf = DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .build()
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                try { cf.lookup(hostname).ifEmpty { Dns.SYSTEM.lookup(hostname) } } catch (e: Exception) { Dns.SYSTEM.lookup(hostname) }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .dns(doh)
        .build()
}
