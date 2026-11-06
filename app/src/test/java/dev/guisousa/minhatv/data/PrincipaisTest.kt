package dev.guisousa.minhatv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrincipaisTest {

    private fun cats(json: String) = Catalogo.json.decodeFromString(Categorias.serializer(), json)

    @Test
    fun categoria_comIds_lidaDoJson() {
        val c = cats("""{"canais":[{"id":"globo","nome":"Globo"},{"id":"principais","nome":"Principais","ids":[3,1,2]}]}""")
        assertNull(c.canais[0].ids)
        assertEquals(listOf(3L, 1L, 2L), c.canais[1].ids)
    }

    @Test
    fun principais_vemPrimeiro() {
        val c = cats("""{"canais":[{"id":"globo","nome":"Globo"},{"id":"sbt","nome":"SBT"},{"id":"principais","nome":"Principais","ids":[1]}]}""")
        assertEquals(listOf("principais", "globo", "sbt"), ordemAoVivo(c.canais).map { it.id })
    }

    @Test
    fun semPrincipais_ordemDeHoje() {
        val c = cats("""{"canais":[{"id":"globo","nome":"Globo"},{"id":"sbt","nome":"SBT"}]}""")
        assertEquals(listOf("globo", "sbt"), ordemAoVivo(c.canais).map { it.id })
    }

    @Test
    fun canais_naOrdemDosIds_semOsQueNaoExistem() {
        val porId = mapOf(1L to "Globo", 2L to "SBT", 3L to "Record")
        assertEquals(listOf("Record", "Globo", "SBT"), canaisPorIds(listOf(3L, 99L, 1L, 2L, 1L), porId))
        assertEquals(emptyList<String>(), canaisPorIds(listOf(7L), porId))
    }
}
