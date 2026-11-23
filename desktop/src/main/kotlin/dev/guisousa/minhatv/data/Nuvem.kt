package dev.guisousa.minhatv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Sincroniza com a conta W4LLE (o mesmo Supabase do site): onde parou, Minha lista e "já assisti".
 * Assistiu na TV, abre no celular (ou no site) e está no mesmo ponto.
 *
 * Usa as mesmas tabelas e o mesmo formato do site W4LLE, que identifica tudo pelo código do TMDB:
 * - watch_progress: media_id (TMDB), media_type ("movie" | "tv"), season/episode, playback_time e duration
 *   em segundos, percentage, updated_at;
 * - user_lists: list_type "watchlist" (Minha lista) ou "watched" (já assisti; episódio com season/episode).
 * Título sem código do TMDB fica só no aparelho (o site espera um número em media_id).
 *
 * Regras: o mais recente vence (updated_at da nuvem x hora em que o aparelho guardou); o que muda no aparelho
 * sobe na hora (Minha lista, visto) ou a cada poucos segundos (onde parou). Sem conta, nada disso roda.
 *
 * Copiado do app da TV (o mesmo arquivo vai na TV, no celular e no Windows); cada um liga o seu armazenamento em [Local].
 */
object Nuvem {

    /** O que o app guarda no aparelho (Repository da TV, LocalStore do celular). */
    interface Local {
        /** Catálogo e mapa do TMDB carregados (antes disso nada é sincronizado, para não perder a 1ª junção). */
        fun pronto(): Boolean
        /** Código do TMDB a partir do nosso id (filme / série), e o contrário. */
        fun tmdbDoFilme(id: Long): Long?
        fun tmdbDaSerie(id: Long): Long?
        fun filmeDoTmdb(tmdb: Long): Long?
        fun serieDoTmdb(tmdb: Long): Long?
        /** Nome e capa (endereço completo) para o site mostrar. */
        fun tituloECapa(url: String): Pair<String, String?>?
        /** Onde parou: posição e duração em ms, e quando foi guardado (ms). null = não começou. */
        fun onde(url: String): Triple<Long, Long, Long>?
        /** Aplica o que veio da nuvem (sem mandar de volta). */
        fun aplicarOnde(url: String, posicaoMs: Long, duracaoMs: Long, emMs: Long)
        fun favoritos(): Set<String>
        fun aplicarFavorito(url: String, ligado: Boolean)
        fun aplicarVisto(url: String)
        /** Filmes/episódios do "Continuar assistindo" (para subir no 1º login). */
        fun recentesVod(): List<String>
        /** O que veio da nuvem entra no "Continuar assistindo" (o mais recente primeiro). */
        fun aplicarRecentes(urls: List<String>)
        /** Guarda pequenas informações da sincronização (o que já está na nuvem). */
        fun ler(chave: String): String?
        fun gravar(chave: String, valor: String?)
    }

    private const val F = "v2:f:"
    private const val E = "v2:e:"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var local: Local? = null

    private val _ultima = MutableStateFlow(0L)
    /** Última sincronização completa (ms; 0 = nunca). */
    val ultima: StateFlow<Long> = _ultima
    private val _sincronizando = MutableStateFlow(false)
    val sincronizando: StateFlow<Boolean> = _sincronizando

    fun ligar(l: Local) {
        local = l
        _ultima.value = l.ler("nuvem_ultima")?.toLongOrNull() ?: 0L
    }

    // ---------- url <-> TMDB ----------

    /** O que a url é na nuvem: (media_id, media_type, season, episode). */
    private data class Item(val tmdb: Long, val tipo: String, val temporada: Int?, val episodio: Int?)

    private fun itemDe(l: Local, url: String): Item? = when {
        url.startsWith(F) -> url.removePrefix(F).toLongOrNull()?.let { l.tmdbDoFilme(it) }?.let { Item(it, "movie", null, null) }
        url.startsWith(E) -> {
            val p = url.removePrefix(E).split(':')
            val tmdb = p.getOrNull(0)?.toLongOrNull()?.let { l.tmdbDaSerie(it) }
            when {
                tmdb == null -> null
                p.size >= 3 -> Item(tmdb, "tv", p[1].toIntOrNull(), p[2].toIntOrNull())
                else -> Item(tmdb, "tv", null, null) // a série inteira (Minha lista)
            }
        }
        else -> null
    }

