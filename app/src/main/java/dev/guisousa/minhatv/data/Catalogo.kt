package dev.guisousa.minhatv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// Catálogo pronto que o servidor monta (public/app/v2 do iptv-listas). O app só baixa e mostra:
// nada de ler playlist, juntar cópias ou procurar capa aqui.

@Serializable
class Fonte(val u: String, val q: String? = null, val h: Map<String, String>? = null)

@Serializable
class Canal(val id: Long, val n: String, val c: String = "", val l: String? = null, val e: String? = null, val f: List<Fonte> = emptyList())

@Serializable
class CardFilme(
    val id: Long, val t: String, val a: Int? = null, val p: String? = null, val v: String = "d", val ad: Long? = null,
    val b: String? = null, val g: List<String>? = null, val k: String? = null,
    /** Id da categoria principal (serviço ou gênero), quando o servidor manda. */
    val c: String? = null,
)

@Serializable
class CardSerie(
    val id: Long, val t: String, val a: Int? = null, val p: String? = null, val v: String = "d",
    val nt: Int = 0, val ne: Int = 0, val m: Long? = null,
    val b: String? = null, val g: List<String>? = null,
    /** Id da categoria principal (serviço ou gênero), quando o servidor manda. */
    val c: String? = null,
)

/** Categoria. No ao vivo, uma com [ids] (ex.: "Principais") é uma lista pronta de canais, nessa ordem. */
@Serializable
class Cat(val id: String, val nome: String, val ids: List<Long>? = null)

/** Ao vivo: as categorias com lista pronta de canais (Principais) vêm primeiro, as outras na ordem do servidor. */
fun ordemAoVivo(cats: List<Cat>): List<Cat> = cats.filter { it.ids != null } + cats.filter { it.ids == null }

/** Canais de uma lista pronta, na ordem dos ids, sem os que não existem e sem repetir. */
fun <T> canaisPorIds(ids: List<Long>, porId: Map<Long, T>): List<T> = ids.distinct().mapNotNull { porId[it] }

@Serializable
class Categorias(val canais: List<Cat> = emptyList(), val filmes: List<Cat> = emptyList(), val series: List<Cat> = emptyList())

@Serializable
class Versoes(val dub: List<Fonte> = emptyList(), val leg: List<Fonte> = emptyList())

/** Ficha de um filme ou série: capa, fundo, sinopse, gêneros e os links (por versão). */
@Serializable
class Ficha(
    val t: String = "", val a: Int? = null, val p: String? = null, val b: String? = null, val s: String? = null,
    val g: List<String>? = null, val v: Versoes? = null, val eps: List<JsonArray>? = null,
)

@Serializable
class LinhaItem(val tipo: String, val id: Long)

/** Fileira da tela inicial. tipo "f" (filmes, ids), "s" (séries, ids) ou "m" (misturado, itens). */
@Serializable
class Linha(val t: String, val tipo: String, val itens: List<LinhaItem>? = null, val ids: List<Long>? = null)

/** Ids que sumiram (cópias juntadas pelo TMDB no servidor) -> id atual. "f" filmes, "s" séries. */
@Serializable
class Apelidos(val f: Map<String, Long> = emptyMap(), val s: Map<String, Long> = emptyMap())

@Serializable
class Manifesto(val formato: Int = 0, val versao: String = "", val fichas: Int = 256, val partes: Map<String, String> = emptyMap())

/** Episódio dentro da ficha da série. */
class Episodio(val temporada: Int, val numero: Int, val versoes: Versoes)

@OptIn(ExperimentalSerializationApi::class)
class Catalogo(private val dir: File, private val http: OkHttpClient, private val base: String = BASE) {

