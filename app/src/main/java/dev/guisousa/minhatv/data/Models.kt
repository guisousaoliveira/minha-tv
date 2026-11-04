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
    /** Servidor Xtream (url = endereço do servidor): usuário e senha. */
    val xtreamUser: String? = null,
    val xtreamPass: String? = null,
) {
    val isXtream get() = xtreamUser != null
}

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
    /** Etiqueta tirada do nome: "FHD", "4K · Dublado"... (só para mostrar). */
    val quality: String? = null,
    /** Chave para achar as outras fontes do mesmo título (vem do banco). */
    val norm: String? = null,
    /** Marcas da limpeza (CleanRules.FOREIGN/ADULT), vindas do banco. */
    val hidden: Int = 0,
    /** Quando o item entrou no servidor (unix, campo "added" do Xtream). Ordena os lançamentos. */
    val added: Long? = null,
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

    private val yearRx = Regex("""(?<!\d)(19[2-9]\d|20[0-4]\d)(?!\d)""")

    /**
     * Chave para juntar fontes do mesmo título. Filme sem ano no nome só junta com outro do mesmo
     * pôster: "Homem-Aranha" de 2002 e o de agora têm o mesmo nome e não podem virar um item só.
     * (Com ano, o ano já faz parte da chave: "homemaranha2002".)
     */
    fun mergeKey(e: Entry): String {
        val norm = e.norm ?: name(e.name).ifEmpty { e.url }
        if (e.type != ContentType.MOVIE || yearRx.containsMatchIn(e.name)) return norm
        return norm + "|" + (e.logo?.trim()?.lowercase()?.ifEmpty { null } ?: e.url)
    }

    private val episodeMark = Regex("""(?i)(?:^|[^a-z0-9])(?:[ST]\d{1,2}\s*[._ -]?\s*EP?\s*\d{1,3}|\d{1,2}x\d{1,3}|EP?\s*\d{1,3})(?![0-9])""")
    private val edgeSeps = Regex("""^[\s\-–—|:·.,_]+|[\s\-–—|:·.,_]+$""")

    /**
     * Nome do episódio sem repetir o da série: "Smallville S01E02 Metamorfose" -> "Metamorfose".
     * Sem nada sobrando, fica "Episódio 2".
     */
    fun episodeTitle(name: String, series: String?, episode: Int?): String {
        var t = name
        if (!series.isNullOrBlank()) {
            // tira o nome da série do começo, comparando sem acento e sem maiúsculas
            val s = base(series).trim()
            if (s.isNotEmpty() && base(t).startsWith(s)) t = t.substring(minOf(s.length, t.length))
        }
        t = episodeMark.find(t)?.let { m -> t.substring(m.range.last + 1).ifBlank { t.substring(0, m.range.first) } } ?: t
        t = edgeSeps.replace(t, "").trim()
        // sobrou só o nome da série ou nada: mostra o número
        if (t.isEmpty() || (!series.isNullOrBlank() && name(t) == name(series))) return if (episode != null) "Episódio $episode" else name
        return t
    }

    private val yearInBrackets = Regex("""\s*[\[(]\s*(?:19|20)\d{2}\s*[\])]\s*""")

    private val articles = setOf("a", "o", "as", "os", "um", "uma", "the", "de", "da", "do", "das", "dos", "e", "and", "of", "el", "la", "le", "les")

    /**
     * Chave solta para juntar o mesmo título escrito de jeitos diferentes: sem pontuação, acento,
     * qualidade nem artigos ("Batman: A Alma do Dragão" = "Batman Alma do Dragao"). Os artigos só
     * saem quando sobram pelo menos 2 palavras ("A&E" continua "ae").
     */
    fun looseKey(title: String): String {
        val tokens = nonAlnum.split(codec.replace(base(title), " ")).filter { it.isNotEmpty() && it !in dropTokens }
        val kept = tokens.filter { it !in articles }
        return (if (kept.size >= 2) kept else tokens).joinToString("").ifEmpty { name(title).ifEmpty { title.lowercase() } }
    }

    /**
     * Chave da série: nome limpo (sem Dublado/Legendado, qualidade, ano entre parênteses, pontuação
     * e artigos), para "Smallville", "Smallville (2001)" e "Smallville: As Aventuras [L]" se juntarem.
     */
    fun showKey(seriesName: String): String {
        val title = display(seriesName).title
        val noYear = yearInBrackets.replace(title, " ").trim().ifEmpty { title }
        return looseKey(noYear)
    }

    /** Chave de agrupamento a partir do nome já limpo. */
    fun key(title: String): String = name(title).ifEmpty { title.lowercase() }

    private val displayCache = object : java.util.LinkedHashMap<String, Display>(4096, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Display>?) = size > 20_000
    }

    /** [display] com memória (para itens antigos que ainda não têm o nome limpo guardado). */
    fun displayCached(raw: String): Display =
        synchronized(displayCache) { displayCache[raw] } ?: display(raw).also { d -> synchronized(displayCache) { displayCache[raw] = d } }

    /** Nome limpo para a tela + etiqueta de qualidade/áudio. */
    data class Display(val title: String, val tag: String?)

    private val prefix = Regex("""^\s*(?:[\[(|]\s*)?(?:br|bra|brasil|pt|ptbr|pt-br|us|usa|uk|lat|latam|es|vip|24/7|24h|24hs|24hrs)\s*(?:[\])|:»>\-]+\s*)+""", RegexOption.IGNORE_CASE)
    private val bracketed = Regex("""[\[(]([^\[\]()]*)[\])]""")
    private const val B = """(?<![\p{L}\p{N}])"""
    private const val E = """(?![\p{L}\p{N}])"""
    /** Qualidade solta no nome. */
    private val qualityWords = Regex("""$B(4k|uhd|2160p?|fhd|full\s*hd|fullhd|1080p?|hd|720p?|sd|480p?|h\.?26[45]|hevc|x26[45]|\d{2}\s*fps|fps|hdr(?:10\+?)?)$E""", RegexOption.IGNORE_CASE)
    /** Só dentro de colchetes/parênteses (soltas no nome podem fazer parte do título). */
    private val qualityInBrackets = Regex("""$B(hq|lq|dolby|atmos|dv|vision|uhd\s*hdr)$E""", RegexOption.IGNORE_CASE)
    private val audioWords = Regex("""$B(dublado|dubbed|legendado|dual\s*[aá]udio)$E""", RegexOption.IGNORE_CASE)
    private val audioInBrackets = Regex("""$B(dub|leg|legenda|sub|subbed|dual|nacional|original|l|d)$E""", RegexOption.IGNORE_CASE)
    private val junkWords = Regex("""$B(alt|alternativo|backup|reserva|op[cç][aã]o\s*\d+|sinal\s*\d+|link\s*\d+|s/an[uú]ncio|sem\s+an[uú]ncio)$E""", RegexOption.IGNORE_CASE)
    private val junkInBrackets = Regex("""$B(new|novo|ppv|vod|tv|ao\s*vivo|live|\d+)$E""", RegexOption.IGNORE_CASE)
    /** "Duna 2021 Leg" / "Filme - DUB" no fim do nome. */
    private val audioSuffix = Regex("""[\s\-|]+(dub|leg|dual)\s*$""", RegexOption.IGNORE_CASE)
    private val year = Regex("""^\s*(19|20)\d{2}\s*$""")
    private val edgeJunk = Regex("""^[\s|:\-–—·*#»>.,+_~]+|[\s|:\-–—·*#»>.,+_~]+$""")
    private val superDigits = mapOf('⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9')
    private val superK = Regex("""⁴\s*ᴷ""")
    private val superDigit = Regex("""[⁰¹²³⁴-⁹]+""")
    private val modifiers = Regex("""[\p{Lm}²³¹⁰-₟]""") // ⁽ᴴᴰ⁾, ², ³
    private val emptyBrackets = Regex("""[\[(]\s*[\])]""")

    /**
     * "BR: GLOBO SP (FHD) H.265 [ALT]" -> título "Globo SP", etiqueta "FHD".
     * "Duna (2021) [Dublado] 4K" -> "Duna (2021)", "4K · Dublado".
     */
    fun display(raw: String): Display {
        var t = clean(raw)
        // "SPORTV²" é o SporTV 2, não o SporTV: número sobrescrito vira número normal antes de limpar
        t = superK.replace(t, " 4K")
        t = superDigit.replace(t) { m -> " " + m.value.map { superDigits[it] ?: ' ' }.joinToString("") }
        t = modifiers.replace(t, " ")
        var best = 0
        var audio: String? = null
        fun note(q: String) {
            val k = q.lowercase().replace(" ", "")
            val rank = when {
                k == "4k" || k == "uhd" || k.startsWith("2160") -> 4
                k == "fhd" || k == "fullhd" || k.startsWith("1080") -> 3
                k == "hd" || k.startsWith("720") -> 2
                k == "sd" || k.startsWith("480") -> 1
                else -> 0
            }
            if (rank > best) best = rank
        }
        fun noteAudio(a: String) {
            val k = a.lowercase()
            audio = audio ?: when {
                k.startsWith("dub") || k == "d" -> "Dublado"
                k.startsWith("leg") || k.startsWith("sub") || k == "l" -> "Legendado"
                k.startsWith("dual") -> "Dual áudio"
                k == "nacional" -> "Nacional"
                else -> null
            }
        }
        // tira prefixos de país/grupo ("BR:", "[BR]", "|BR|", "24/7 -") quantas vezes aparecerem
        repeat(3) { t = prefix.replace(t, "") }
        // conteúdo entre colchetes/parênteses que é só etiqueta some (o ano do filme fica)
        t = bracketed.replace(t) { m ->
            val inner = m.groupValues[1]
            if (year.matches(inner)) return@replace "(${inner.trim()})"
            var rest = qualityWords.replace(inner) { note(it.value); " " }
            rest = qualityInBrackets.replace(rest, " ")
            rest = audioWords.replace(rest) { noteAudio(it.value); " " }
            rest = audioInBrackets.replace(rest) { noteAudio(it.value); " " }
            rest = junkInBrackets.replace(junkWords.replace(rest, " "), " ")
            if (edgeJunk.replace(rest, "").isBlank()) " " else m.value
        }
        t = qualityWords.replace(t) { note(it.value); " " }
        t = audioWords.replace(t) { noteAudio(it.value); " " }
        t = spaces.replace(t, " ").trim()
        t = audioSuffix.replace(t) { noteAudio(it.groupValues[1]); "" }
        t = junkWords.replace(t, " ")
        t = emptyBrackets.replace(t, " ")
        t = spaces.replace(t, " ")
        repeat(2) { t = edgeJunk.replace(t, "").trim() }
        if (t.isBlank()) t = clean(raw)
        // TUDO EM MAIÚSCULAS fica "Tudo em Maiúsculas"
        val letters = t.filter { it.isLetter() }
        if (letters.length > 3 && letters.none { it.isLowerCase() }) t = titleCase(t)
        val q = when (best) { 4 -> "4K"; 3 -> "FHD"; 2 -> "HD"; 1 -> "SD"; else -> null }
        val tag = listOfNotNull(q, audio).joinToString(" · ").ifEmpty { null }
        return Display(t, tag)
    }

    private val small = setOf("de", "da", "do", "das", "dos", "e", "a", "o", "em", "na", "no", "para", "com")
    private val keepUpper = setOf("tv", "hbo", "espn", "sbt", "amc", "tnt", "axn", "cnn", "bbc", "mtv", "ufc", "nba", "nfl", "dc", "24h", "24hrs", "4k", "uhd", "fhd", "hd", "sd", "ppv", "vip", "br", "usa", "uk", "a&e", "tlc", "fx", "nbc", "abc", "cbs", "mma", "wwe", "f1", "ge", "us", "sp", "rj", "mg", "rs", "pr", "ba", "df", "pe", "ce", "sc", "ii", "iii", "iv", "nfl", "nhl", "mlb", "lfp", "ufc", "bbb", "rbs", "rpc", "eptv", "cbn", "tv5", "ppv")

    private fun titleCase(s: String): String = s.split(' ').mapIndexed { idx, w ->
        val lw = w.lowercase()
        when {
            lw in keepUpper -> w.uppercase()
            idx > 0 && lw in small -> lw
            else -> lw.replaceFirstChar { it.titlecase() }
        }
    }.joinToString(" ")
}
