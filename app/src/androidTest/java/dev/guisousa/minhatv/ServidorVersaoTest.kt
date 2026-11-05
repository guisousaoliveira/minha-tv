package dev.guisousa.minhatv

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.guisousa.minhatv.data.Normalize
import dev.guisousa.minhatv.data.Xtream
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Teste no emulador contra um servidor Xtream de verdade (só roda pelo workflow "Teste no emulador",
 * que passa servidor, usuário e série como argumentos; sem eles o teste é pulado).
 * Faz o mesmo caminho do app: acha a série, pede os episódios e lê a versão de cada um como a tela lê.
 */
@RunWith(AndroidJUnit4::class)
class ServidorVersaoTest {
    private val ua = "VLC/3.0.20 LibVLC/3.0.20"

    private fun open(url: String, block: (java.io.InputStream) -> Any?): Any? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", ua)
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        return c.inputStream.use(block)
    }

    /** Mesma regra do app (Repository.versionOf) sobre o nome guardado e limpo. */
    private fun version(name: String): String? {
        val d = Normalize.display(Normalize.clean(name))
        val t = listOfNotNull(d.tag, d.title).joinToString(" ").lowercase()
        return when {
            Regex("""legend|\bleg\b|\bsub(bed|titled)?\b""").containsMatchIn(t) -> "leg"
            Regex("""dublad|\bdub\b|dual|nacional""").containsMatchIn(t) -> "dub"
            else -> null
        }
    }

    @Test
    fun serieTemLegendadoEDublado() {
        val args = InstrumentationRegistry.getArguments()
        val server = args.getString("server").orEmpty().trimEnd('/')
        assumeTrue("sem servidor", server.isNotEmpty())
        val user = args.getString("user").orEmpty()
        val pass = args.getString("pass").orEmpty().ifEmpty { user }
        val wanted = args.getString("series") ?: "Ted Lasso"
        val x = Xtream(server, user, pass, ua) { u, b -> open(u, b) }

        val covers = ArrayList<dev.guisousa.minhatv.data.Entry>()
        x.seriesCovers("teste") { e -> if (Normalize.display(e.name).title.contains(wanted, ignoreCase = true)) covers += e }
        log("séries achadas com \"$wanted\": ${covers.size} · ${covers.joinToString(" | ") { it.name + " #" + Xtream.stubSeriesId(it.url) }}")
        assertTrue("série \"$wanted\" não existe no servidor", covers.isNotEmpty())

        var leg = 0; var dub = 0; var none = 0
        for (c in covers) {
            val eps = x.episodes(Xtream.stubSeriesId(c.url), c.seriesName ?: c.name, c.group, c.logo)
            log("\"${c.name}\": ${eps.size} episódios · servidor: ${x.rawSample.joinToString(" | ")}")
            eps.take(6).forEach { log("  ${it.season}x${it.episode} → ${it.name} → versão ${version(it.name)}") }
            for (e in eps) when (version(e.name)) { "leg" -> leg++; "dub" -> dub++; else -> none++ }
        }
        val summary = "RESULTADO: $leg legendados, $dub dublados, $none sem marca"
        log(summary)
        assertTrue(summary, leg > 0 && dub > 0)
    }

    /** Filmes com o nome pedido: como vêm do servidor e quais o app junta como "mesmo filme" (fontes alternativas). */
    @Test
    fun filmesParecidos() {
        val args = InstrumentationRegistry.getArguments()
        val server = args.getString("server").orEmpty().trimEnd('/')
        val wanted = args.getString("movies").orEmpty().split(',').map { Normalize.name(it.trim()) }.filter { it.isNotEmpty() }
        assumeTrue("sem servidor ou filmes", server.isNotEmpty() && wanted.isNotEmpty())
        val user = args.getString("user").orEmpty()
        val pass = args.getString("pass").orEmpty().ifEmpty { user }
        val x = Xtream(server, user, pass, ua) { u, b -> open(u, b) }
        val found = ArrayList<dev.guisousa.minhatv.data.Entry>()
        x.movies { e -> val n = Normalize.name(Normalize.display(e.name).title); if (wanted.any { n.contains(it) }) found += e }
        for (e in found.sortedBy { Normalize.display(it.name).title }) {
            val d = Normalize.display(Normalize.clean(e.name))
            val norm = Normalize.key(d.title)
            log("FILME \"${e.name}\" · tela \"${d.title}\" ${d.tag ?: ""} · grupo ${e.group} · capa ${e.logo?.substringAfterLast('/')} · chave $norm")
        }
        log("RESULTADO FILMES: ${found.size} achados")
    }

    /** O servidor diz a versão de cada categoria, e pedir uma categoria traz só os itens dela. */
    @Test
    fun versaoPorCategoria() {
        val args = InstrumentationRegistry.getArguments()
        val server = args.getString("server").orEmpty().trimEnd('/')
        assumeTrue("sem servidor", server.isNotEmpty())
        val user = args.getString("user").orEmpty()
        val pass = args.getString("pass").orEmpty().ifEmpty { user }
        val x = Xtream(server, user, pass, ua) { u, b -> open(u, b) }
        val info = x.login()
        val parts = info.parts
        log("VERSÕES: lista ${info.version} · ${parts?.size ?: 0} categorias")
        assumeTrue("servidor não manda versão por categoria", parts != null)
        val cats = x.categories("get_vod_categories")
        val (catId, catName) = cats.entries.first { parts!!.containsKey("vod:${it.key}") }
        var n = 0
        var fora = 0
        x.movies(catId, cats) { e -> n++; if (e.group != catName) fora++ }
        log("CATEGORIA \"$catName\" ($catId): $n filmes, $fora de outra categoria")
        assertTrue(n > 0 && fora == 0)
    }

    private fun log(s: String) { Log.i("MinhaTVTeste", s); println(s) }
}
