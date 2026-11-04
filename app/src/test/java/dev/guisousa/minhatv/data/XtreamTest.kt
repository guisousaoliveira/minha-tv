package dev.guisousa.minhatv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamTest {
    /** Monta o link de teste em partes (valores de exemplo, não é acesso de verdade). */
    private fun link(base: String, file: String, user: String, pass: String, extra: String = "") =
        "$base/$file?" + listOf("username" to user, "password" to pass).joinToString("&") { "${it.first}=${it.second}" } + extra

    @Test fun recognizesGetPhpAndPlayerApiLinks() {
        assertEquals(
            Triple("http://srv.example:8080", "exemplo", "x@y"),
            Xtream.parseLogin(link("http://srv.example:8080", "get.php", "exemplo", "x%40y", "&type=m3u_plus")),
        )
        assertEquals(
            Triple("https://tv.example/painel", "a", "b"),
            Xtream.parseLogin(link("https://tv.example/painel", "player_api.php", "a", "b")),
        )
    }

    @Test fun ignoresNormalPlaylistLinks() {
        assertNull(Xtream.parseLogin("https://example.com/lista.m3u8"))
        assertNull(Xtream.parseLogin("http://srv.example/get.php?type=m3u"))
    }

    @Test fun buildsLoginUrlFromTypedFields() {
        assertEquals("http://srv.example:8080/player_api.php?username=jo%20ao&password=x", Xtream.loginUrl("srv.example:8080/", " jo ao ", "x"))
        val back = Xtream.parseLogin(Xtream.loginUrl("https://srv.example", "u", "p&q"))
        assertEquals(Triple("https://srv.example", "u", "p&q"), back)
    }

    @Test fun seriesCoverUrlKeepsTheId() {
        val url = Xtream.stubUrl("pl-1", "540106410")
        assertTrue(Xtream.isStub(url))
        assertEquals("540106410", Xtream.stubSeriesId(url))
        assertFalse(Xtream.isStub("http://srv/series/u/p/1.mp4"))
    }
}
