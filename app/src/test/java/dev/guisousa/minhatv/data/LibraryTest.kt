package dev.guisousa.minhatv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test fun foreignGroupsAreHidden() {
        listOf("Canais | Austrália", "BOLIVIA", "Uruguay TV", "PY: Canais", "UK | News", "Latino").forEach {
            assertTrue(it, Library.isForeignLiveGroup(it))
        }
    }

    @Test fun brazilianAndSportsGroupsStay() {
        listOf("ES: UFC", "[AR] Deportes", "Sports USA", "Canais | Globo", "Globo ES", "AL: TV Gazeta", "TV Salvador", "Box Brazil").forEach {
            assertFalse(it, Library.isForeignLiveGroup(it))
        }
    }

    @Test fun foreignChannelsInsideMixedGroups() {
        assertTrue(Library.isForeignLiveEntry(Entry(name = "AU: Seven", url = "a", group = "Mix")))
        assertTrue(Library.isForeignLiveEntry(Entry(name = "Fox", url = "b", tvgId = "Fox.us", group = "Mix")))
        assertFalse(Library.isForeignLiveEntry(Entry(name = "ESPN", url = "c", tvgId = "ESPN.us", group = "Mix")))
        assertFalse(Library.isForeignLiveEntry(Entry(name = "Globo", url = "d", tvgId = "Globo.br", group = "Mix")))
    }
}
