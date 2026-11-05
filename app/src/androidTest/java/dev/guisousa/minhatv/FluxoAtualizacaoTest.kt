package dev.guisousa.minhatv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.guisousa.minhatv.data.Db
import dev.guisousa.minhatv.data.Repository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O caminho do app de verdade contra um servidor Xtream (só pelo workflow "Teste no emulador"):
 * adiciona o servidor, abre de novo sem nada mudar, e simula uma categoria que mudou no servidor.
 */
@RunWith(AndroidJUnit4::class)
class FluxoAtualizacaoTest {

    private fun log(s: String) { Log.i("MinhaTVTeste", s); println(s) }

    private fun secs(t0: Long) = "%.1f s".format((System.currentTimeMillis() - t0) / 1000.0)

    private fun sql(ctx: Context) = SQLiteDatabase.openDatabase(ctx.getDatabasePath("minhatv.db").path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun count(ctx: Context, playlist: String, where: String = "1", args: Array<String> = emptyArray()): Int =
        sql(ctx).use { db -> db.rawQuery("SELECT COUNT(*) FROM entries WHERE playlist = ? AND $where", arrayOf(playlist, *args)).use { it.moveToFirst(); it.getInt(0) } }

    @Test
    fun abreReabreEUmaCategoriaMuda() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val server = args.getString("server").orEmpty().trimEnd('/')
        assumeTrue("sem servidor", server.isNotEmpty())
        val user = args.getString("user").orEmpty()
        val pass = args.getString("pass").orEmpty().ifEmpty { user }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Repository.init(ctx)
        Repository.awaitLoaded()
        val prefs = ctx.getSharedPreferences("minhatv", Context.MODE_PRIVATE)

        // 1) primeira vez: baixa tudo
        var t0 = System.currentTimeMillis()
        val done = CompletableDeferred<String>()
        Repository.addFromUrl("$server/player_api.php?username=$user&password=$pass") { done.complete(it) }
        val msg = withTimeout(25 * 60_000L) { done.await() }
        val p = Repository.playlists.value.first { it.isXtream }
        val total = count(ctx, p.id)
        log("FLUXO 1) primeira importação em ${secs(t0)}: $msg ($total linhas)")
        assertNull(p.lastError)
        val partsJson = prefs.getString("listparts_${p.id}", null)
        assertNotNull("versões das categorias não foram guardadas", partsJson)

        // 2) abre de novo, nada mudou no servidor: não baixa nem troca nada
        t0 = System.currentTimeMillis()
        Repository.refreshAll()
        log("FLUXO 2) conferência sem mudança em ${secs(t0)}: ${count(ctx, p.id)} linhas")
        assertEquals(total, count(ctx, p.id))

        // 3) uma categoria de filmes "mudou" no servidor (versão guardada diferente) e faltam itens dela no aparelho
        val parts = JSONObject(partsJson!!)
        val key = parts.keys().asSequence().first { it.startsWith("vod:") }
        val rawName = parts.getJSONArray(key).getString(1)
        val grp = Db(ctx).groupLabel(rawName)
        parts.put(key, parts.getJSONArray(key).put(0, "versao-velha"))
        prefs.edit().putString("listparts_${p.id}", parts.toString()).remove("listver_${p.id}").commit()
        val naCategoria = count(ctx, p.id, "type = 1 AND grp = ?", arrayOf(grp))
        sql(ctx).use { it.execSQL("DELETE FROM entries WHERE id IN (SELECT id FROM entries WHERE playlist = ? AND type = 1 AND grp = ? LIMIT 5)", arrayOf(p.id, grp)) }
        val outras = count(ctx, p.id, "NOT (type = 1 AND grp = ?)", arrayOf(grp))
        log("FLUXO 3) categoria \"$grp\" ($key): $naCategoria filmes, 5 apagados do aparelho")
        t0 = System.currentTimeMillis()
        Repository.refreshAll()
        log("FLUXO 3) atualização só dessa categoria em ${secs(t0)}: ${count(ctx, p.id, "type = 1 AND grp = ?", arrayOf(grp))} filmes nela, ${count(ctx, p.id)} linhas no total")
        assertEquals("a categoria não voltou inteira", naCategoria, count(ctx, p.id, "type = 1 AND grp = ?", arrayOf(grp)))
        assertEquals("mexeu em outras categorias", outras, count(ctx, p.id, "NOT (type = 1 AND grp = ?)", arrayOf(grp)))
        assertTrue(prefs.getString("listparts_${p.id}", "")!!.contains(JSONObject(partsJson).getJSONArray(key).getString(0)))

        // 4) a TV abre o app de novo logo em seguida: conferido há menos de 10 min, não pergunta nada
        val antes = Repository.playlists.value.first { it.id == p.id }.lastUpdated
        Repository.refreshAll(onlyStale = true)
        assertEquals(antes, Repository.playlists.value.first { it.id == p.id }.lastUpdated)
        log("FLUXO 4) abriu de novo logo em seguida: nada conferido (ok)")
    }
}
