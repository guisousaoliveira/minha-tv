package dev.guisousa.minhatv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class ContaTest {
    init { Conta.b64 = { java.util.Base64.getDecoder().decode(it) } }

    /** Valores gerados com as mesmas funções JavaScript da página entrar.html. */
    @Test fun compativelComAPagina() {
        assertEquals("pair-f583a90b42d71dc564212aaf", Conta.canalDo("ABCD2345"))
        val texto = Conta.decifrar(
            "ABCD2345", "MuEvzcRukRWXV4Gn",
            "3Cxa4tGo7P65JQCig0E/FvwQnzG9gXxGBlX3Sbk8vIIamWdbBIJ+/Y/1GzWHL9F6euLEDbW+LljCFw==",
        )
        assertEquals("""{"refresh_token":"rt-teste","nome":"Lobo"}""", texto)
    }

    @Test fun codigoSemLetrasConfusas() {
        repeat(200) {
            val c = Conta.novoCodigo()
            assertEquals(8, c.length)
            assert(c.none { it in "01OILU" }) { c }
        }
    }

    /** Liga no Supabase de verdade: um canal manda, o outro recebe. Rode com CONTA_ONLINE=1. */
    @Test fun canalAoVivoEntregaMensagem() = runBlocking {
        assumeTrue(System.getenv("CONTA_ONLINE") == "1")
        val nome = Conta.canalDo(Conta.novoCodigo())
        val chegou = CompletableDeferred<JsonObject>()
        val tv = Conta.CanalAoVivo(nome) { ev, p -> if (ev == "sessao") chegou.complete(p) }
        val cel = Conta.CanalAoVivo(nome) { _, _ -> }
        tv.abrir(); cel.abrir()
        delay(2500)
        cel.enviar("sessao", buildJsonObject { put("iv", "x"); put("ct", "y") })
        val p = withTimeout(10_000) { chegou.await() }
        assertEquals("y", p["ct"]!!.jsonPrimitive.content)
        tv.fechar(); cel.fechar()
    }
}