    companion object {
        const val BASE = "https://badwolf.pages.dev/app/v2/"
        const val UA = "VLC/3.0.20 LibVLC/3.0.20"
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

        // ping a cada 10 s: uma conexão guardada que morreu (Wi-Fi da TV, roteador) é descoberta logo,
        // em vez de o pedido ficar 1 minuto esperando resposta que nunca vem
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** "fichas/f_007" para o filme/série com esse id. */
        fun fichaParte(serie: Boolean, id: Long, n: Int): String =
            "fichas/" + (if (serie) "s_" else "f_") + String.format(java.util.Locale.ROOT, "%03d", Math.floorMod(id, n.toLong()))

        /** Partes que o app usa (o servidor publica outras, como tmdb.json, que o app não lê). */
        private val PARTES_DO_APP = setOf("categorias", "canais", "inicio", "guia", "apelidos")
        fun usada(parte: String) = parte in PARTES_DO_APP || parte.startsWith("filmes/") || parte.startsWith("series/")

        fun episodios(f: Ficha): List<Episodio> = f.eps.orEmpty().mapNotNull { a ->
            runCatching {
                Episodio(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, json.decodeFromJsonElement(Versoes.serializer(), a[2]))
            }.getOrNull()
        }
    }

    init { dir.mkdirs() }

    private val hashesFile = File(dir, "hashes.json")
    private val hashSer = MapSerializer(String.serializer(), String.serializer())

    /** Parte -> hash do arquivo que está no disco. */
    private val hashes: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap(runCatching {
        hashesFile.inputStream().use { json.decodeFromStream(hashSer, it) }
    }.getOrDefault(emptyMap()))

    @Volatile var manifesto: Manifesto = runCatching {
        File(dir, "manifesto.json").inputStream().use { json.decodeFromStream(Manifesto.serializer(), it) }
    }.getOrDefault(Manifesto())
        private set

    private fun file(parte: String) = File(dir, parte.replace('/', '_') + ".json")

    fun has(parte: String) = file(parte).exists() && hashes[parte] != null

    private fun saveHashes() = synchronized(hashes) {
        val tmp = File(dir, "hashes.json.tmp")
        tmp.writeText(json.encodeToString(hashSer, HashMap(hashes)))
        tmp.renameTo(hashesFile) || run { hashesFile.delete(); tmp.renameTo(hashesFile) }
    }