    private fun urlDe(l: Local, tmdb: Long, tipo: String, temporada: Int?, episodio: Int?): String? = when (tipo) {
        "movie" -> l.filmeDoTmdb(tmdb)?.let { "$F$it" }
        "tv" -> l.serieDoTmdb(tmdb)?.let { sid -> if (temporada != null && episodio != null) "$E$sid:$temporada:$episodio" else "$E$sid" }
        else -> null
    }

    /** "https://image.tmdb.org/t/p/w342/abc.jpg" -> "/abc.jpg" (o site monta o endereço sozinho). */
    private fun capaTmdb(url: String?): String? = url?.let { Regex("""image\.tmdb\.org/t/p/[^/]+(/[^/?]+)""").find(it)?.groupValues?.get(1) }

    // ---------- HTTP (Supabase REST) ----------

    private suspend fun cabecalhos(): Pair<String, String>? {
        val token = Conta.accessToken() ?: return null
        val uid = Conta.usuario.value?.id ?: return null
        return token to uid
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun pedir(token: String, metodo: String, caminho: String, corpo: String? = null): String? {
        val b = Request.Builder().url(Conta.SUPABASE_URL + "/rest/v1/" + caminho)
            .header("apikey", Conta.ANON_KEY).header("Authorization", "Bearer $token")
            .header("Prefer", "return=minimal")
        val body = corpo?.toRequestBody("application/json".toMediaType())
        when (metodo) {
            "GET" -> b.get()
            "POST" -> b.post(body!!)
            "PATCH" -> b.patch(body!!)
            "DELETE" -> b.delete()
        }
        Conta.http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Supabase $metodo ${caminho.substringBefore('?')}: HTTP ${r.code}")
            return r.body?.string()
        }
    }

    private fun filtroItem(uid: String, it: Item) = buildString {
        append("user_id=eq.").append(q(uid)).append("&media_id=eq.").append(it.tmdb)
        if (it.temporada != null && it.episodio != null) append("&season=eq.").append(it.temporada).append("&episode=eq.").append(it.episodio)
        else append("&season=is.null&episode=is.null")
    }

    private val iso = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private fun agoraIso(ms: Long = System.currentTimeMillis()) = iso.get()!!.format(java.util.Date(ms))

    /** "2026-11-23T02:56:35.123456+00:00" -> ms. */
    internal fun msDe(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return runCatching {
            val t = s.replace(' ', 'T')
            val base = t.substring(0, 19)
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            var ms = f.parse(base)!!.time
            val frac = Regex("""\.(\d+)""").find(t.substring(19))?.groupValues?.get(1)
            if (frac != null) ms += frac.take(3).padEnd(3, '0').toLong()
            val fuso = Regex("""([+-])(\d{2}):?(\d{2})$""").find(t)
            if (fuso != null) {
                val min = fuso.groupValues[2].toInt() * 60 + fuso.groupValues[3].toInt()
                ms -= (if (fuso.groupValues[1] == "+") 1 else -1) * min * 60_000L
            }
            ms
        }.getOrDefault(0)
    }

    // ---------- Baixar e aplicar ----------

    /**
     * Baixa tudo da conta e aplica no aparelho; sobe o que só está aqui (1º login, ou mudou sem internet).
     * [forcar] ignora o intervalo mínimo de 1 min entre sincronizações.
     */
    fun sincronizar(forcar: Boolean = false) {
        val l = local ?: return
        if (!Conta.conectado || !l.pronto()) return
        if (!forcar && System.currentTimeMillis() - _ultima.value < 60_000) return
        scope.launch {
            lock.withLock {
                _sincronizando.value = true
                try {
                    baixar(l)
                    val agora = System.currentTimeMillis()
                    l.gravar("nuvem_ultima", agora.toString())
                    _ultima.value = agora
                } catch (e: Exception) {
                    log("sincronização falhou: ${e.message}")
                } finally {
                    _sincronizando.value = false
                }
            }
        }
    }

