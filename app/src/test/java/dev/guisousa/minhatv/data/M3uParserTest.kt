package dev.guisousa.minhatv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U x-tvg-url="http://epg.example/guide.xml.gz"
        #EXTINF:-1 tvg-id="canal1.br" tvg-name="Canal 1" tvg-logo="http://img/c1.png" group-title="Abertos",Canal 1 HD
        #EXTVLCOPT:http-user-agent=Mozilla/5.0
        http://example.com/live/user/pass/1.ts
        #EXTINF:-1 tvg-logo="http://img/f.jpg" group-title="Filmes | Ação",Filme Teste (2024)
        http://example.com/movie/user/pass/10.mp4
        #EXTINF:-1 group-title="Séries | Comédia",Friends S01E02 - Episódio
        http://example.com/series/user/pass/20.mkv
        #EXTINF:-1 group-title="Novidades, 2024",Chaves T2 E5
        http://example.com/a/b.mp4
        #EXTINF:-1,Sem atributos, com vírgula
        http://example.com/stream.m3u8|User-Agent=VLC%2F3&Referer=http://ref
        linha inválida
    """.trimIndent()

    @Test
    fun parsesEntriesAndAttributes() {
        val r = M3uParser.parse(sample.lineSequence(), "p1")
        assertEquals("http://epg.example/guide.xml.gz", r.epgUrl)
        assertEquals(5, r.entries.size)
        assertEquals(ContentType.LIVE, M3uParser.classify("9-1-1: 01", "24HRS =  SERIADOS", "http://h/1/2/3.ts"))
        assertEquals(ContentType.LIVE, M3uParser.classify("A&E", "CANAIS: FILMES E SÉRIES ✨", "http://h/1/2/3.ts"))
        assertEquals(ContentType.MOVIE, M3uParser.classify("A Casa", "FILMES | Lançamentos", "http://h/movie/a/b/1.mp4"))

        val live = r.entries[0]
        assertEquals("Canal 1 HD", live.name)
        assertEquals("canal1.br", live.tvgId)
        assertEquals("Abertos", live.group)
        assertEquals("http://img/c1.png", live.logo)
        assertEquals("Mozilla/5.0", live.userAgent)
        assertEquals(ContentType.LIVE, live.type)
        assertEquals("p1", live.playlistId)

        assertEquals(ContentType.MOVIE, r.entries[1].type)

        val ep = r.entries[2]
        assertEquals(ContentType.SERIES, ep.type)
        assertEquals("Friends", ep.seriesName)
        assertEquals(1, ep.season)
        assertEquals(2, ep.episode)

        val chaves = r.entries[3]
        assertEquals("Novidades, 2024", chaves.group)
        assertEquals(ContentType.SERIES, chaves.type)
        assertEquals("Chaves", chaves.seriesName)
        assertEquals(2, chaves.season)
        assertEquals(5, chaves.episode)

        val last = r.entries[4]
        assertEquals("Sem atributos, com vírgula", last.name)
        assertEquals("http://example.com/stream.m3u8", last.url)
        assertEquals("VLC/3", last.userAgent)
        assertEquals("http://ref", last.referrer)
        assertEquals(ContentType.LIVE, last.type)
        assertNull(last.logo)
    }

    @Test
    fun detectsHlsStreamVsChannelList() {
        assertTrue(M3uParser.isChannelPlaylist(sample))
        assertFalse(M3uParser.isChannelPlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts"))
        assertFalse(M3uParser.isChannelPlaylist("<html>oi</html>"))
    }

    @Test
    fun extractsPlaylistLinksFromReadme() {
        val readme = """
            # Listas
            - [Abertos](https://github.com/u/r/blob/master/abertos.m3u)
            - [Esportes](listas/esportes.m3u8)
            - Direto: http://servidor.com/get.php?username=a&password=b&type=m3u_plus
            - <a href="https://x.com/lista.m3u">x</a>
            - imagem: https://x.com/logo.png
        """.trimIndent()
        val links = LinkExtractor.extract("https://raw.githubusercontent.com/u/r/HEAD/README.md", readme)
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/u/r/master/abertos.m3u",
                "http://servidor.com/get.php?username=a&password=b&type=m3u_plus",
                "https://x.com/lista.m3u",
                "https://raw.githubusercontent.com/u/r/HEAD/listas/esportes.m3u8",
            ),
            links,
        )
        assertEquals("https://raw.githubusercontent.com/u/r/HEAD/README.md", LinkExtractor.toRaw("https://github.com/u/r"))
        assertEquals("Ramys" to "Iptv-Brasil-2026", LinkExtractor.githubRepo("https://github.com/Ramys/Iptv-Brasil-2026/blob/master/README.md"))
        assertEquals("u" to "r", LinkExtractor.githubRepo("https://raw.githubusercontent.com/u/r/HEAD/README.md"))
        assertEquals(
            "https://raw.githubusercontent.com/u/r/HEAD/listas/Filmes%20e%20S%C3%A9ries.m3u8",
            LinkExtractor.githubRawFile("u", "r", "listas/Filmes e Séries.m3u8"),
        )
        assertEquals("Canais Abertos", LinkExtractor.nameFromUrl("https://h/x/Canais%20Abertos.m3u"))
    }
}
