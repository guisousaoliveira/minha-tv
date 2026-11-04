package dev.guisousa.minhatv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Regras de limpeza (regras/limpeza.json no GitHub). Só regras: nenhum link, lista ou senha.
 * Aplicadas uma vez, na importação, e guardadas no banco como marcas (coluna hide).
 */
class CleanRules private constructor(val file: RulesFile) {

    @Serializable
    data class Merge(val de: String, val para: String)

    @Serializable
    data class Countries(
        val codigos: List<String> = emptyList(),
        val palavras: List<String> = emptyList(),
        val manter_mesmo_de_fora: List<String> = emptyList(),
    )

    @Serializable
    data class RulesFile(
        val versao: Int = 0,
        val esconder_categorias: List<String> = emptyList(),
        val esconder_nomes: List<String> = emptyList(),
        val adulto: List<String> = emptyList(),
        val outros_paises: Countries = Countries(),
        val juntar_categorias: List<Merge> = emptyList(),
        val categorias_no_topo: List<String> = emptyList(),
    )

    val version get() = file.versao

    private fun any(list: List<String>, wordBound: Boolean = false): Regex? {
        val parts = list.filter { it.isNotBlank() && runCatching { Regex(it) }.isSuccess }
        if (parts.isEmpty()) return null
        val body = parts.joinToString("|") { "(?:$it)" }
        return Regex(if (wordBound) """\b(?:$body)\b""" else body)
    }

    private val hideGroup = any(file.esconder_categorias)
    private val hideName = any(file.esconder_nomes)
    private val adult = any(file.adulto)
    private val sporty = any(file.outros_paises.manter_mesmo_de_fora, wordBound = true)
    private val countryWords = any(file.outros_paises.palavras, wordBound = true)
    private val codes = file.outros_paises.codigos.map { it.trim().lowercase() }.filter { it.matches(Regex("[a-z-]+")) }.toSet()

    /** "AU: Canal", "[UY] Canal", "|AR| Canal", "ES - Canal" (código de país no começo). */
    private val codePrefix = if (codes.isEmpty()) null
    else Regex("""^\s*[\[(|]?\s*(?:${codes.joinToString("|") { Regex.escape(it) }})\s*(?:[\])|:»>·\-]|\s\|)""")
    private val tvgCountry = Regex("""\.([a-z]{2})(?:@|$)""")
    private val merges = file.juntar_categorias.mapNotNull { m -> runCatching { Regex(m.de) to m.para }.getOrNull() }
    private val top = any(file.categorias_no_topo)

    private fun Regex?.hit(s: String) = this?.containsMatchIn(s) == true

    fun isSporty(text: String) = sporty.hit(Normalize.search(text))

    fun isAdult(group: String) = adult.hit(Normalize.search(group))

    /** Categoria de canais de outro país (sem ser de esporte). */
    fun isForeignLiveGroup(group: String): Boolean {
        val g = Normalize.search(group)
        if (sporty.hit(g)) return false
        return codePrefix.hit(g) || countryWords.hit(g)
    }

    /** Canal de outro país dentro de uma categoria misturada (pelo prefixo do nome ou pelo tvg-id). */
    fun isForeignLiveEntry(name: String, group: String, tvgId: String?): Boolean {
        val n = Normalize.search(name)
        if (sporty.hit(n) || sporty.hit(Normalize.search(group))) return false
        if (codePrefix.hit(n) || countryWords.hit(n)) return true
        val tvg = tvgId?.lowercase() ?: return false
        val code = tvgCountry.find(tvg)?.groupValues?.get(1) ?: return false
        return code in codes
    }

    /** Nome final da categoria (junta categorias com nomes diferentes). */
    fun group(label: String): String {
        if (merges.isEmpty()) return label
        val g = Normalize.search(label)
        return merges.firstOrNull { it.first.containsMatchIn(g) }?.second ?: label
    }

    /** 0 = mais importante. Brasil e lançamentos primeiro, outros países por último. */
    fun priority(group: String): Int {
        val g = Normalize.search(group)
        return when {
            !sporty.hit(g) && (codePrefix.hit(g) || countryWords.hit(g)) -> 3
            top.hit(g) -> 0
            "canais" in g || "filmes" in g || "series" in g || "24h" in g -> 1
            else -> 2
        }
    }

    /**
     * Marcas de um item, calculadas uma vez na importação:
     * [FOREIGN] canal ao vivo de outro país, [ADULT] categoria adulta, [JUNK] some sempre.
     */
    fun classify(name: String, group: String, tvgId: String?, type: ContentType): Int {
        var m = 0
        if (hideGroup.hit(Normalize.search(group)) || hideName.hit(Normalize.search(name))) m = m or JUNK
        if (isAdult(group)) m = m or ADULT
        if (type == ContentType.LIVE && (isForeignLiveGroup(group) || isForeignLiveEntry(name, group, tvgId))) m = m or FOREIGN
        return m
    }

    companion object {
        const val FOREIGN = 1
        const val ADULT = 2
        const val JUNK = 4

        /** Endereço do arquivo de regras (público, só regras). */
        const val URL = "https://raw.githubusercontent.com/guisousaoliveira/minha-tv/main/regras/limpeza.json"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): CleanRules = CleanRules(json.decodeFromString(RulesFile.serializer(), text))

        /** Cópia que vai dentro do app (vale até a TV baixar a do GitHub). */
        val builtin: CleanRules by lazy {
            val text = CleanRules::class.java.getResourceAsStream("/limpeza.json")?.bufferedReader()?.use { it.readText() }
            if (text != null) parse(text) else CleanRules(RulesFile())
        }

        @Volatile var current: CleanRules = builtin
            private set

        fun use(r: CleanRules) { current = r }
    }
}
