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
    private val quality = Regex("""\b(fhd|uhd|hd|sd|4k|h265|hevc|hd\+|full ?hd)\b""")
    private val accents = Regex("""\p{Mn}+""")

    fun name(s: String): String {
        val noAccent = accents.replace(java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD), "")
        return quality.replace(noAccent, "").filter { it.isLetterOrDigit() }
    }

    fun search(s: String): String =
        accents.replace(java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD), "")
}
