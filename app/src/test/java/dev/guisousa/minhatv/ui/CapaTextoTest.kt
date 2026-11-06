package dev.guisousa.minhatv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapaTextoTest {

    @Test
    fun mesmoTitulo_mesmaCor() {
        assertEquals(CapaTexto.cores("Um Trabalho do Inferno"), CapaTexto.cores("  um trabalho do inferno "))
    }

    @Test
    fun coresSaoDaPaleta() {
        listOf("", "A", "Rock in Rio 2019", "Charlie Brown", "Ação & Aventura").forEach {
            assertTrue(CapaTexto.cores(it) in CapaTexto.PARES)
        }
    }

    @Test
    fun titulosDiferentes_usamVariasCores() {
        val usadas = (1..200).map { CapaTexto.cores("Filme $it") }.toSet()
        assertTrue("só ${usadas.size} cores", usadas.size >= CapaTexto.PARES.size / 2)
    }

    @Test
    fun coresEscuras_paraTextoBranco() {
        // brilho (0..255) da cor mais clara de cada par: o título branco precisa se destacar
        CapaTexto.PARES.forEach { (a, b) ->
            listOf(a, b).forEach { c ->
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val bl = c and 0xFF
                val luz = (299 * r + 587 * g + 114 * bl) / 1000
                assertTrue("cor ${java.lang.Long.toHexString(c)} clara demais", luz < 100)
            }
        }
    }
}