    private suspend fun baixar(l: Local) {
        val (token, uid) = cabecalhos() ?: return
        val primeira = l.ler("nuvem_uid") != uid // conta nova neste aparelho: junta o que já estava aqui

        // onde parou
        val prog = pedir(token, "GET", "watch_progress?select=media_id,media_type,season,episode,playback_time,duration,percentage,updated_at&user_id=eq.${q(uid)}&order=updated_at.desc&limit=1000")
        val naNuvem = HashSet<String>()
        val aplicados = ArrayList<String>() // em ordem: o mais recente primeiro
        (json.parseToJsonElement(prog ?: "[]") as? JsonArray)?.forEach { el ->
            val o = el.jsonObject
            val tmdb = o.str("media_id")?.toLongOrNull() ?: return@forEach
            val url = urlDe(l, tmdb, o.str("media_type") ?: "", o.int("season"), o.int("episode")) ?: return@forEach
            naNuvem += url
            val em = msDe(o.str("updated_at"))
            val pos = ((o.num("playback_time") ?: 0.0) * 1000).toLong()
            val dur = ((o.num("duration") ?: 0.0) * 1000).toLong()
            val pct = o.num("percentage") ?: 0.0
            val aqui = l.onde(url)
            if (aqui == null || em > aqui.third + 2_000) {
                if (pct >= 95) l.aplicarVisto(url)
                else if (pos > 5_000 && dur > 60_000) { l.aplicarOnde(url, pos, dur, em); aplicados += url }
            }
        }
        if (aplicados.isNotEmpty()) l.aplicarRecentes(aplicados)
        // listas (Minha lista e já assisti)
        val listas = pedir(token, "GET", "user_lists?select=media_id,media_type,list_type,season,episode&user_id=eq.${q(uid)}&limit=5000")
        val favNuvem = HashSet<String>()
        (json.parseToJsonElement(listas ?: "[]") as? JsonArray)?.forEach { el ->
            val o = el.jsonObject
            val tmdb = o.str("media_id")?.toLongOrNull() ?: return@forEach
            val tipo = o.str("media_type") ?: return@forEach
            when (o.str("list_type")) {
                "watchlist" -> urlDe(l, tmdb, tipo, null, null)?.let { favNuvem += it }
                "watched" -> urlDe(l, tmdb, tipo, o.int("season"), o.int("episode"))?.let { l.aplicarVisto(it) }
            }
        }
        // Minha lista: o que está na nuvem e não aqui entra; o que saiu da nuvem (e já tinha subido) sai daqui;
        // o que só está aqui (novo, sem internet ou 1º login) sobe
        val antes = l.ler("nuvem_favs")?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()
        val aqui = l.favoritos().filter { it.startsWith(F) || (it.startsWith(E) && it.count { c -> c == ':' } == 2) }.toSet()
        for (u in favNuvem - aqui) l.aplicarFavorito(u, true)
        for (u in aqui - favNuvem) {
            if (!primeira && u in antes) l.aplicarFavorito(u, false) else subirFavorito(token, uid, l, u, true)
        }
        l.gravar("nuvem_favs", (favNuvem + aqui.filter { itemDe(l, it) != null }).joinToString("\n"))
        // 1º login neste aparelho: sobe o "Continuar assistindo" daqui que a nuvem não tem
        if (primeira) {
            for (u in l.recentesVod()) if (u !in naNuvem) l.onde(u)?.let { (pos, dur, em) -> subirOnde(token, uid, l, u, pos, dur, em) }
            l.gravar("nuvem_uid", uid)
        }
    }

    // ---------- Subir ----------

    private val pendentesOnde = ConcurrentHashMap<String, Job>()

    /** Onde parou mudou: sobe em até 10 s (o player chama várias vezes; só a última vai). */
    fun onde(url: String, posicaoMs: Long, duracaoMs: Long) {
        val l = local ?: return
        if (!Conta.conectado || itemDe(l, url) == null) return
        pendentesOnde.remove(url)?.cancel()
        pendentesOnde[url] = scope.launch {
            delay(10_000)
            pendentesOnde.remove(url)
            runCatching {
                val (token, uid) = cabecalhos() ?: return@launch
                subirOnde(token, uid, l, url, posicaoMs, duracaoMs, System.currentTimeMillis())
            }.onFailure { log("onde parou não subiu: ${it.message}") }
        }
    }

