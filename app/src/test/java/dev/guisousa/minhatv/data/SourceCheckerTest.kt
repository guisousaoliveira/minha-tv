package dev.guisousa.minhatv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceCheckerTest {
    @Test fun masterPlaylistGivesFirstVariantResolved() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            high/index.m3u8
        """.trimIndent()
        assertEquals("https://example.com/live/low/index.m3u8", M3uLite.firstVariant(master, "https://example.com/live/master.m3u8"))
    }

    @Test fun mediaPlaylistHasNoVariant() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n"
        assertNull(M3uLite.firstVariant(media, "https://example.com/a.m3u8"))
    }

    @Test fun deadNeedsTwoFailures() {
        assertEquals(false, Health(ok = false, fails = 1, checked = 0, ms = null).dead)
        assertEquals(true, Health(ok = false, fails = 2, checked = 0, ms = null).dead)
        assertEquals(false, Health(ok = true, fails = 0, checked = 0, ms = 120).dead)
    }
}

class DisplayNameTest {
    private fun d(s: String) = Normalize.display(s)

    @Test fun channelNamesLoseCountryPrefixAndQuality() {
        assertEquals(Normalize.Display("Globo SP", "FHD"), d("BR: GLOBO SP FHD"))
        assertEquals(Normalize.Display("Globo RJ", "FHD"), d("GLOBO RJ (FHD) H.265 [ALT]"))
        assertEquals(Normalize.Display("SBT", "HD"), d("|BR| SBT HD"))
        assertEquals(Normalize.Display("Premiere 2", "FHD"), d("Premiere 2 | FHD"))
        assertEquals(Normalize.Display("Megapix", "FHD"), d("Megapix FullHD [Opção 2]"))
        assertEquals(Normalize.Display("Cartoon Network", null), d("Cartoon Network (Alternativo)"))
    }

    @Test fun moviesKeepYearAndGetAudioTag() {
        assertEquals(Normalize.Display("Duna (2021)", "4K · Dublado"), d("Duna (2021) [Dublado] 4K"))
        assertEquals(Normalize.Display("Duna 2021", "FHD · Legendado"), d("Duna 2021 1080p Leg"))
        assertEquals(Normalize.Display("Novo Mundo", null), d("Novo Mundo"))
        assertEquals(Normalize.Display("The New Mutants (2020)", null), d("The New Mutants (2020)"))
    }

    @Test fun sameChannelGetsSameKey() {
        assertEquals(Normalize.key(d("BR: GLOBO SP FHD").title), Normalize.key(d("Globo SP HD").title))
    }
}
