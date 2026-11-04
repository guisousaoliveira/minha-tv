package dev.guisousa.minhatv.data

import java.net.URI

/** Acha links de playlists M3U dentro de uma página qualquer (README, HTML, texto). Kotlin puro. */
object LinkExtractor {

    private val absolute = Regex("""https?://[^\s"'<>()\[\]{}`]+""")
    private val markdownLink = Regex("""\]\(\s*<?([^)\s>]+)>?""")
    private val hrefLink = Regex("""(?i)href\s*=\s*["']([^"']+)["']""")

    /** Converte links de páginas do GitHub para o arquivo "cru" (raw). */
    fun toRaw(url: String): String {
        val u = url.trim()
        Regex("""^https?://github\.com/([^/]+)/([^/]+)/(?:blob|raw)/(.+)$""").find(u)?.let {
            val (owner, repo, rest) = it.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$rest"
        }
        Regex("""^https?://github\.com/([^/?#]+)/([^/?#]+)/?$""").find(u)?.let {
            val (owner, repo) = it.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/HEAD/README.md"
        }
        Regex("""^https?://gist\.github\.com/([^/]+)/([0-9a-f]+)/?$""").find(u)?.let {
            val (owner, id) = it.destructured
            return "https://gist.githubusercontent.com/$owner/$id/raw"
        }
        return u
    }

    /** Se o link aponta para um repositório do GitHub (qualquer página dele), devolve (dono, repo). */
    fun githubRepo(url: String): Pair<String, String>? {
        val u = url.trim()
        Regex("""^https?://(?:www\.)?github\.com/([^/?#]+)/([^/?#]+)""").find(u)?.let {
            val (o, r) = it.destructured
            if (o in setOf("orgs", "settings", "topics", "search", "marketplace")) return null
            return o to r.removeSuffix(".git")
        }
        Regex("""^https?://raw\.githubusercontent\.com/([^/?#]+)/([^/?#]+)/""").find(u)?.let {
            val (o, r) = it.destructured
            return o to r
        }
        return null
    }

    /** Endereço da API do GitHub que lista todos os arquivos do repositório. */
    fun githubTreeApi(owner: String, repo: String) =
        "https://api.github.com/repos/$owner/$repo/git/trees/HEAD?recursive=1"

    fun githubRawFile(owner: String, repo: String, path: String): String =
        "https://raw.githubusercontent.com/$owner/$repo/HEAD/" +
            path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /**
     * Chave para saber se dois links são o mesmo arquivo: no GitHub, ".../HEAD/x.m3u", ".../main/x.m3u",
     * ".../refs/heads/main/x.m3u" e "github.com/.../blob/main/x.m3u" são a mesma lista.
     */
    fun sameFileKey(url: String): String {
        val raw = toRaw(url).substringBefore('#')
        val decoded = runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        Regex("""^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/(?:refs/heads/)?[^/]+/(.+)$""").find(decoded)?.let {
            val (o, r, path) = it.destructured
            return "gh:${o.lowercase()}/${r.lowercase()}/$path"
        }
        return decoded.replaceFirst(Regex("^https?://"), "")
    }

    fun looksLikePlaylist(url: String): Boolean {
        val l = url.lowercase()
        val path = l.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u") || path.endsWith(".m3u8") ||
            ("get.php" in path && "type=m3u" in l) || "output=m3u" in l
    }

    fun extract(pageUrl: String, text: String): List<String> {
        val base = runCatching { URI(pageUrl) }.getOrNull()
        val found = LinkedHashSet<String>()
        fun add(candidate: String) {
            var c = candidate.trim().trimEnd('.', ',', ';', ':', '*', '!', '_')
            if (c.isEmpty() || c.startsWith("#") || c.startsWith("mailto:")) return
            if (!c.startsWith("http://") && !c.startsWith("https://")) {
                c = base?.let { runCatching { it.resolve(c.replace(" ", "%20")).toString() }.getOrNull() } ?: return
            }
            if (looksLikePlaylist(c)) found += toRaw(c)
        }
        absolute.findAll(text).forEach { add(it.value) }
        markdownLink.findAll(text).forEach { add(it.groupValues[1]) }
        hrefLink.findAll(text).forEach { add(it.groupValues[1]) }
        return found.toList()
    }

    /** Nome amigável a partir da URL: ".../listas/Canais%20Abertos.m3u" -> "Canais Abertos". */
    fun nameFromUrl(url: String): String {
        val last = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        val noExt = decoded.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
        return noExt.ifBlank { runCatching { URI(url).host }.getOrNull() ?: "Playlist" }
    }
}
