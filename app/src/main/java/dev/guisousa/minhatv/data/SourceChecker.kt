package dev.guisousa.minhatv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Testa se um link de canal/filme responde, sem tocar o vídeo: pede só o começo do arquivo.
 * Para HLS (.m3u8) confere se veio mesmo uma playlist e, se for a lista "mestre",
 * testa também a primeira qualidade dela (é comum a mestre abrir e o vídeo estar fora do ar).
 */
class SourceChecker(base: OkHttpClient) {

    sealed class Result {
        data class Ok(val ms: Long) : Result()
        object Dead : Result()
        /** Não deu para saber (ex.: sem internet na TV). Não guarda nada. */
        object Unknown : Result()
    }

    private val http = base.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Um teste por vez em cada servidor: muitos servidores de IPTV aceitam só uma ou duas conexões
     * por conta, e testes em paralelo no mesmo servidor dariam "morto" sem estar.
     */
    private val hostLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun check(url: String, userAgent: String?, referrer: String?): Result {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return Result.Dead
        val lock = hostLocks.getOrPut(host) { Any() }
        return synchronized(lock) { checkNow(url, userAgent, referrer) }
    }

    private fun checkNow(url: String, userAgent: String?, referrer: String?): Result {
        val start = System.nanoTime()
        return try {
            val first = fetch(url, userAgent, referrer) ?: return Result.Dead
            if (first.isPlaylist) {
                val variant = M3uLite.firstVariant(first.text, first.finalUrl)
                if (variant != null) {
                    val v = fetch(variant, userAgent, referrer) ?: return Result.Dead
                    if (!v.isPlaylist && !v.isMedia) return Result.Dead
                }
            } else if (!first.isMedia) {
                return Result.Dead
            }
            Result.Ok((System.nanoTime() - start) / 1_000_000)
        } catch (e: java.net.UnknownHostException) {
            Result.Dead
        } catch (e: IOException) {
            // tempo esgotado, conexão recusada: conta como falha (precisa falhar 2 vezes para sumir)
            Result.Dead
        } catch (e: IllegalArgumentException) {
            Result.Dead // link mal formado
        }
    }

    private class Page(val text: String, val finalUrl: String, val isPlaylist: Boolean, val isMedia: Boolean)

    /** null quando o servidor respondeu com erro. */
    private fun fetch(url: String, userAgent: String?, referrer: String?): Page? {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent ?: Repository.DEFAULT_UA)
            .header("Range", "bytes=0-8191")
            .apply { referrer?.let { header("Referer", it) } }
            .build()
        http.newCall(req).execute().use { resp ->
            // 416 = o servidor está vivo, só não aceitou o pedaço pedido
            if (resp.code == 416) return Page("", url, isPlaylist = false, isMedia = true)
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val bytes = body.source().let { src ->
                src.request(8192)
                src.buffer.readByteArray(minOf(src.buffer.size, 8192))
            }
            val type = resp.header("Content-Type").orEmpty().lowercase()
            val text = String(bytes, Charsets.UTF_8)
            val isPlaylist = text.trimStart('﻿', ' ', '\r', '\n').startsWith("#EXTM3U")
            val looksHtml = type.contains("text/html") || text.trimStart().startsWith("<")
            return Page(text, resp.request.url.toString(), isPlaylist, isMedia = !isPlaylist && !looksHtml && bytes.isNotEmpty())
        }
    }
}

/** O mínimo de HLS que o teste precisa. */
object M3uLite {
    /** Primeira qualidade de uma lista HLS "mestre" (ou null se a lista já for de segmentos). */
    fun firstVariant(text: String, baseUrl: String): String? {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (i < 0) return null
        val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        return runCatching { java.net.URI(baseUrl).resolve(uri).toString() }.getOrNull()
    }
}
