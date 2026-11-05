package dev.guisousa.minhatv

import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MesmoFilmeTest {
    private fun movie(name: String, poster: String?) =
        Entry(name = name, url = "u/$name/$poster", type = ContentType.MOVIE, logo = poster?.let { "https://img.example/t/p/w600/$it" })

    @Test
    fun semAnoSoJuntaComOMesmoPoster() {
        val desenho = movie("101 Dálmatas", "yvNj.jpg")
        val legendado = movie("101 Dálmatas [L]", "yvNj.jpg")
        val liveAction = movie("101 Dálmatas", "aq0M.jpg")
        assertTrue(Normalize.sameMovie(desenho, legendado))
        assertFalse(Normalize.sameMovie(desenho, liveAction))
        assertFalse(Normalize.sameMovie(legendado, liveAction))
    }

    @Test
    fun numeroNaFrenteDoPosterNaoAtrapalha() {
        assertTrue(Normalize.sameMovie(movie("Nosferatu", "v7cs.jpg"), movie("Nosferatu [L]", "12230_v7cs.jpg")))
    }

    @Test
    fun comAnoContinuaJuntando() {
        assertTrue(Normalize.sameMovie(movie("Duna (2021)", "a.jpg"), movie("Duna [L] (2021)", "b.jpg")))
    }

    @Test
    fun semAnoESemPosterSoJuntaSeAmbasNaoTemPoster() {
        assertTrue(Normalize.sameMovie(movie("Filme", null), movie("Filme [L]", null)))
        assertFalse(Normalize.sameMovie(movie("101 Dálmatas", null), movie("101 Dálmatas [L]", "yvNj.jpg")))
    }

    @Test
    fun mesmoPosterEmTamanhosDiferentesViraUmItemSo() {
        val a = Entry(name = "101 Dálmatas", url = "u1", type = ContentType.MOVIE, logo = "https://image.tmdb.org/t/p/w300/yvNj.jpg", norm = "101dalmatas")
        val b = Entry(name = "101 Dálmatas", url = "u2", type = ContentType.MOVIE, logo = "http://dafonte.uk/images/capa/12230_yvNj.jpg", norm = "101dalmatas")
        val c = Entry(name = "101 Dálmatas", url = "u3", type = ContentType.MOVIE, logo = "https://image.tmdb.org/t/p/w600/aq0M.jpg", norm = "101dalmatas")
        assertEquals(Normalize.mergeKey(a), Normalize.mergeKey(b))
        assertNotEquals(Normalize.mergeKey(a), Normalize.mergeKey(c))
    }
}
