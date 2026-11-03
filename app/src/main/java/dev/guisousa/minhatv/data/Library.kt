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

        private val adult = Regex("""(?i)adult|xxx|\+18|18\+|erot|porn|hot ?club|playboy|sexy|brasileirinhas""")
        fun isAdult(group: String) = adult.containsMatchIn(group)
    }
}
