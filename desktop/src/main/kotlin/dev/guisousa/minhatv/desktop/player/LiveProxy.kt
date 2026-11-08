package dev.guisousa.minhatv.desktop.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.guisousa.minhatv.data.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ao vivo em HLS: o servidor redireciona o link do canal (.m3u8) para um endereço com senha
 * temporária. O VLC guardava esse endereço e pedia a lista de pedaços de novo nele: dava erro
 * depois de alguns segundos e o canal parava (era o mesmo problema que a TV tinha).
 *
 * Aqui o VLC pede a lista a um servidorzinho local, que a cada atualização volta no link ORIGINAL
 * do canal, recebe um endereço novo e válido e devolve a lista com os pedaços em endereço completo.
 * Os pedaços (.ts) o VLC baixa direto do servidor.
 */
object LiveProxy {
    private class Canal(val url: String, val ua: String, val referrer: String?)

    private val canais = ConcurrentHashMap<Int, Canal>()
    private val proximo = AtomicInteger(1)

    private val http = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val server: HttpServer? by lazy {
        runCatching {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16).apply {
                executor = Executors.newCachedThreadPool { r -> Thread(r, "live-proxy").apply { isDaemon = true } }
                createContext("/c/") { ex -> runCatching { atender(ex) }.onFailure { Log.w("proxy ao vivo", it); runCatching { ex.sendResponseHeaders(502, -1); ex.close() } } }
                start()
                Log.i("proxy do ao vivo em 127.0.0.1:${address.port}")
            }
        }.onFailure { Log.w("não consegui abrir o proxy do ao vivo", it) }.getOrNull()
    }

    /** Endereço local para o VLC tocar este canal HLS (ou o próprio link, se o proxy não abriu). */
    fun endereco(url: String, ua: String, referrer: String?): String {
        val s = server ?: return url
        val id = proximo.getAndIncrement()
        canais[id] = Canal(url, ua, referrer)
        // guarda só os últimos canais abertos
        canais.keys.filter { it < id - 20 }.forEach { canais.remove(it) }
        return "http://127.0.0.1:${s.address.port}/c/$id/lista.m3u8"
    }

    private fun atender(ex: HttpExchange) {
        // /c/<id>/lista.m3u8  ou  /c/<id>/sub.m3u8?u=<link>
        val partes = ex.requestURI.path.trim('/').split('/')
        val canal = partes.getOrNull(1)?.toIntOrNull()?.let { canais[it] }
        if (canal == null) { ex.sendResponseHeaders(404, -1); ex.close(); return }
        val alvo = if (partes.getOrNull(2) == "sub.m3u8") {
            ex.requestURI.rawQuery?.split('&')?.firstOrNull { it.startsWith("u=") }?.let { URLDecoder.decode(it.substring(2), "UTF-8") }
        } else canal.url
        if (alvo == null) { ex.sendResponseHeaders(400, -1); ex.close(); return }

        val req = Request.Builder().url(alvo)
            .header("User-Agent", canal.ua).header("Accept", "*/*")
            .apply { canal.referrer?.let { header("Referer", it) } }
            .build()
        http.newCall(req).execute().use { resp ->
            val corpo = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                Log.w("proxy ao vivo: HTTP ${resp.code} em $alvo")
                ex.sendResponseHeaders(resp.code, -1); ex.close(); return
            }
            val texto = String(corpo, Charsets.UTF_8)
            val saida = if (texto.trimStart().startsWith("#EXTM3U")) {
                reescrever(texto, resp.request.url.toString(), ex.requestURI.path.split('/').take(3).joinToString("/"))
                    .toByteArray(Charsets.UTF_8)
            } else corpo
            ex.responseHeaders.add("Content-Type", resp.header("Content-Type") ?: "application/vnd.apple.mpegurl")
            ex.responseHeaders.add("Cache-Control", "no-cache")
            ex.sendResponseHeaders(200, saida.size.toLong())
            ex.responseBody.use { it.write(saida) }
        }
    }

    private val uriAttr = Regex("""URI="([^"]+)"""")

    /** Deixa todo endereço da lista completo; sub-listas (.m3u8) também passam pelo proxy. */
    internal fun reescrever(lista: String, base: String, prefixo: String, porta: Int = server?.address?.port ?: 0): String {
        val baseUrl = base.toHttpUrlOrNull() ?: return lista
        fun absoluto(u: String) = baseUrl.resolve(u.trim())?.toString() ?: u
        fun mapear(u: String): String {
            val abs = absoluto(u)
            val caminho = abs.toHttpUrlOrNull()?.encodedPath ?: abs
            return if (caminho.endsWith(".m3u8") || caminho.endsWith(".m3u")) {
                "http://127.0.0.1:$porta$prefixo/sub.m3u8?u=" + URLEncoder.encode(abs, "UTF-8")
            } else abs
        }
        return lista.lineSequence().joinToString("\n") { linha ->
            val l = linha.trim()
            when {
                l.isEmpty() -> linha
                l.startsWith("#") -> uriAttr.replace(linha) { m -> "URI=\"" + mapear(m.groupValues[1]) + "\"" }
                else -> mapear(l)
            }
        }
    }
}