    private fun subirOnde(token: String, uid: String, l: Local, url: String, posMs: Long, durMs: Long, em: Long) {
        val it = itemDe(l, url) ?: return
        val (titulo, capa) = l.tituloECapa(url) ?: ("" to null)
        val seg = posMs / 1000.0
        val dur = (durMs / 1000.0).coerceAtLeast(1.0)
        val existe = pedir(token, "GET", "watch_progress?select=id&${filtroItem(uid, it)}&limit=1")
        val id = (json.parseToJsonElement(existe ?: "[]") as? JsonArray)?.firstOrNull()?.jsonObject?.str("id")
        val dados = buildJsonObject {
            put("playback_time", seg); put("duration", dur); put("percentage", (seg / dur * 100).coerceIn(0.0, 100.0))
            put("updated_at", agoraIso(em))
            if (titulo.isNotBlank()) put("title", titulo)
            capaTmdb(capa)?.let { c -> put("poster_path", c) }
        }
        if (id != null) pedir(token, "PATCH", "watch_progress?id=eq.$id", dados.toString())
        else pedir(token, "POST", "watch_progress", JsonObject(dados + buildJsonObject {
            put("user_id", uid); put("media_id", it.tmdb.toString()); put("media_type", it.tipo)
            put("season", it.temporada?.let { s -> JsonPrimitive(s) } ?: kotlinx.serialization.json.JsonNull)
            put("episode", it.episodio?.let { e -> JsonPrimitive(e) } ?: kotlinx.serialization.json.JsonNull)
            if (titulo.isBlank()) put("title", "Mídia ${it.tmdb}")
        }).toString())
    }

    /** Minha lista mudou no aparelho: sobe na hora. */
    fun favorito(url: String, ligado: Boolean) {
        val l = local ?: return
        if (!Conta.conectado) return
        scope.launch {
            runCatching {
                val (token, uid) = cabecalhos() ?: return@launch
                subirFavorito(token, uid, l, url, ligado)
                val antes = l.ler("nuvem_favs")?.split('\n')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
                if (ligado) antes += url else antes -= url
                l.gravar("nuvem_favs", antes.joinToString("\n"))
            }.onFailure { log("Minha lista não subiu: ${it.message}") }
        }
    }

    private fun subirFavorito(token: String, uid: String, l: Local, url: String, ligado: Boolean) {
        val it = itemDe(l, url)?.copy(temporada = null, episodio = null) ?: return
        val filtro = "user_id=eq.${q(uid)}&media_id=eq.${it.tmdb}&list_type=eq.watchlist"
        if (!ligado) { pedir(token, "DELETE", "user_lists?$filtro"); return }
        val existe = pedir(token, "GET", "user_lists?select=id&$filtro&limit=1")
        if ((json.parseToJsonElement(existe ?: "[]") as? JsonArray)?.isNotEmpty() == true) return
        val (titulo, capa) = l.tituloECapa(url) ?: ("" to null)
        pedir(token, "POST", "user_lists", buildJsonObject {
            put("user_id", uid); put("media_id", it.tmdb.toString()); put("media_type", it.tipo); put("list_type", "watchlist")
            put("title", titulo.ifBlank { "Mídia ${it.tmdb}" }); capaTmdb(capa)?.let { c -> put("poster_path", c) }
        }.toString())
    }

    /** Marcou ou desmarcou "já assisti" no aparelho: sobe na hora. */
    fun visto(url: String, visto: Boolean) {
        val l = local ?: return
        if (!Conta.conectado) return
        scope.launch {
            runCatching {
                val (token, uid) = cabecalhos() ?: return@launch
                val it = itemDe(l, url) ?: return@launch
                val filtro = filtroItem(uid, it) + "&list_type=eq.watched"
                if (!visto) { pedir(token, "DELETE", "user_lists?$filtro"); return@launch }
                val existe = pedir(token, "GET", "user_lists?select=id&$filtro&limit=1")
                if ((json.parseToJsonElement(existe ?: "[]") as? JsonArray)?.isNotEmpty() == true) return@launch
                val (titulo, capa) = l.tituloECapa(url) ?: ("" to null)
                pedir(token, "POST", "user_lists", buildJsonObject {
                    put("user_id", uid); put("media_id", it.tmdb.toString()); put("media_type", it.tipo); put("list_type", "watched")
                    put("season", it.temporada?.let { s -> JsonPrimitive(s) } ?: kotlinx.serialization.json.JsonNull)
                    put("episode", it.episodio?.let { e -> JsonPrimitive(e) } ?: kotlinx.serialization.json.JsonNull)
                    put("title", titulo.ifBlank { "Mídia ${it.tmdb}" }); capaTmdb(capa)?.let { c -> put("poster_path", c) }
                }.toString())
            }.onFailure { log("visto não subiu: ${it.message}") }
        }
    }

    /** Saiu da conta: esquece o que sabia da nuvem (o que está no aparelho fica). */
    fun esquecer() {
        val l = local ?: return
        pendentesOnde.values.forEach { it.cancel() }
        pendentesOnde.clear()
        l.gravar("nuvem_uid", null); l.gravar("nuvem_favs", null); l.gravar("nuvem_ultima", null)
        _ultima.value = 0
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

    private fun log(msg: String) = Log.w("nuvem: $msg")
}