    private fun get(path: String): ByteArray {
        val req = Request.Builder().url(base + path).header("User-Agent", UA).build()
        var erro: IOException? = null
        // 3 tentativas: falhou de rede, a próxima vai numa conexão nova
        repeat(3) { tentativa ->
            try {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code} em $path")
                    return r.body?.bytes() ?: throw IOException("resposta vazia em $path")
                }
            } catch (e: IOException) {
                erro = e
                if (e.message?.startsWith("HTTP 404") == true) throw e
                android.util.Log.w("MinhaTV", "catálogo: $path falhou (tentativa ${tentativa + 1} de 3): $e")
                http.connectionPool.evictAll()
                if (tentativa < 2) Thread.sleep(500L * (tentativa + 1))
            }
        }
        throw erro ?: IOException("falhou $path")
    }

    private fun download(parte: String, hash: String): ByteArray {
        val bytes = get("$parte.json")
        val f = file(parte)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        synchronized(hashes) { hashes[parte] = hash }
        return bytes
    }

    /**
     * Confere o manifesto e baixa só as partes que mudaram (fichas ficam para quando forem abertas).
     * Devolve true se algo mudou.
     */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val bytes = get("manifesto.json")
        val novo = json.decodeFromString(Manifesto.serializer(), bytes.decodeToString())
        if (novo.formato != 2) throw IOException("formato ${novo.formato} desconhecido")
        val faltando = novo.partes.filter { (p, h) -> usada(p) && (hashes[p] != h || !file(p).exists()) }
        val limite = Semaphore(4)
        coroutineScope {
            faltando.map { (p, h) -> async { limite.withPermit { download(p, h) } } }.awaitAll()
        }
        // ficha que mudou: apaga, baixa de novo quando abrir
        var fichasMudaram = false
        synchronized(hashes) {
            for (p in hashes.keys.toList()) {
                val h = novo.partes[p]
                if (h == null || (p.startsWith("fichas/") && h != hashes[p])) {
                    file(p).delete(); hashes.remove(p); fichasMudaram = true
                }
            }
        }
        if (fichasMudaram) clearFichaCache()
        saveHashes()
        // Só recarrega o catálogo (telas) se mudou alguma parte que aparece na tela. Ficha que mudou (links)
        // já foi apagada acima e é baixada de novo quando abrir: não precisa reler 16 MB de JSON por isso.
        val mudou = faltando.isNotEmpty()
        File(dir, "manifesto.json").writeBytes(bytes)
        manifesto = novo
        mudou
    }

    fun <T> read(parte: String, ser: KSerializer<T>): T? = runCatching {
        file(parte).takeIf { it.exists() }?.inputStream()?.buffered()?.use { json.decodeFromStream(ser, it) }
    }.onFailure { android.util.Log.w("MinhaTV", "catálogo: não li $parte", it) }.getOrNull()

    // ---- fichas (baixadas na hora de abrir, guardadas no disco) ----

    private val fichaSer = MapSerializer(String.serializer(), Ficha.serializer())
    private val fichaCache = object : LinkedHashMap<String, Map<String, Ficha>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Ficha>>?) = size > 6
    }

    /** Por que a última ficha pedida não veio (para mostrar na tela). */
    @Volatile var fichaErro: String? = null

    suspend fun ficha(serie: Boolean, id: Long): Ficha? = withContext(Dispatchers.IO) {
        fichaErro = null
        val parte = fichaParte(serie, id, manifesto.fichas.coerceAtLeast(1))
        synchronized(fichaCache) { fichaCache[parte] }?.get(id.toString())?.let { return@withContext it }
        val hash = manifesto.partes[parte] ?: "?"
        // baixa (se mudou ou não tem) e lê direto do que veio, sem depender do arquivo (a sincronização
        // pode apagar a ficha velha no meio). Se o item não está na ficha guardada, baixa de novo uma vez.
        fun baixar(): Map<String, Ficha>? = runCatching {
            val bytes = download(parte, hash); saveHashes()
            json.decodeFromString(fichaSer, bytes.decodeToString())
        }.onFailure {
            android.util.Log.w("MinhaTV", "catálogo: ficha $parte não baixou", it)
            fichaErro = if (it is IOException) "não consegui baixar a ficha (${it.message ?: "sem internet?"})" else "ficha com defeito no servidor"
        }.getOrNull()
        val atual = hashes[parte] == hash
        val noDisco = if (file(parte).exists()) read(parte, fichaSer) else null
        // sem internet, a ficha velha do disco ainda serve (os links podem ter mudado, mas quase sempre tocam)
        val mapa = noDisco?.takeIf { atual && it.containsKey(id.toString()) } ?: baixar() ?: noDisco ?: return@withContext null
        synchronized(fichaCache) { fichaCache[parte] = mapa }
        mapa[id.toString()].also { if (it == null && fichaErro == null) fichaErro = "este título não está mais no servidor (atualize a lista)" }
    }

    fun clearFichaCache() = synchronized(fichaCache) { fichaCache.clear() }

    // ---- partes fixas ----

    fun categorias() = read("categorias", Categorias.serializer())
    fun canais() = read("canais", ListSerializer(Canal.serializer()))
    fun filmes(cat: String) = read("filmes/$cat", ListSerializer(CardFilme.serializer()))
    fun series(cat: String) = read("series/$cat", ListSerializer(CardSerie.serializer()))
    fun inicio() = read("inicio", ListSerializer(Linha.serializer()))
    fun apelidos() = read("apelidos", Apelidos.serializer())

    /** Guia: id do canal -> programas (horários em ms). */
    fun guia(): Map<String, List<Programme>> {
        val raw = read("guia", MapSerializer(String.serializer(), ListSerializer(JsonArray.serializer()))) ?: return emptyMap()
        return raw.mapValues { (_, progs) ->
            progs.mapNotNull { p ->
                runCatching {
                    Programme(
                        p[0].jsonPrimitive.long * 1000, p[1].jsonPrimitive.long * 1000,
                        p[2].jsonPrimitive.content, p.getOrNull(3)?.jsonPrimitive?.content,
                    )
                }.getOrNull()
            }
        }
    }

}
