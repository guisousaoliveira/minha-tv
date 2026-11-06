package dev.guisousa.minhatv.ui

// Cores da "capa de texto" (cartão sem capa ou com capa quebrada). Sem Compose: dá para testar na JVM.

object CapaTexto {
    /** Pares de cores (ARGB) escuros, para o título branco ficar legível em cima. */
    val PARES: List<Pair<Long, Long>> = listOf(
        0xFF1E3A5FL to 0xFF0B1A2EL, // azul-marinho
        0xFF4A1942L to 0xFF1C0A1AL, // ameixa
        0xFF1B4D3EL to 0xFF0A1F19L, // verde
        0xFF5C2E0EL to 0xFF241005L, // âmbar
        0xFF3B2A6BL to 0xFF140E2BL, // anil
        0xFF5A1A1AL to 0xFF220909L, // vinho
        0xFF0F4C5CL to 0xFF06222AL, // petróleo
        0xFF4B4B1EL to 0xFF1E1E0AL, // oliva
    )

    /** Sempre o mesmo par para o mesmo título (sem diferença de maiúsculas ou espaços nas pontas). */
    fun cores(titulo: String): Pair<Long, Long> =
        PARES[Math.floorMod(titulo.trim().lowercase().hashCode(), PARES.size)]
}
