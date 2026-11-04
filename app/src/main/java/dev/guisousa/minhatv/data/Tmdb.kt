package dev.guisousa.minhatv.data

import dev.guisousa.minhatv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Capas, fundos e sinopses oficiais do TMDB (só metadados). As listas costumam trazer
 * um quadro do episódio no lugar da capa da série; aqui buscamos o pôster de verdade.
 * Tudo fica guardado no banco: cada título é procurado uma vez só (sem resultado: tenta de novo em 7 dias).
 */
object Tmdb {
    class Info(val poster: String?, val backdrop: String?, val overview: String?)

    val enabled get() = BuildConfig.TMDB_KEY.isNotBlank()

    private val client by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mem = ConcurrentHashMap<String, Info>()
    private val gate = Semaphore(4)
    private val yearRe = Regex("""\((19|20)\d{2}\)|\b(19|20)\d{2}\b""")

    /** [tv] = série; [title] = nome limpo, pode ter "(2025)". */
    suspend fun lookup(title: String, tv: Boolean): Info? {
        if (!enabled || title.isBlank()) return null
        val key = (if (tv) "t:" else "m:") + Normalize.name(title)
        mem[key]?.let { return it.takeIf { i -> i.poster != null || i.backdrop != null } }
        return withContext(Dispatchers.IO) {
            val cached = runCatching { Repository.db.tmdbGet(key) }.getOrNull()
            if (cached != null) {
                val fresh = cached[0] != null || System.currentTimeMillis() - (cached[3]?.toLong() ?: 0) < 7 * 86_400_000L
                if (fresh) return@withContext Info(cached[0], cached[1], cached[2]).also { mem[key] = it }.takeIf { it.poster != null || it.backdrop != null }
            }
            val info = gate.withPermit { runCatching { fetch(title, tv) }.getOrNull() } ?: return@withContext null
            runCatching { Repository.db.tmdbPut(key, info.poster, info.backdrop, info.overview) }
            mem[key] = info
            info.takeIf { it.poster != null || it.backdrop != null }
        }
    }

    /** Extras da tela de detalhes: fundo sem texto (o "backdrop" padrão às vezes é uma colagem com letreiro) e títulos parecidos. */
    class Extra(val backdrop: String?, val related: List<String>)
    private val extraMem = ConcurrentHashMap<String, Extra>()

    suspend fun extra(title: String, tv: Boolean): Extra? {
        if (!enabled || title.isBlank()) return null
        val key = (if (tv) "t:" else "m:") + Normalize.name(title)
        extraMem[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                runCatching {
                    val hit = searchHit(title, tv) ?: return@runCatching null
                    val id = hit["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                    val kind = if (tv) "tv" else "movie"
                    // fundos sem idioma = imagem limpa, sem título escrito por cima
                    val images = get("https://api.themoviedb.org/3/$kind/$id/images", "include_image_language" to "null")
                    val clean = images?.get("backdrops")?.jsonArray?.firstOrNull()?.jsonObject?.get("file_path")?.jsonPrimitive?.contentOrNull
                    val recs = get("https://api.themoviedb.org/3/$kind/$id/recommendations", "language" to "pt-BR")
                        ?.get("results")?.jsonArray.orEmpty().mapNotNull { r ->
                            r.jsonObject[if (tv) "name" else "title"]?.jsonPrimitive?.contentOrNull
                        }
                    Extra(clean?.let { "https://image.tmdb.org/t/p/w1280$it" }, recs)
                }.getOrNull()
            }?.also { extraMem[key] = it }
        }
    }

    /** Título de filme do TMDB: nome em português, nome original e ano. */
    class Title(val title: String, val original: String?, val year: Int?)

    /** Filmes em alta no Brasil agora (populares na região BR + mais vistos da semana), em ordem de popularidade. */
    fun popularBr(): List<Title> {
        if (!enabled) return emptyList()
        val pages = listOf(
            "https://api.themoviedb.org/3/movie/popular" to arrayOf("language" to "pt-BR", "region" to "BR", "page" to "1"),
            "https://api.themoviedb.org/3/movie/popular" to arrayOf("language" to "pt-BR", "region" to "BR", "page" to "2"),
            "https://api.themoviedb.org/3/trending/movie/week" to arrayOf("language" to "pt-BR"),
            "https://api.themoviedb.org/3/movie/now_playing" to arrayOf("language" to "pt-BR", "region" to "BR"),
        )
        val seen = HashSet<String>()
        return pages.flatMap { (url, params) ->
            runCatching { get(url, *params)?.get("results")?.jsonArray.orEmpty() }.getOrDefault(emptyList())
        }.mapNotNull { r ->
            val o = r.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: return@mapNotNull null
            val original = o["original_title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != title }
            val year = o["release_date"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull()
            Title(title, original, year)
        }
    }

    private fun get(base: String, vararg params: Pair<String, String>): JsonObject? {
        val url = base.toHttpUrl().newBuilder().addQueryParameter("api_key", BuildConfig.TMDB_KEY)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            return json.parseToJsonElement(r.body!!.string()).jsonObject
        }
    }

    private fun searchHit(title: String, tv: Boolean): JsonObject? {
        val year = yearRe.find(title)?.value?.filter { it.isDigit() }
        val name = yearRe.replace(title, " ").replace(Regex("""\s+"""), " ").trim()
        fun search(withYear: Boolean): JsonObject? = get(
            "https://api.themoviedb.org/3/search/${if (tv) "tv" else "movie"}",
            *listOfNotNull("language" to "pt-BR", "query" to name, if (withYear && year != null) (if (tv) "first_air_date_year" else "year") to year else null).toTypedArray(),
        )?.get("results")?.jsonArray?.firstOrNull()?.jsonObject
        return search(true) ?: if (year != null) search(false) else null
    }

    private fun fetch(title: String, tv: Boolean): Info {
        val year = yearRe.find(title)?.value?.filter { it.isDigit() }
        val name = yearRe.replace(title, " ").replace(Regex("""\s+"""), " ").trim()
        fun search(withYear: Boolean): JsonObject? {
            val url = "https://api.themoviedb.org/3/search/${if (tv) "tv" else "movie"}".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", BuildConfig.TMDB_KEY)
                .addQueryParameter("language", "pt-BR")
                .addQueryParameter("query", name)
                .apply { if (withYear && year != null) addQueryParameter(if (tv) "first_air_date_year" else "year", year) }
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("tmdb ${r.code}")
                val body = json.parseToJsonElement(r.body!!.string()).jsonObject
                return body["results"]?.jsonArray?.firstOrNull()?.jsonObject
            }
        }
        val hit = search(true) ?: if (year != null) search(false) else null
        fun img(field: String, size: String) = hit?.get(field)?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/$size$it" }
        return Info(img("poster_path", "w342"), img("backdrop_path", "w1280"), hit?.get("overview")?.jsonPrimitive?.contentOrNull?.ifBlank { null })
    }
}
