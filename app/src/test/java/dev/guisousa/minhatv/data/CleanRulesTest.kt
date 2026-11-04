package dev.guisousa.minhatv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanRulesTest {
    @Test fun builtinRulesLoadFromRepoFile() {
        assertTrue("regras/limpeza.json não carregou", CleanRules.builtin.version >= 1)
    }

    @Test fun classifyMarksForeignAdultAndJunk() {
        val r = CleanRules.builtin
        assertEquals(CleanRules.FOREIGN, r.classify("AU: Seven", "Mix", null, ContentType.LIVE))
        assertEquals(0, r.classify("ES: UFC 300", "Eventos", null, ContentType.LIVE))
        assertEquals(0, r.classify("Globo SP", "Canais | Globo", "Globo.br", ContentType.LIVE))
        // país só vale para canais ao vivo; filme com "Mexico" no nome fica
        assertEquals(0, r.classify("Viva Mexico", "Filmes", null, ContentType.MOVIE))
        assertEquals(CleanRules.ADULT, r.classify("Qualquer", "Adultos +18", null, ContentType.MOVIE))
        assertEquals(CleanRules.JUNK, r.classify("-----", "Canais", null, ContentType.LIVE) and CleanRules.JUNK)
    }

    @Test fun rulesFromJsonMergeAndHideGroups() {
        val r = CleanRules.parse(
            """{"versao": 9, "esconder_categorias": ["^teste"], "juntar_categorias": [{"de": "^canais . globo|^globo$", "para": "Globo"}]}"""
        )
        assertEquals(9, r.version)
        assertEquals("Globo", r.group("Canais · Globo"))
        assertEquals("Globo", r.group("GLOBO"))
        assertEquals("SBT", r.group("SBT"))
        assertEquals(CleanRules.JUNK, r.classify("Canal", "Teste VIP", null, ContentType.LIVE))
    }

    @Test fun badPatternIsIgnoredNotFatal() {
        val r = CleanRules.parse("""{"versao": 2, "esconder_nomes": ["(sem fechar", "^lixo$"]}""")
        assertEquals(CleanRules.JUNK, r.classify("lixo", "X", null, ContentType.MOVIE))
        assertEquals(0, r.classify("bom", "X", null, ContentType.MOVIE))
    }
}

class SameFileTest {
    @Test fun githubRefsPointToSameFile() {
        val a = LinkExtractor.sameFileKey("https://raw.githubusercontent.com/o/r/HEAD/listas/canais.m3u")
        assertEquals(a, LinkExtractor.sameFileKey("https://raw.githubusercontent.com/o/r/main/listas/canais.m3u"))
        assertEquals(a, LinkExtractor.sameFileKey("https://raw.githubusercontent.com/o/r/refs/heads/main/listas/canais.m3u"))
        assertEquals(a, LinkExtractor.sameFileKey("https://github.com/o/r/blob/main/listas/canais.m3u"))
        assertTrue(a != LinkExtractor.sameFileKey("https://raw.githubusercontent.com/o/r/main/listas/filmes.m3u"))
    }

    @Test fun nullGroupIsNoGroup() {
        val r = M3uParser.parse(sequenceOf("#EXTM3U", "#EXTINF:-1 group-title=\"null\",Canal X", "http://x/live/1.ts"))
        assertEquals("", r.entries.single().group)
    }
}

class EpisodeAndShowTest {
    @Test fun episodeTitleDropsSeriesName() {
        assertEquals("Metamorfose", Normalize.episodeTitle("Smallville S01E02 Metamorfose", "Smallville", 2))
        assertEquals("Episódio 2", Normalize.episodeTitle("Smallville S01E02", "Smallville", 2))
        assertEquals("O Começo", Normalize.episodeTitle("S01E01 - O Começo", "Lost", 1))
    }

    @Test fun showKeyIgnoresYearInBrackets() {
        assertEquals(Normalize.showKey("Smallville"), Normalize.showKey("Smallville (2001)"))
        assertEquals(Normalize.showKey("Scooby-Doo"), Normalize.showKey("Scooby-Doo [2002]"))
        assertTrue(Normalize.showKey("Blade 2") != Normalize.showKey("Blade"))
        assertEquals(Normalize.showKey("Smallville: As Aventuras do Superboy"), Normalize.showKey("Smallville As Aventuras do Superboy [L]"))
    }

    @Test fun looseKeyIgnoresPunctuationAndArticles() {
        assertEquals(Normalize.looseKey("Batman: A Alma do Dragão"), Normalize.looseKey("Batman Alma do Dragao"))
        assertEquals(Normalize.looseKey("Batman A Alma do Dragão FHD"), Normalize.looseKey("Batman: Alma do Dragão"))
        assertTrue(Normalize.looseKey("A&E") != Normalize.looseKey("E"))
        assertTrue(Normalize.looseKey("Duna (2021)") != Normalize.looseKey("Duna (1984)"))
    }
}
