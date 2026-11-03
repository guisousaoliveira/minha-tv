package dev.guisousa.minhatv.data

/** Resumo do que está no banco (só as categorias), usado para montar as telas. */
class Library(
    val version: Long,
    val liveGroups: List<GroupInfo>,
    val movieGroups: List<GroupInfo>,
    val showGroups: List<GroupInfo>,
) {
    val liveTotal = liveGroups.sumOf { it.count }
    val movieTotal = movieGroups.sumOf { it.count }
    val showTotal = showGroups.sumOf { it.count }
    val isEmpty get() = liveTotal + movieTotal + showTotal == 0

    companion object {
        val EMPTY = Library(0, emptyList(), emptyList(), emptyList())

        /** "Todos" só aparece quando cabe na memória com folga. */
        const val ALL_LIMIT = 3000

        private val foreign = Regex("""^(?!br\b|pt\b|bra\b)[a-z]{2,3}\s*(?:·|\||:|-)""")
        private val foreignWords = Regex("""\b(usa|uk|latino|latam|espana|arab|arabic|india|turk|italia|france|deutsch|german|portugal|mexico|argentina|chile|colombia|ex-yu|africa|asia|kids en|english)\b""")
        private val topWords = Regex("""lancamento|novidade|abertos|globo|sbt|record|band|noticia|jornal|esporte|premiere|sportv|espn|combate|telecine|hbo|infantil|desenho|novela|nacional|brasil""")

        /** 0 = mais importante. Brasil e lançamentos primeiro, outros países por último. */
        fun priority(group: String): Int {
            val g = Normalize.search(group)
            return when {
                foreign.containsMatchIn(g) || foreignWords.containsMatchIn(g) -> 3
                topWords.containsMatchIn(g) -> 0
                "canais" in g || "filmes" in g || "series" in g || "24h" in g -> 1
                else -> 2
            }
        }

        fun <T> sortGroups(list: List<T>, name: (T) -> String): List<T> =
            list.withIndex().sortedWith(compareBy({ priority(name(it.value)) }, { it.index })).map { it.value }

        // ---------- Canais de outros países ----------

        // sem siglas que também são estados do Brasil (AL, BA, PA, MA, PE, RS, RO, SE, ES)
        private const val CODES = "au|bo|uy|py|ar|cl|co|mx|ve|ec|us|usa|eua|uk|ca|in|it|fr|de|nl|be|ch|gr|tr|ru|pl|il|ir|pk|ph|nz|ie|jp|kr|cn|do|cu|hn|gt|cr|pa|ni|sv|za|ng|eg|sa|ae|qa|dz|tn|lb|iq|jo|kw|hr|mk|si|sk|cz|hu|bg|ua|at|no|dk|fi|pt|lat|latam|latino|exyu|ex-yu|arab|afr"
        /** "AU: Canal", "[UY] Canal", "|AR| Canal", "ES - Canal" (código de país no começo). */
        private val codePrefix = Regex("""^\s*[\[(|]?\s*(?:$CODES)\s*(?:[\])|:»>·\-]|\s\|)""")
        private val countryWords = Regex(
            """\b(australia|bolivia|uruguai|uruguay|paraguai|paraguay|argentina|chile|colombia|peru|mexico|venezuela|equador|ecuador|espanha|espana|spain|portugal|italia|italy|franca|france|alemanha|germany|deutsch|deutschland|reino unido|united kingdom|england|estados unidos|united states|canada|india|arabe|arabic|arabia|turquia|turkey|turk|africa|asia|latino|latam|latin|caribe|caribbean|dominicana|dominican|cuba|honduras|guatemala|costa rica|panama|nicaragua|el salvador|coreia|korea|japao|japan|china|russia|polonia|poland|holanda|netherlands|nederland|belgica|belgium|suica|switzerland|grecia|greece|albania|romania|israel|iran|pakistan|filipinas|philippines|nova zelandia|new zealand|irlanda|ireland|ex yu|balkan|balcas|croacia|croatia|servia|serbia|ucrania|ukraine|suecia|sweden|noruega|norway|dinamarca|denmark|finlandia|finland|austria|hungria|hungary|bulgaria|marrocos|morocco|egito|egypt|nigeria|kenya|vietnam|thailand|tailandia|indonesia|malaysia|malasia)\b"""
        )
        /** Esporte e luta ficam mesmo sendo de fora (UFC da Espanha, ESPN gringa...). */
        private val sporty = Regex(
            """\b(ufc|mma|combate|fight|fighting|luta|lutas|boxe|boxing|box|wwe|aew|f1|formula ?1|motogp|nascar|nba|nfl|nhl|mlb|dazn|espn|sport|sports|sportv|esporte|esportes|deportes|futebol|futbol|football|soccer|laliga|la liga|premier league|champions|libertadores|tenis|tennis|golf|ppv|eventos?)\b"""
        )

        fun isSporty(text: String) = sporty.containsMatchIn(Normalize.search(text))

        /** Categoria de canais de outro país (sem ser de esporte). */
        fun isForeignLiveGroup(group: String): Boolean {
            val g = Normalize.search(group)
            if (sporty.containsMatchIn(g)) return false
            return codePrefix.containsMatchIn(g) || countryWords.containsMatchIn(g)
        }

        private val tvgCountry = Regex("""\.([a-z]{2})(?:@|$)""")
        private val foreignCodes = CODES.split('|').toSet()

        /** Canal de outro país dentro de uma categoria misturada (pelo prefixo do nome ou pelo tvg-id). */
        fun isForeignLiveEntry(e: Entry): Boolean {
            val n = Normalize.search(e.name)
            if (sporty.containsMatchIn(n) || sporty.containsMatchIn(Normalize.search(e.group))) return false
            if (codePrefix.containsMatchIn(n)) return true
            val tvg = e.tvgId?.lowercase() ?: return false
            val code = tvgCountry.find(tvg)?.groupValues?.get(1) ?: return false
            return code in foreignCodes
        }

        private val adult = Regex("""(?i)adult|xxx|\+18|18\+|erot|porn|hot ?club|playboy|sexy|brasileirinhas""")
        fun isAdult(group: String) = adult.containsMatchIn(group)
    }
}
