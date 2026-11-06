package dev.guisousa.minhatv.player

// Contas do player sem nada de Android (testadas em app/src/test).

/** Barra de tempo: quanto cada aperto de ◀▶ anda. Segurando a tecla, o passo cresce. */
object SeekStep {
    /** [repeatCount] do KeyEvent: 0 no primeiro aperto, sobe enquanto a tecla fica segurada. */
    fun stepFor(repeatCount: Int): Long = when {
        repeatCount < 8 -> 10_000L
        repeatCount < 20 -> 30_000L
        repeatCount < 40 -> 60_000L
        else -> 300_000L
    }

    /**
     * Passo pelo TEMPO com a tecla segurada (cada controle repete a tecla numa velocidade, então contar repetições
     * dava resultados diferentes): até 1,5 s anda 10 s; até 3 s, 30 s; até 5 s, 1 min; depois, 5 min.
     */
    fun stepForHeld(heldMs: Long): Long = when {
        heldMs < 1_500 -> 10_000L
        heldMs < 3_000 -> 30_000L
        heldMs < 5_000 -> 60_000L
        else -> 300_000L
    }

    /** Novo alvo a partir de [base], sem passar do começo nem do fim (para 1 s antes do fim). */
    fun target(base: Long, delta: Long, duration: Long): Long {
        var t = base + delta
        if (duration > 0) t = t.coerceAtMost((duration - 1000).coerceAtLeast(0))
        return t.coerceAtLeast(0)
    }
}

/** Troca de canal com ▲▼: anda na lista e dá a volta nas pontas. */
object Zap {
    fun wrap(i: Int, size: Int): Int = if (size <= 0) 0 else ((i % size) + size) % size
}
