package dev.guisousa.minhatv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Datas do Supabase (updated_at): decidem quem é o mais recente entre o aparelho e a nuvem. */
class NuvemTest {
    @Test
    fun leDatasDoSupabase() {
        val base = 1795402595000L // 2026-11-23T02:56:35Z
        assertEquals(base + 123, Nuvem.msDe("2026-11-23T02:56:35.123456+00:00"))
        assertEquals(base, Nuvem.msDe("2026-11-23T02:56:35Z"))
        assertEquals(base, Nuvem.msDe("2026-11-23 02:56:35+00"))
        assertEquals(base, Nuvem.msDe("2026-11-22T23:56:35-03:00")) // horário de Brasília
        assertEquals(base + 500, Nuvem.msDe("2026-11-23T02:56:35.5+00:00"))
        assertEquals(0L, Nuvem.msDe(null))
        assertEquals(0L, Nuvem.msDe("lixo"))
    }
}
