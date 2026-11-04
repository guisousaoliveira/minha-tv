package dev.guisousa.minhatv.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder

/**
 * Servidor no padrão Xtream Codes (player_api.php), o mesmo que TiviMate e Smarters usam.
 * Canais e filmes vêm de uma vez; das séries vêm só as capas, e os episódios de cada uma
 * são pedidos ao servidor quando a série é aberta (get_series_info).
 */
class Xtream(
    val base: String,
    val user: String,
    val pass: String,
    /** O mesmo User-Agent da conexão com a API vai para o player (servidores Xtream costumam amarrar o acesso a ele). */
    private val userAgent: String?,
    private val open: (String, (InputStream) -> Any?) -> Any?,
) {

    private val u = enc(user)
    private val p = enc(pass)

    private fun api(action: String? = null, extra: String = "") =
        "$base/player_api.php?username=$u&password=$p" + (action?.let { "&action=$it" } ?: "") + extra

    /**
     * Confere usuário e senha (o servidor responde auth = 0 quando estão errados).
     * Devolve a versão das listas (server_info.list_version), quando o servidor informa:
     * se for igual à da última importação, não precisa baixar tudo de novo.
     */
    fun login(): String? {
        var auth: String? = null
        var version: String? = null
        open(api()) { input ->
            reader(input).use { r ->
                if (r.peek() != JsonToken.BEGIN_OBJECT) throw IOException("o servidor não respondeu como um servidor Xtream")
                r.beginObject()
                while (r.hasNext()) {
                    val section = r.nextName()
                    if ((section == "user_info" || section == "server_info") && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject()
                        while (r.hasNext()) {
                            val key = r.nextName()
                            when {
                                section == "user_info" && key == "auth" -> auth = r.value()
                                section == "server_info" && key == "list_version" -> version = r.value()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    } else r.skipValue()
                }
            }
            null
        }
        if (auth == "0") throw IOException("usuário ou senha não aceitos pelo servidor")
        return version?.trim()?.ifBlank { null }
    }

    /** category_id -> nome da categoria. */
    fun categories(action: String): Map<String, String> {
        val out = HashMap<String, String>()
        eachObject(api(action)) { o -> o["category_id"]?.let { id -> out[id] = o["category_name"] ?: "" } }
        return out
    }

    fun live(emit: (Entry) -> Unit) {
        val cats = categories("get_live_categories")
        eachObject(api("get_live_streams")) { o ->
            val id = o["stream_id"] ?: return@eachObject
            emit(Entry(
                name = o["name"] ?: return@eachObject,
                url = o["direct_source"].httpOrNull() ?: "$base/live/$u/$p/$id.ts",
                group = cats[o["category_id"]] ?: "",
                logo = o["stream_icon"].orNullIfBlank(),
                tvgId = o["epg_channel_id"].orNullIfBlank(),
                type = ContentType.LIVE,
                userAgent = uaFor(o["direct_source"]),
            ))
        }
    }

    fun movies(emit: (Entry) -> Unit) {
        val cats = categories("get_vod_categories")
        eachObject(api("get_vod_streams")) { o ->
            val id = o["stream_id"] ?: return@eachObject
            val ext = o["container_extension"].orNullIfBlank() ?: "mp4"
            emit(Entry(
                name = o["name"] ?: return@eachObject,
                url = o["direct_source"].httpOrNull() ?: "$base/movie/$u/$p/$id.$ext",
                group = cats[o["category_id"]] ?: "",
                logo = o["stream_icon"].orNullIfBlank(),
                type = ContentType.MOVIE,
                userAgent = uaFor(o["direct_source"]),
                added = o["added"]?.trim()?.toLongOrNull(),
            ))
        }
    }

    /** Uma linha por série (sem episódios): o endereço guarda o id para buscar os episódios depois. */
    fun seriesCovers(playlistId: String, emit: (Entry) -> Unit) {
        val cats = categories("get_series_categories")
        eachObject(api("get_series")) { o ->
            val id = o["series_id"] ?: return@eachObject
            val name = o["name"] ?: return@eachObject
            emit(Entry(
                name = name,
                url = stubUrl(playlistId, id),
                group = cats[o["category_id"]] ?: "",
                logo = o["cover"].orNullIfBlank(),
                type = ContentType.SERIES,
                seriesName = name,
            ))
        }
    }

    /** Episódios de uma série, pedidos na hora em que ela é aberta. */
    fun episodes(seriesId: String, seriesName: String, group: String, cover: String?): List<Entry> {
        val out = ArrayList<Entry>()
        open(api("get_series_info", "&series_id=${enc(seriesId)}")) { input ->
            reader(input).use { r ->
                if (r.peek() != JsonToken.BEGIN_OBJECT) return@use
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "episodes") { r.skipValue(); continue }
                    // { "1": [ ... ], "2": [ ... ] } ou, em alguns painéis, uma lista de listas
                    when (r.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            r.beginObject()
                            while (r.hasNext()) { val season = r.nextName().toIntOrNull(); readEpisodes(r, season, seriesName, group, cover, out) }
                            r.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                if (r.peek() == JsonToken.BEGIN_ARRAY) readEpisodes(r, null, seriesName, group, cover, out)
                                else readEpisode(r, null, seriesName, group, cover)?.let { out += it }
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
            }
        }
        return out
    }

    private fun readEpisodes(r: JsonReader, season: Int?, series: String, group: String, cover: String?, out: MutableList<Entry>) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return }
        r.beginArray()
        while (r.hasNext()) readEpisode(r, season, series, group, cover)?.let { out += it }
        r.endArray()
    }

    private fun readEpisode(r: JsonReader, seasonKey: Int?, series: String, group: String, cover: String?): Entry? {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        val o = HashMap<String, String?>()
        var image: String? = null
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (name == "info" && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject()
                while (r.hasNext()) { if (r.nextName() == "movie_image") image = r.value() else r.skipValue() }
                r.endObject()
            } else o[name] = r.value()
        }
        r.endObject()
        val id = o["id"] ?: return null
        val season = o["season"]?.toIntOrNull() ?: seasonKey
        val number = o["episode_num"]?.toIntOrNull()
        val ext = o["container_extension"].orNullIfBlank() ?: "mp4"
        val marker = if (number != null) " S%02dE%02d".format(season ?: 0, number) else ""
        val title = o["title"].orNullIfBlank() ?: "$series$marker"
        var name = if (marker.isNotEmpty() && !Regex("""[Ss]\d+\s*[Ee]\d+""").containsMatchIn(title)) "$series$marker - $title" else title
        // alguns servidores dizem a versão num campo à parte ("version": "dub" / "leg"): vai para o nome, onde o app procura
        versionTag(o["version"] ?: o["audio"] ?: o["language"])?.let { tag ->
            if (!Regex("""legend|dublad|\b(leg|dub)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)) name = "$name [$tag]"
        }
        return Entry(
            name = name,
            url = o["direct_source"].httpOrNull() ?: "$base/series/$u/$p/$id.$ext",
            group = group,
            logo = cover ?: image.orNullIfBlank(),
            type = ContentType.SERIES,
            seriesName = series,
            season = season,
            episode = number,
            userAgent = uaFor(o["direct_source"]),
        )
    }

    /**
     * Link direto (direct_source) toca como os outros links de lista, com o UA normal do player;
     * endereço do próprio servidor (/live, /movie, /series) vai com o mesmo UA da API.
     */
    private fun uaFor(direct: String?) = if (direct.httpOrNull() != null) null else userAgent

    /** Lê uma lista JSON de objetos sem carregar tudo na memória (as listas de filmes passam de 70 mil itens). */
    private fun eachObject(url: String, block: (Map<String, String?>) -> Unit) {
        open(url) { input ->
            reader(input).use { r ->
                when (r.peek()) {
                    JsonToken.BEGIN_ARRAY -> {}
                    // painel sem nada nessa parte costuma responder {} ou []
                    else -> { r.skipValue(); return@use }
                }
                r.beginArray()
                val o = HashMap<String, String?>()
                while (r.hasNext()) {
                    if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                    o.clear()
                    r.beginObject()
                    while (r.hasNext()) o[r.nextName()] = r.value()
                    r.endObject()
                    block(o)
                }
                r.endArray()
            }
        }
    }

    companion object {
        private const val STUB = "xtream-series://"

        fun stubUrl(playlistId: String, seriesId: String) = "$STUB$playlistId/$seriesId"

        fun isStub(url: String) = url.startsWith(STUB)

        /** id da série guardado na linha da capa. */
        fun stubSeriesId(url: String) = url.removePrefix(STUB).substringAfter('/')

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun reader(input: InputStream) = JsonReader(input.bufferedReader(Charsets.UTF_8)).apply { isLenient = true }

        /** Texto de qualquer valor simples (número, texto, booleano); objetos e listas são pulados. */
        private fun JsonReader.value(): String? = when (peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> nextString()
            JsonToken.BOOLEAN -> nextBoolean().toString()
            JsonToken.NULL -> { nextNull(); null }
            else -> { skipValue(); null }
        }

        /** "dub", "dublado", "pt" -> Dublado; "leg", "sub", "legendado" -> Legendado. */
        fun versionTag(v: String?): String? {
            val t = v?.trim()?.lowercase() ?: return null
            return when {
                t.startsWith("leg") || t.startsWith("sub") -> "Legendado"
                t.startsWith("dub") || t == "pt" || t == "pt-br" || t == "nacional" -> "Dublado"
                else -> null
            }
        }

        private fun String?.orNullIfBlank() = this?.trim()?.ifBlank { null }
        private fun String?.httpOrNull() = this?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

        /**
         * Reconhece um acesso Xtream colado como link:
         * "http://servidor:8080/get.php?username=x&password=y&type=m3u_plus" ou ".../player_api.php?username=x&password=y".
         * Devolve (servidor, usuário, senha).
         */
        fun parseLogin(link: String): Triple<String, String, String>? {
            val uri = runCatching { java.net.URI(link.trim()) }.getOrNull() ?: return null
            val path = uri.rawPath ?: return null
            val file = path.substringAfterLast('/').lowercase()
            if (file != "get.php" && file != "player_api.php") return null
            val q = (uri.rawQuery ?: return null).split('&').mapNotNull {
                val k = it.substringBefore('=', "")
                if (k.isEmpty()) null else k.lowercase() to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }.toMap()
            val user = q["username"]?.ifBlank { null } ?: return null
            val pass = q["password"] ?: return null
            val dir = path.substringBeforeLast('/')
            val host = uri.rawAuthority ?: return null
            return Triple("${uri.scheme}://$host$dir", user, pass)
        }

        /** Endereço do acesso Xtream a partir do servidor, usuário e senha digitados (senha opcional). */
        fun loginUrl(server: String, user: String, pass: String): String {
            var s = server.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            s = s.removeSuffix("/player_api.php").removeSuffix("/get.php")
            // senha em branco: vai o próprio usuário (servidores que aceitam qualquer senha funcionam assim)
            val u = user.trim()
            val pw = pass.trim().ifEmpty { u }
            return "$s/player_api.php?username=${enc(u)}&password=${enc(pw)}"
        }
    }
}
