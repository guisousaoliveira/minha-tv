package dev.guisousa.minhatv.data

/** Série montada a partir dos episódios da playlist. */
class Show(val key: String, val name: String, val group: String, val logo: String?, val episodes: List<Entry>) {
    /** temporada -> episódios (temporada 0 = sem temporada) */
    val seasons: List<Pair<Int, List<Entry>>> by lazy {
        episodes.groupBy { it.season ?: 0 }.toSortedMap().map { (s, eps) ->
            s to eps.sortedWith(compareBy(nullsLast()) { it.episode })
        }
    }
}

/** Índice de tudo que foi carregado das playlists, pronto para as telas. */
class Library(
    val live: List<Entry>,
    val liveGroups: List<Pair<String, List<Entry>>>,
    val movies: List<Entry>,
    val movieGroups: List<Pair<String, List<Entry>>>,
    val shows: List<Show>,
    val showGroups: List<Pair<String, List<Show>>>,
    val byUrl: Map<String, Entry>,
    val all: List<Entry>,
) {
    val isEmpty get() = all.isEmpty()

    companion object {
        val EMPTY = build(emptyList())

        fun build(entries: List<Entry>): Library {
            // remove duplicados (mesma URL em listas diferentes)
            val seen = HashSet<String>()
            val unique = entries.filter { seen.add(it.url) }

            val live = unique.filter { it.type == ContentType.LIVE }
            val movies = unique.filter { it.type == ContentType.MOVIE }
            val episodes = unique.filter { it.type == ContentType.SERIES }

            val showMap = LinkedHashMap<String, MutableList<Entry>>()
            for (e in episodes) {
                val key = Normalize.name(e.seriesName ?: e.name).ifEmpty { e.name }
                showMap.getOrPut(key) { ArrayList() } += e
            }
            val shows = showMap.map { (key, eps) ->
                val first = eps.first()
                Show(key, first.seriesName ?: first.name, first.group, eps.firstNotNullOfOrNull { it.logo }, eps)
            }

            return Library(
                live = live,
                liveGroups = groups(live) { it.group },
                movies = movies,
                movieGroups = groups(movies) { it.group },
                shows = shows,
                showGroups = groups(shows) { it.group },
                byUrl = unique.associateBy { it.url },
                all = unique,
            )
        }

        private fun <T> groups(items: List<T>, groupOf: (T) -> String): List<Pair<String, List<T>>> {
            val map = LinkedHashMap<String, MutableList<T>>()
            for (i in items) map.getOrPut(groupOf(i).ifBlank { "Outros" }) { ArrayList() } += i
            return map.map { it.key to it.value }
        }
    }
}
