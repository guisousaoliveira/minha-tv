package dev.guisousa.minhatv.data

/** Leitor de playlists M3U / M3U8 estendidas (formato usado por IPTV). Kotlin puro, sem Android. */
object M3uParser {

    data class Result(val entries: List<Entry>, val epgUrl: String?)

    private class Info(val title: String, val attrs: Map<String, String>)

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val episodeRegex = Regex("""(?i)(?:^|[^a-z0-9])[ST](\d{1,2})\s*[._ -]?\s*EP?\s*(\d{1,3})(?![0-9])""")
    private val altEpisodeRegex = Regex("""(?i)(?:^|[^a-z0-9])(\d{1,2})x(\d{1,3})(?![0-9])""")
    private val seasonOnlyRegex = Regex("""(?i)(?:temporada|season|temp\.?)\s*(\d{1,2})""")

    private val seriesWords = listOf("série", "series", "serie", "temporada", "season", "novela", "anime", "dorama")
    private val movieWords = listOf("filme", "movie", "vod", "cine ", "lançamento", "lancamento", "coletânea", "coletanea")
    private val liveWords = listOf("canais", "canal ", "24h", "24/7", "ao vivo", "aovivo", "live")
    private val vodExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".webm")

    /** true se o texto parece uma playlist de canais (e não um stream HLS). */
    fun isChannelPlaylist(head: String): Boolean {
        val t = head.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!t.startsWith("#EXTM3U", ignoreCase = true) && !t.contains("#EXTINF", ignoreCase = true)) return false
        return !isHlsStream(t)
    }

    fun isHlsStream(head: String): Boolean =
        head.contains("#EXT-X-TARGETDURATION") || head.contains("#EXT-X-STREAM-INF") ||
            head.contains("#EXT-X-MEDIA-SEQUENCE")

    fun parse(lines: Sequence<String>, playlistId: String = ""): Result {
        val out = ArrayList<Entry>(1024)
        val epg = parseTo(lines, playlistId) { out += it }
        return Result(out, epg)
    }

    /** Versão em fluxo: entrega cada item para [sink] sem guardar a lista inteira na memória. Devolve a URL do EPG. */
    fun parseTo(lines: Sequence<String>, playlistId: String = "", sink: (Entry) -> Unit): String? {
        var epg: String? = null
        var info: Info? = null
        var extGroup: String? = null
        var ua: String? = null
        var ref: String? = null

        for (raw in lines) {
            val line = raw.trim().trimStart('﻿')
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val upper = line.take(12).uppercase()
                when {
                    upper.startsWith("#EXTM3U") -> {
                        val a = attrs(line)
                        epg = a["x-tvg-url"] ?: a["url-tvg"] ?: a["tvg-url"] ?: epg
                    }
                    upper.startsWith("#EXTINF") -> info = parseExtinf(line)
                    upper.startsWith("#EXTGRP") -> extGroup = line.substringAfter(':', "").trim()
                    upper.startsWith("#EXTVLCOPT") -> {
                        val opt = line.substringAfter(':', "")
                        val key = opt.substringBefore('=').trim().lowercase()
                        val value = opt.substringAfter('=', "").trim()
                        when (key) {
                            "http-user-agent" -> ua = value
                            "http-referrer", "http-referer" -> ref = value
                        }
                    }
                }
                continue
            }
            if (!line.contains("://")) {
                info = null; extGroup = null; ua = null; ref = null
                continue
            }

            // Formato Kodi: url|User-Agent=xxx&Referer=yyy
            var url = line
            if (url.contains('|')) {
                val opts = url.substringAfter('|')
                url = url.substringBefore('|')
                opts.split('&').forEach { kv ->
                    val k = kv.substringBefore('=').trim().lowercase()
                    val v = java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                    when (k) {
                        "user-agent" -> ua = v
                        "referer", "referrer" -> ref = v
                    }
                }
            }

            val a = info?.attrs.orEmpty()
            val name = info?.title?.takeIf { it.isNotBlank() }
                ?: a["tvg-name"]?.takeIf { it.isNotBlank() }
                ?: url.substringBefore('?').substringAfterLast('/')
            val group = a["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup ?: ""
            val type = classify(name, group, url, a["tvg-type"] ?: a["type"])
            var seriesName: String? = null
            var season: Int? = null
            var episode: Int? = null
            if (type == ContentType.SERIES) {
                val (s, se, ep) = seriesInfo(name)
                seriesName = s; season = se; episode = ep
            }
            sink(Entry(
                name = name,
                url = url,
                group = group,
                logo = (a["tvg-logo"] ?: a["logo"])?.takeIf { it.isNotBlank() },
                tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
                type = type,
                userAgent = ua ?: a["http-user-agent"] ?: a["user-agent"],
                referrer = ref ?: a["http-referrer"] ?: a["referrer"],
                seriesName = seriesName,
                season = season,
                episode = episode,
                playlistId = playlistId,
            ))
            info = null; extGroup = null; ua = null; ref = null
        }
        val epgUrl = epg?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("http") }
        return epgUrl
    }

    fun classify(name: String, group: String, url: String, hint: String? = null): ContentType {
        when (hint?.lowercase()) {
            "live", "stream", "channel" -> return ContentType.LIVE
            "movie", "movies", "vod" -> return ContentType.MOVIE
            "series", "serie", "show" -> return ContentType.SERIES
        }
        val u = url.lowercase()
        val g = group.lowercase()
        if ("/series/" in u) return ContentType.SERIES
        if ("/movie/" in u || "/movies/" in u) return ContentType.MOVIE
        if ("/live/" in u) return ContentType.LIVE
        if (episodeRegex.containsMatchIn(name)) return ContentType.SERIES
        if (liveWords.any { it in "$g " }) return ContentType.LIVE
        if (seriesWords.any { it in g }) return ContentType.SERIES
        if (movieWords.any { it in "$g " }) return ContentType.MOVIE
        val path = u.substringBefore('?')
        if (vodExtensions.any { path.endsWith(it) }) return ContentType.MOVIE
        return ContentType.LIVE
    }

    /** Extrai (nome da série, temporada, episódio) de títulos como "Friends S01E02" ou "Chaves T1 E5". */
    fun seriesInfo(name: String): Triple<String, Int?, Int?> {
        val m = episodeRegex.find(name) ?: altEpisodeRegex.find(name)
        if (m != null) {
            val show = name.substring(0, m.range.first + if (name[m.range.first].isLetterOrDigit()) 0 else 1)
                .trim().trimEnd('-', '|', ':', '.', '_', '(', '[', ' ').trim()
            return Triple(show.ifBlank { name }, m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull())
        }
        val s = seasonOnlyRegex.find(name)
        return Triple(name, s?.groupValues?.get(1)?.toIntOrNull(), null)
    }

    private fun parseExtinf(line: String): Info {
        val body = line.substringAfter(':', "")
        var inQuotes = false
        var commaAt = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) { commaAt = i; break }
        }
        val head = if (commaAt >= 0) body.substring(0, commaAt) else body
        val title = if (commaAt >= 0) body.substring(commaAt + 1).trim() else ""
        return Info(title, attrs(head))
    }

    private fun attrs(s: String): Map<String, String> =
        attrRegex.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
}
