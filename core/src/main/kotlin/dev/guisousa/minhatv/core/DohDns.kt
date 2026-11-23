package dev.guisousa.minhatv.core

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * DNS via Cloudflare DoH (1.1.1.1) com circuit breaker de 5 minutos:
 * se a rede bloquear ou deixar o 1.1.1.1 lento, cai imediatamente para Dns.SYSTEM
 * sem travar 10-20s a cada nova resolução DNS.
 */
object DohDns : Dns {
    private const val COOLDOWN_MS = 5 * 60_000L
    private val disabledUntil = AtomicLong(0L)

    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private val cloudflare = DnsOverHttps.Builder()
        .client(bootstrap)
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1"),
        )
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        // IPs literais ou loopback nunca precisam de DoH
        if (hostname == "localhost" || hostname == "127.0.0.1" || hostname.all { it.isDigit() || it == '.' }) {
            return Dns.SYSTEM.lookup(hostname)
        }
        val now = System.currentTimeMillis()
        if (now < disabledUntil.get()) {
            return Dns.SYSTEM.lookup(hostname)
        }
        return runCatching { cloudflare.lookup(hostname) }.getOrElse {
            disabledUntil.set(now + COOLDOWN_MS)
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /** Só para testes / diagnóstico. */
    fun resetCircuitBreaker() = disabledUntil.set(0L)
}
