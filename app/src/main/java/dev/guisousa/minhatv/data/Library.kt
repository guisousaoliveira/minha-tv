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

        private val adult = Regex("""(?i)adult|xxx|\+18|18\+|erot|porn|hot ?club|playboy|sexy|brasileirinhas""")
        fun isAdult(group: String) = adult.containsMatchIn(group)
    }
}
