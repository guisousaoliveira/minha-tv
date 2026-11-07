package dev.guisousa.minhatv.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Atualização do catálogo contra um servidor falso: baixa só o que mudou e só recarrega as telas quando precisa. */
class CatalogoSyncTest {

    private val arquivos = HashMap<String, String>()
    private val pedidos = ArrayList<String>()

    /** Quantos pedidos seguidos falham de rede (conexão morta) antes de responder. */
    private var falhas = 0

    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val caminho = chain.request().url.encodedPath.removePrefix("/app/v2/")
        pedidos += caminho
        if (falhas > 0) { falhas--; throw java.net.SocketTimeoutException("timeout") }
        val corpo = arquivos[caminho]
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(if (corpo != null) 200 else 404).message("x")
            .body((corpo ?: "").toResponseBody("application/json".toMediaType())).build()
    }).build()

    private fun manifesto(vararg partes: Pair<String, String>) {
        arquivos["manifesto.json"] = """{"formato":2,"versao":"${partes.joinToString { it.second }.hashCode()}","fichas":256,"partes":{${partes.joinToString(",") { "\"${it.first}\":\"${it.second}\"" }}}}"""
    }

    @Test
    fun baixaSoOQueMudouERecarregaSoQuandoPrecisa() = runBlocking {
        val dir = Files.createTempDirectory("sync").toFile()
        arquivos["categorias.json"] = """{"filmes":[{"id":"101","nome":"Filmes · Ação"}]}"""
        arquivos["filmes/101.json"] = """[{"id":1,"t":"A","v":"dl"}]"""
        arquivos["fichas/f_001.json"] = """{"1":{"t":"A","v":{"dub":[{"u":"http://x/1"}]}}}"""
        arquivos["tmdb.json"] = """{"f":{}}"""
        arquivos["apelidos.json"] = """{"f":{"9":1},"s":{}}"""
        manifesto("categorias" to "c1", "filmes/101" to "f1", "fichas/f_001" to "x1", "tmdb" to "t1", "apelidos" to "a1")
        val c = Catalogo(dir, http, "http://teste/app/v2/")

        // 1ª vez: baixa as partes que o app usa (fichas só ao abrir; tmdb.json nunca)
        assertTrue(c.sync())
        assertTrue("tmdb.json não é usado pelo app", "tmdb.json" !in pedidos)
        assertTrue("ficha só ao abrir", pedidos.none { it.startsWith("fichas/") })
        assertEquals(1L, c.apelidos()!!.f["9"])
        assertEquals("http://x/1", c.ficha(false, 1)!!.v!!.dub[0].u)

        // nada mudou: só o manifesto
        pedidos.clear()
        assertFalse(c.sync())
        assertEquals(listOf("manifesto.json"), pedidos)

        // só a ficha mudou (links): não recarrega as telas; a ficha é baixada de novo ao abrir
        arquivos["fichas/f_001.json"] = """{"1":{"t":"A","v":{"dub":[{"u":"http://x/novo"}]}}}"""
        manifesto("categorias" to "c1", "filmes/101" to "f1", "fichas/f_001" to "x2", "tmdb" to "t2", "apelidos" to "a1")
        pedidos.clear()
        assertFalse(c.sync())
        assertEquals(listOf("manifesto.json"), pedidos)
        assertEquals("http://x/novo", c.ficha(false, 1)!!.v!!.dub[0].u)

        // mudou uma categoria: baixa só ela e recarrega
        arquivos["filmes/101.json"] = """[{"id":1,"t":"A","v":"dl"},{"id":2,"t":"B"}]"""
        manifesto("categorias" to "c1", "filmes/101" to "f2", "fichas/f_001" to "x2", "tmdb" to "t2", "apelidos" to "a1")
        pedidos.clear()
        assertTrue(c.sync())
        assertEquals(listOf("manifesto.json", "filmes/101.json"), pedidos)
        assertEquals(2, c.filmes("101")!!.size)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun fichaTentaDeNovoQuandoARedeFalhaEQuandoOItemNaoEstaNaGuardada() = runBlocking {
        val dir = Files.createTempDirectory("ficha").toFile()
        arquivos["categorias.json"] = """{"series":[]}"""
        arquivos["fichas/s_001.json"] = """{"1":{"t":"A","eps":[[1,1,{"dub":[{"u":"http://x/1"}]}]]}}"""
        manifesto("categorias" to "c1", "fichas/s_001" to "x1")
        val c = Catalogo(dir, http, "http://teste/app/v2/")
        c.sync()

        // conexão morta nas 2 primeiras tentativas: a 3ª baixa
        falhas = 2
        assertEquals("A", c.ficha(true, 1)?.t)

        // item novo (257 cai na mesma parte) que não está na ficha guardada: baixa de novo e acha
        arquivos["fichas/s_001.json"] = """{"1":{"t":"A"},"257":{"t":"B"}}"""
        c.clearFichaCache()
        assertEquals("B", c.ficha(true, 257)?.t)

        // fora do ar de vez: usa a ficha que já está no disco
        c.clearFichaCache()
        falhas = 100
        manifesto("categorias" to "c1", "fichas/s_001" to "x2")
        runCatching { c.sync() } // falha de rede no manifesto: segue com o que tem
        assertEquals("B", c.ficha(true, 257)?.t)
        falhas = 0

        // não existe em lugar nenhum: diz o motivo
        c.clearFichaCache()
        assertEquals(null, c.ficha(true, 513))
        assertTrue(c.fichaErro!!.contains("não está"))
        dir.deleteRecursively()
        Unit
    }
}
