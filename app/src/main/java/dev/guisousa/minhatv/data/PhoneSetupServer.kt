package dev.guisousa.minhatv.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Servidorzinho na rede local: o celular abre http://IP-DA-TV:8765 e cola o link,
 * sem precisar digitar com o controle remoto.
 */
class PhoneSetupServer(private val onAdd: (url: String, name: String?, epg: String?) -> Unit) {

    private var server: ServerSocket? = null

    val port = 8765

    fun address(): String? = localIp()?.let { "http://$it:$port" }

    fun start() {
        if (server != null) return
        val s = runCatching { ServerSocket(port) }.getOrNull() ?: return
        server = s
        thread(isDaemon = true, name = "phone-setup") {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) = socket.use { sock ->
        sock.soTimeout = 15_000
        val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        val requestLine = input.readLine() ?: return
        var length = 0
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val method = requestLine.substringBefore(' ')
        val page = if (method == "POST" && length in 1..100_000) {
            val buf = CharArray(length)
            var n = 0
            while (n < length) { val r = input.read(buf, n, length - n); if (r < 0) break; n += r }
            val form = String(buf, 0, n).split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val url = form["url"]?.trim().orEmpty()
            if (url.startsWith("http")) {
                onAdd(url, form["name"], form["epg"])
                html("<h2>Pronto</h2><p>O link foi enviado para a TV. Ela vai carregar as listas agora (pode levar um minutinho).</p><p><a href='/'>Adicionar outro link</a></p>")
            } else {
                html("<h2>Link inválido</h2><p>O link precisa começar com http:// ou https://</p><p><a href='/'>Voltar</a></p>")
            }
        } else {
            html(FORM)
        }
        val body = page.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun html(content: String) = """
        <!doctype html><html lang="pt-br"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Minha TV</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#000;color:#fff;margin:0;padding:24px;max-width:560px}
          h1,h2{margin-top:0;letter-spacing:2px} label{display:block;margin:16px 0 6px;color:#a0a8b8}
          input{width:100%;box-sizing:border-box;padding:14px;border-radius:4px;border:1px solid #333;background:#141414;color:#fff;font-size:16px}
          button{margin-top:20px;width:100%;padding:16px;border:0;border-radius:4px;background:#fff;color:#000;font-size:18px;font-weight:600}
          a{color:#7fa8ff} small{color:#a0a8b8}
        </style></head><body>$content</body></html>
    """.trimIndent()

    companion object {
        private const val FORM = """
            <h1>MINHA TV</h1>
            <p>Cole o link de uma playlist M3U, de uma página com várias playlists ou de um repositório do GitHub. A TV encontra todas as listas sozinha.</p>
            <form method="post">
              <label>Link</label>
              <input name="url" type="url" placeholder="https://..." required autofocus>
              <label>Nome (opcional)</label>
              <input name="name" type="text" placeholder="Ex.: Canais da casa">
              <label>Guia de programação XMLTV (opcional)</label>
              <input name="epg" type="url" placeholder="https://.../guide.xml.gz">
              <button type="submit">Enviar para a TV</button>
            </form>
            <p><small>O celular precisa estar no mesmo Wi-Fi da TV.</small></p>
        """

        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
