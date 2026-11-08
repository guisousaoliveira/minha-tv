package dev.guisousa.minhatv.desktop.player

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveProxyTest {
    /** Servidor falso: o link do canal redireciona para um endereço com senha que só vale uma vez. */
    @Test fun atualizacaoVoltaPeloLinkOriginal() {
        val n = AtomicInteger(0)
        val usados = mutableSetOf<String>()
        val origem = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/live/u/s/1.m3u8") { ex ->
                ex.responseHeaders.add("Location", "/tok${n.incrementAndGet()}/index.m3u8")
                ex.sendResponseHeaders(302, -1); ex.close()
            }
            createContext("/tok") { ex ->
                val p = ex.requestURI.path
                val ok = synchronized(usados) { usados.add(p) }
                if (!ok) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
                val tok = p.split('/')[1]
                val b = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\n/hls/$tok/10.ts\n#EXTINF:4,\nseg11.ts\n".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        val base = "http://127.0.0.1:${origem.address.port}"
        val local = LiveProxy.endereco("$base/live/u/s/1.m3u8", SAFARI_UA, null)
        repeat(3) { i ->
            val txt = URI(local).toURL().readText()
            assertTrue(txt.contains("$base/hls/tok${i + 1}/10.ts"), txt)
            assertTrue(txt.contains("$base/tok${i + 1}/seg11.ts"), txt)
        }
        origem.stop(0)
    }

    @Test fun subListaPassaPeloProxy() {
        val txt = LiveProxy.reescrever(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvideo/alta.m3u8\n#EXT-X-KEY:METHOD=AES-128,URI=\"chave.key\"\n",
            "http://srv.com/a/b/master.m3u8", "/c/7", porta = 5000,
        )
        assertTrue(txt.contains("http://127.0.0.1:5000/c/7/sub.m3u8?u=http%3A%2F%2Fsrv.com%2Fa%2Fb%2Fvideo%2Falta.m3u8"), txt)
        assertTrue(txt.contains("URI=\"http://srv.com/a/b/chave.key\""), txt)
        assertEquals(4, txt.trim().lines().size)
    }
}
