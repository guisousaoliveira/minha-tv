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
