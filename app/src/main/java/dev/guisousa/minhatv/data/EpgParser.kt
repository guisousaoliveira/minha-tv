package dev.guisousa.minhatv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.GregorianCalendar
import java.util.TimeZone

/** Lê guias XMLTV guardando só os canais que existem nas playlists e só as próximas horas. */
object EpgParser {

    fun parse(
        input: InputStream,
        wantedIds: Set<String>,
        wantedNames: Set<String>,
        from: Long,
        to: Long,
    ): EpgData {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val keepIds = HashSet<String>()
        val names = HashMap<String, String>()
        val programmes = HashMap<String, MutableList<Programme>>()
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))

        var channelId: String? = null
        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var title: String? = null
        var desc: String? = null
        var inProgramme = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        channelId = parser.getAttributeValue(null, "id")
                        if (channelId != null && channelId in wantedIds) keepIds += channelId
                        // guias que usam o próprio nome do canal como id ("Globo SP")
                        channelId?.let { id ->
                            val n = Normalize.name(id)
                            if (n.isNotEmpty() && n in wantedNames) { keepIds += id; names.putIfAbsent(n, id) }
                        }
                    }
                    "display-name" -> if (channelId != null && !inProgramme) {
                        val n = Normalize.name(parser.nextText())
                        if (n.isNotEmpty() && n in wantedNames) {
                            keepIds += channelId!!
                            names.putIfAbsent(n, channelId!!)
                        }
                    }
                    "programme" -> {
                        val ch = parser.getAttributeValue(null, "channel")
                        val start = parseTime(parser.getAttributeValue(null, "start"), cal)
                        val stop = parseTime(parser.getAttributeValue(null, "stop"), cal)
                        if (ch != null && ch in keepIds && stop > from && start < to) {
                            inProgramme = true
                            progChannel = ch; progStart = start; progStop = stop
                            title = null; desc = null
                        } else {
                            skip(parser)
                        }
                    }
                    "title" -> if (inProgramme && title == null) title = parser.nextText()
                    "desc" -> if (inProgramme && desc == null) desc = parser.nextText()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> channelId = null
                    "programme" -> if (inProgramme) {
                        inProgramme = false
                        val ch = progChannel!!
                        programmes.getOrPut(ch) { ArrayList() } +=
                            Programme(progStart, progStop, title?.trim().orEmpty(), desc?.trim()?.take(300))
                    }
                }
            }
            event = parser.next()
        }
        programmes.values.forEach { list -> list.sortBy { it.start } }
        return EpgData(programmes, names)
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** "20241201203000 -0300" -> epoch ms */
    private fun parseTime(s: String?, cal: GregorianCalendar): Long {
        if (s == null || s.length < 12) return 0
        return try {
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt()
            val mi = s.substring(10, 12).toInt()
            val se = if (s.length >= 14 && s[12].isDigit()) s.substring(12, 14).toInt() else 0
            cal.clear()
            cal.set(y, mo - 1, d, h, mi, se)
            var ms = cal.timeInMillis
            val tz = s.substringAfter(' ', "").trim()
            if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                val off = (tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt()) * 60_000L
                ms -= if (tz[0] == '+') off else -off
            }
            ms
        } catch (e: Exception) {
            0
        }
    }

}
