package dev.guisousa.minhatv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegrasTest {

    private val nomes = mapOf(
        "lan" to "Lançamentos",
        "leg" to "Legendados",
        "4k" to "4K UHD",
        "div" to "Diversos",
        "net" to "Netflix",
        "acao" to "Ação",
        "alta" to "Em Alta",
        "out" to "Outras",
    )

    private fun escolher(sugerida: String?, vararg doTitulo: String) =
        CategoriaPrincipal.escolher(sugerida, doTitulo.toList(), "lan") { nomes[it] }

    @Test
    fun secundarias_porNomeSemAcento() {
        listOf("Lançamentos", "LANCAMENTOS 2025", "Em Alta no Brasil", "Legendados", "Filmes Legendadas", "4K", "UHD", "Diversos", "Outras", "Outros")
            .forEach { assertTrue(it, CategoriaPrincipal.ehSecundaria(it)) }
        listOf("Netflix", "Ação", "Comédia", "Prime Video", "Altas Aventuras", "Documentários")
            .forEach { assertFalse(it, CategoriaPrincipal.ehSecundaria(it)) }
    }

    @Test
    fun principal_usaCampoDoServidor() {
        assertEquals("acao", escolher("acao", "leg", "net", "acao"))
    }

    @Test
    fun principal_ignoraCampoDesconhecido() {
        assertEquals("net", escolher("nao-existe", "leg", "net"))
    }

    @Test
    fun principal_pulaLegendados4kDiversos() {
        // filme com dub+leg: antes saía "Legendados" por estar em segundo lugar
        assertEquals("net", escolher(null, "lan", "leg", "4k", "net", "acao"))
        assertEquals("acao", escolher(null, "div", "alta", "acao"))
    }

    @Test
    fun principal_semNenhumaBoa_caiNaRegraAntiga() {
        assertEquals("leg", escolher(null, "lan", "leg", "div"))
        assertEquals("lan", escolher(null, "lan"))
        assertNull(escolher(null))
    }

    @Test
    fun favoritos_episodioViraSerie() {
        assertEquals("v2:e:42", FavRefs.normalizar("v2:e:42:1:3"))
        assertEquals("v2:e:42", FavRefs.normalizar("v2:e:42"))
        assertEquals("v2:f:7", FavRefs.normalizar("v2:f:7"))
        assertEquals("v2:c:9", FavRefs.normalizar("v2:c:9"))
        assertTrue(FavRefs.ehSerie("v2:e:42"))
        assertFalse(FavRefs.ehSerie("v2:e:42:1:3"))
        assertFalse(FavRefs.ehSerie("v2:f:42"))
        assertFalse(FavRefs.ehSerie("v2:e:"))
    }

    @Test
    fun favoritos_conversaoJuntaEpisodiosDaMesmaSerie() {
        val antigos = setOf("v2:e:42:1:3", "v2:e:42:2:1", "v2:f:7", "v2:c:9", "v2:e:5")
        assertEquals(setOf("v2:e:42", "v2:f:7", "v2:c:9", "v2:e:5"), FavRefs.converter(antigos))
        // converter de novo não muda nada
        assertEquals(FavRefs.converter(antigos), FavRefs.converter(FavRefs.converter(antigos)))
    }

    @Test
    fun tituloFileira_prefixaQuandoRepete() {
        assertEquals("Filmes da Netflix", TituloFileira.titulo(false, "Netflix", true))
        assertEquals("Séries da Netflix", TituloFileira.titulo(true, "Netflix", true))
        assertEquals("Filmes do Prime Video", TituloFileira.titulo(false, "Prime Video", true))
        assertEquals("Séries de Ação", TituloFileira.titulo(true, "Ação", true))
        assertEquals("Netflix", TituloFileira.titulo(false, "Netflix", false))
    }
}
