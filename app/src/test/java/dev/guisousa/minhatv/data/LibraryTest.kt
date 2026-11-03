package dev.guisousa.minhatv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test fun foreignGroupsAreHidden() {
        listOf("Canais | Austrália", "BOLIVIA", "Uruguay TV", "PY: Canais", "UK | News", "Latino", "Canais · Latinos", "Canais · Uruguay",
            "Canais · França", "Pt · Vip Filmes", "USA VIP", "Canais · Espanha", "United States").forEach {
            assertTrue(it, Library.isForeignLiveGroup(it))
        }
    }

    @Test fun brazilianAndSportsGroupsStay() {
        listOf("ES: UFC", "[AR] Deportes", "Sports USA", "Canais | Globo", "Globo ES", "AL: TV Gazeta", "TV Salvador", "Box Brazil").forEach {
            assertFalse(it, Library.isForeignLiveGroup(it))
        }
    }

    @Test fun leftoversFromTvTest() {
        listOf("Russian VIP", "Ocea Newzealand VIP").forEach { assertTrue(it, Library.isForeignLiveGroup(it)) }
        listOf("Exyu Sport VIP", "Es·Laliga VIP", "USA NFL Gamepass", "Lame·Brazil VIP", "Rádios Fm", "Outros").forEach {
            assertFalse(it, Library.isForeignLiveGroup(it))
        }
    }

    @Test fun foreignChannelsInsideMixedGroups() {
        assertTrue(Library.isForeignLiveEntry(Entry(name = "AU: Seven", url = "a", group = "Mix")))
        assertTrue(Library.isForeignLiveEntry(Entry(name = "Fox", url = "b", tvgId = "Fox.us", group = "Mix")))
        assertFalse(Library.isForeignLiveEntry(Entry(name = "ESPN", url = "c", tvgId = "ESPN.us", group = "Mix")))
        assertFalse(Library.isForeignLiveEntry(Entry(name = "Globo", url = "d", tvgId = "Globo.br", group = "Mix")))
        assertTrue(Library.isForeignLiveEntry(Entry(name = "Galavision New York", url = "e", group = "Mix")))
        assertTrue(Library.isForeignLiveEntry(Entry(name = "Telemundo Internacional", url = "f", group = "Mix")))
        assertTrue(Library.isForeignLiveEntry(Entry(name = "Home Made Nation USA VIP", url = "g", group = "Canais")))
        assertFalse(Library.isForeignLiveEntry(Entry(name = "Canal Brasil", url = "h", group = "Canais")))
        assertFalse(Library.isForeignLiveEntry(Entry(name = "Fantasia", url = "i", group = "Canais")))
    }
}
