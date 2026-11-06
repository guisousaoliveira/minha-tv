package dev.guisousa.minhatv.data

import kotlinx.serialization.Serializable

data class GroupInfo(val name: String, val count: Int)

/** Série no catálogo (key = id do servidor). */
@Serializable
data class Show(val key: String, val name: String, val group: String, val logo: String?, val episodeCount: Int, val year: Int? = null)

/** Resumo do catálogo (só as categorias), usado para montar as telas. */
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
    }
}
