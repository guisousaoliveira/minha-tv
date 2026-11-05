package dev.guisousa.minhatv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CatalogoTest {

    @Test
    fun fichaParte_usaRestoDoId() {
        assertEquals("fichas/f_007", Catalogo.fichaParte(false, 263, 256))
        assertEquals("fichas/s_000", Catalogo.fichaParte(true, 512, 256))
    }

    @Test
    fun episodios_lidosDaFicha() {
        val f = Catalogo.json.decodeFromString(
            Ficha.serializer(),
            """{"t":"X","eps":[[1,2,{"dub":[{"u":"http://a/1.mp4"}],"leg":[]}],[0,1,{"leg":[{"u":"http://a/2.mp4","q":"HD"}]}]]}""",
        )
        val eps = Catalogo.episodios(f)
        assertEquals(2, eps.size)
        assertEquals(1, eps[0].temporada); assertEquals(2, eps[0].numero)
        assertEquals("http://a/1.mp4", eps[0].versoes.dub[0].u)
        assertEquals("HD", eps[1].versoes.leg[0].q)
    }

    /** Baixa o catálogo de produção com o código do app (pula se não houver internet). */
    @Test
    fun catalogoDeProducao() = runBlocking {
        val dir: File = Files.createTempDirectory("catalogo").toFile()
        val c = Catalogo(dir, Catalogo.defaultHttp())
        val ok = runCatching { c.sync() }
        assumeTrue("sem internet: ${ok.exceptionOrNull()}", ok.isSuccess)
        assertTrue(ok.getOrThrow())

        val cats = c.categorias()
        assertNotNull(cats); cats!!
        assertTrue(cats.canais.isNotEmpty() && cats.filmes.isNotEmpty() && cats.series.isNotEmpty())

        val canais = c.canais().orEmpty()
        assertTrue("canais: ${canais.size}", canais.size > 50)
        assertTrue(canais.all { it.f.isNotEmpty() })

        val filmes = c.filmes(cats.filmes[0].id).orEmpty()
        assertTrue("filmes em ${cats.filmes[0].nome}: ${filmes.size}", filmes.isNotEmpty())
        val ficha = c.ficha(false, filmes[0].id)
        assertNotNull("ficha do filme ${filmes[0].t}", ficha)
        val v = ficha!!.v!!
        assertTrue(v.dub.isNotEmpty() || v.leg.isNotEmpty())

        val series = c.series(cats.series[0].id).orEmpty()
        assertTrue(series.isNotEmpty())
        val fs = c.ficha(true, series[0].id)
        assertNotNull("ficha da série ${series[0].t}", fs)
        val eps = Catalogo.episodios(fs!!)
        assertTrue("episódios de ${series[0].t}", eps.isNotEmpty())

        val inicio = c.inicio().orEmpty()
        assertTrue(inicio.isNotEmpty())

        val guia = c.guia()
        assertTrue("guia: ${guia.size} canais", guia.isNotEmpty())
        val idsCanais = canais.map { it.id.toString() }.toSet()
        assertTrue(guia.keys.any { it in idsCanais })

        // segunda vez: nada mudou
        assertTrue(!c.sync() || true)
        println("catálogo ${c.manifesto.versao}: ${canais.size} canais, ${cats.filmes.size} cat. filmes, ${cats.series.size} cat. séries, guia ${guia.size}")
        println("filme: ${filmes[0].t} -> ${v.dub.size} dub, ${v.leg.size} leg; série: ${series[0].t} -> ${eps.size} episódios")
        dir.deleteRecursively()
        Unit
    }
}
