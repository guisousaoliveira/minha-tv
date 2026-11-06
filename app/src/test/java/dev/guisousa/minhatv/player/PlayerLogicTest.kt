package dev.guisousa.minhatv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLogicTest {

    @Test
    fun passo_aceleraSegurandoATecla() {
        assertEquals(10_000L, SeekStep.stepFor(0))
        assertEquals(10_000L, SeekStep.stepFor(7))
        assertEquals(30_000L, SeekStep.stepFor(8))
        assertEquals(30_000L, SeekStep.stepFor(19))
        assertEquals(60_000L, SeekStep.stepFor(20))
        assertEquals(60_000L, SeekStep.stepFor(39))
        assertEquals(300_000L, SeekStep.stepFor(40))
        assertEquals(300_000L, SeekStep.stepFor(500))
    }

    @Test
    fun passo_peloTempoSegurando_igualEmQualquerControle() {
        assertEquals(10_000L, SeekStep.stepForHeld(0))
        assertEquals(10_000L, SeekStep.stepForHeld(1_499))
        assertEquals(30_000L, SeekStep.stepForHeld(1_500))
        assertEquals(60_000L, SeekStep.stepForHeld(3_000))
        assertEquals(300_000L, SeekStep.stepForHeld(5_000))
        var antes = 0L
        for (ms in 0L..10_000L step 100) {
            val p = SeekStep.stepForHeld(ms)
            assertTrue(p >= antes)
            antes = p
        }
    }

    @Test
    fun passo_nuncaDiminuiEnquantoSegura() {
        var antes = 0L
        for (r in 0..100) {
            val p = SeekStep.stepFor(r)
            assertTrue(p >= antes)
            antes = p
        }
    }

    @Test
    fun alvo_ficaDentroDoVideo() {
        val dur = 3_600_000L
        assertEquals(70_000L, SeekStep.target(60_000L, 10_000L, dur))
        assertEquals(0L, SeekStep.target(5_000L, -10_000L, dur))
        // não passa de 1 s antes do fim
        assertEquals(dur - 1000, SeekStep.target(dur - 5_000L, 300_000L, dur))
        // duração desconhecida: só não fica negativo
        assertEquals(400_000L, SeekStep.target(100_000L, 300_000L, -1L))
        assertEquals(0L, SeekStep.target(1_000L, -10_000L, 0L))
    }

    @Test
    fun zap_daAVoltaNasPontas() {
        assertEquals(1, Zap.wrap(1, 5))
        assertEquals(0, Zap.wrap(5, 5))
        assertEquals(4, Zap.wrap(-1, 5))
        assertEquals(3, Zap.wrap(-7, 5))
        assertEquals(0, Zap.wrap(3, 0))
    }
}
