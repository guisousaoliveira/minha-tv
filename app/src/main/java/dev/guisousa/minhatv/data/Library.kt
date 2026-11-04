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

        /** Regras em uso (as do GitHub, ou a cópia que vem no app). */
        private val rules get() = CleanRules.current

        /** 0 = mais importante. Brasil e lançamentos primeiro, outros países por último. */
        fun priority(group: String): Int = rules.priority(group)

        fun <T> sortGroups(list: List<T>, name: (T) -> String): List<T> =
            list.withIndex().sortedWith(compareBy({ priority(name(it.value)) }, { it.index })).map { it.value }

        fun isSporty(text: String) = rules.isSporty(text)

        /** Categoria de canais de outro país (sem ser de esporte). */
        fun isForeignLiveGroup(group: String): Boolean = rules.isForeignLiveGroup(group)

        /** Canal de outro país dentro de uma categoria misturada (pelo prefixo do nome ou pelo tvg-id). */
        fun isForeignLiveEntry(e: Entry): Boolean = rules.isForeignLiveEntry(e.name, e.group, e.tvgId)

        fun isAdult(group: String) = rules.isAdult(group)
    }
}
