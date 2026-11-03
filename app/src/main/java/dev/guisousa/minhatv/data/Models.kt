package dev.guisousa.minhatv.data

import kotlinx.serialization.Serializable

@Serializable
enum class ContentType { LIVE, MOVIE, SERIES }

/** Uma playlist M3U adicionada pelo usuário. */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val url: String,
    val epgUrl: String? = null,
    val detectedEpgUrl: String? = null,
    /** Página de onde a playlist foi importada (se houver). */
    val sourceId: String? = null,
    val lastUpdated: Long = 0,
    val lastError: String? = null,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
)

/** Uma página (ex.: README do GitHub) que lista várias playlists. É verificada de novo a cada atualização. */
@Serializable
data class Source(
    val id: String,
    val url: String,
    val lastChecked: Long = 0,
    val lastError: String? = null,
    val found: Int = 0,
)

@Serializable
data class Entry(
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String? = null,
    val tvgId: String? = null,
    val type: ContentType = ContentType.LIVE,
    val userAgent: String? = null,
    val referrer: String? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val playlistId: String = "",
)

@Serializable
data class Programme(val start: Long, val stop: Long, val title: String, val desc: String? = null)

@Serializable
data class EpgData(
    /** id do canal no XMLTV -> programas */
    val programmes: Map<String, List<Programme>> = emptyMap(),
    /** nome normalizado do canal -> id do canal no XMLTV */
    val names: Map<String, String> = emptyMap(),
) {
    fun forEntry(e: Entry): List<Programme>? {
        e.tvgId?.let { id -> programmes[id]?.let { return it } }
        val id = names[Normalize.name(e.name)] ?: return null
        return programmes[id]
    }

    fun current(e: Entry, now: Long): Programme? =
        forEntry(e)?.firstOrNull { it.start <= now && now < it.stop }

    fun next(e: Entry, now: Long): Programme? =
        forEntry(e)?.firstOrNull { it.start > now }

    fun merge(other: EpgData) = EpgData(programmes + other.programmes, names + other.names)

    companion object {
        val EMPTY = EpgData()
    }
}

object Normalize {
    private val accents = Regex("""\p{Mn}+""")
    private val codec = Regex("""h\.?26[45]|hevc|x26[45]""")
    private val nonAlnum = Regex("""[^a-z0-9]+""")
    private val dropTokens = setOf("fhd", "uhd", "hd", "sd", "4k", "8k", "fullhd", "full", "hq", "lq", "alt", "backup", "op", "opcao", "br", "720p", "1080p", "2160p", "60fps", "fps")

    private fun base(s: String) = accents.replace(java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD), "")

    /** Chave para juntar itens iguais: "A&E FHD H.265" e "A&E" viram "ae". */
    private val nameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Chave para juntar itens iguais (com cache: a tela chama isso muitas vezes por segundo). */
    fun name(s: String): String {
        nameCache[s]?.let { return it }
        val v = nameUncached(s)
        if (nameCache.size > 20_000) nameCache.clear()
        nameCache[s] = v
        return v
    }

    private fun nameUncached(s: String): String {
        val tokens = nonAlnum.split(codec.replace(base(s), " ")).filter { it.isNotEmpty() }
        val kept = tokens.filter { it !in dropTokens }
        return (kept.ifEmpty { tokens }).joinToString("")
    }

    fun search(s: String): String = base(s)

    private val separators = Regex("""\s*[|=:»>]+\s*""")
    private val spaces = Regex("""\s{2,}""")

    /** Tira emojis e símbolos de nomes de categoria e deixa legível: "CANAIS = GLOBO ✨" -> "Canais · Globo". */
    fun label(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val keep = Character.isLetterOrDigit(cp) || Character.isSpaceChar(cp) || cp.toChar() in "|=:&+.,()'/!?-»>#"
            if (keep && cp < 0x10000) sb.append(cp.toChar()) else if (keep) sb.appendCodePoint(cp) else sb.append(' ')
            i += Character.charCount(cp)
        }
        val t = spaces.replace(separators.replace(sb.toString().trim(), " · "), " ").trim().trim('·', ' ', '-')
        return t.split(" · ").filter { it.isNotBlank() }.joinToString(" · ") { part ->
            val letters = part.filter { it.isLetter() }
            if (letters.isNotEmpty() && letters.none { it.isLowerCase() }) titleCase(part.trim()) else part.trim()
        }.ifBlank { raw.trim() }
    }

    /** Só tira emojis/símbolos (para nomes de canais e filmes). */
    fun clean(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val type = Character.getType(cp)
            val symbol = type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() ||
                type == Character.PRIVATE_USE.toInt() || cp in 0xFE00..0xFE0F || cp == 0x200D
            if (!symbol) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return spaces.replace(sb.toString(), " ").trim().ifBlank { raw.trim() }
    }

    private val small = setOf("de", "da", "do", "das", "dos", "e", "a", "o", "em", "na", "no", "para", "com")
    private val keepUpper = setOf("tv", "hbo", "espn", "sbt", "amc", "tnt", "axn", "cnn", "bbc", "mtv", "ufc", "nba", "nfl", "dc", "24h", "24hrs", "4k", "uhd", "fhd", "hd", "sd", "ppv", "vip", "br", "usa", "uk", "a&e", "tlc", "fx", "nbc", "abc", "cbs", "mma", "wwe", "f1", "ge", "us")

    private fun titleCase(s: String): String = s.split(' ').mapIndexed { idx, w ->
        val lw = w.lowercase()
        when {
            lw in keepUpper -> w.uppercase()
            idx > 0 && lw in small -> lw
            else -> lw.replaceFirstChar { it.titlecase() }
        }
    }.joinToString(" ")
}
