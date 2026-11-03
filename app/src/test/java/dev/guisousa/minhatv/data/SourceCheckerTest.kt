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
