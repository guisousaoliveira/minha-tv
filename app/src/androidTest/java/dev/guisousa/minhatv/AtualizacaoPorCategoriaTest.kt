package dev.guisousa.minhatv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Db
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Normalize
import dev.guisousa.minhatv.data.Xtream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Troca só as categorias que mudaram no servidor, sem mexer no resto da lista. */
@RunWith(AndroidJUnit4::class)
class AtualizacaoPorCategoriaTest {
    private val db = Db(InstrumentationRegistry.getInstrumentation().targetContext)
    private val pl = "teste-por-categoria"

    private fun movie(name: String, grp: String, url: String) = Entry(name = name, url = url, group = grp, type = ContentType.MOVIE)

    private fun urls(grp: String) = db.entries(ContentType.MOVIE, grp).filter { it.url.startsWith("http://teste/") }.map { it.url }.toSet()

    @After
    fun limpa() = db.deletePlaylist(pl)

    @Test
    fun filmesSoACategoriaQueMudou() {
        db.replacePlaylist(pl) { add ->
            add(movie("Filme Um (2020)", "Teste Categoria A", "http://teste/1"))
            add(movie("Filme Dois (2021)", "Teste Categoria A", "http://teste/2"))
            add(movie("Filme Tres (2022)", "Teste Categoria B", "http://teste/3"))
        }
        val a = db.groupLabel("Teste Categoria A")
        val b = db.groupLabel("Teste Categoria B")

        // na categoria A, o 2 saiu e entrou o 4; a B não foi mexida
        val mudou = db.updateGroups(pl, ContentType.MOVIE, setOf(a)) { add ->
            add(movie("Filme Um (2020)", "Teste Categoria A", "http://teste/1"))
            add(movie("Filme Quatro (2023)", "Teste Categoria A", "http://teste/4"))
        }
        assertEquals(2, mudou)
        assertEquals(setOf("http://teste/1", "http://teste/4"), urls(a))
        assertEquals(setOf("http://teste/3"), urls(b))
        assertEquals(3, db.countTypes(pl).movie)

        // a mesma coisa de novo: nada muda
        val deNovo = db.updateGroups(pl, ContentType.MOVIE, setOf(a)) { add ->
            add(movie("Filme Um (2020)", "Teste Categoria A", "http://teste/1"))
            add(movie("Filme Quatro (2023)", "Teste Categoria A", "http://teste/4"))
        }
        assertEquals(0, deNovo)
    }

    @Test
    fun serieQueMudouPerdeOsEpisodiosGuardados() {
        val stub = Xtream.stubUrl(pl, "10")
        fun capa(lastModified: Long) = Entry(name = "Serie Teste X", url = stub, group = "Teste Series", type = ContentType.SERIES, seriesName = "Serie Teste X", added = lastModified)
        db.replacePlaylist(pl) { add -> add(capa(1)) }
        val key = Normalize.showKey("Serie Teste X")
        val grp = db.groupLabel("Teste Series")
        db.addEpisodes(pl, key, "Serie Teste X", grp, listOf(
            Entry(name = "Serie Teste X S01E01", url = "http://teste/ep1", type = ContentType.SERIES, season = 1, episode = 1),
        ))
        assertTrue(db.episodes(key).any { it.url == "http://teste/ep1" })

        // capa igual: os episódios guardados ficam
        assertEquals(0, db.updateGroups(pl, ContentType.SERIES, setOf(grp)) { add -> add(capa(1)) })
        assertTrue(db.episodes(key).any { it.url == "http://teste/ep1" })

        // last_modified mudou (a série ganhou episódio): os guardados saem e são pedidos de novo ao abrir
        assertEquals(2, db.updateGroups(pl, ContentType.SERIES, setOf(grp)) { add -> add(capa(2)) })
        val eps = db.episodes(key)
        assertFalse(eps.any { it.url == "http://teste/ep1" })
        assertTrue(eps.any { it.url == stub })
        assertEquals(1, db.countTypes(pl).series)
    }
}
