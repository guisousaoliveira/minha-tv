package dev.guisousa.minhatv.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

@kotlinx.serialization.Serializable
data class GroupInfo(val name: String, val count: Int)

/** Série (episódios ficam no banco e são lidos ao abrir). */
data class Show(val key: String, val name: String, val group: String, val logo: String?, val episodeCount: Int)

/**
 * Banco SQLite com todos os itens das playlists. Listas de IPTV podem ter centenas de milhares
 * de itens, então nada disso fica inteiro na memória: as telas leem só a categoria aberta.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "minhatv.db", null, 2) {

    /** true quando o banco foi recriado por mudança de formato (as listas precisam ser baixadas de novo). */
    @Volatile var wasReset = false
        private set

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist TEXT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, grp TEXT NOT NULL,
                logo TEXT, tvg_id TEXT, type INTEGER NOT NULL, ua TEXT, ref TEXT,
                show_key TEXT, series TEXT, season INTEGER, episode INTEGER, norm TEXT)"""
        )
        db.execSQL("CREATE INDEX idx_norm ON entries(norm, type)")
        db.execSQL("CREATE INDEX idx_type_grp ON entries(type, grp)")
        db.execSQL("CREATE INDEX idx_playlist ON entries(playlist)")
        db.execSQL("CREATE INDEX idx_show ON entries(show_key)")
        db.execSQL("CREATE INDEX idx_url ON entries(url)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS entries")
        onCreate(db)
        wasReset = true
    }

    class Counts { var live = 0; var movie = 0; var series = 0 }

    /** Troca todos os itens de uma playlist numa transação só (os antigos continuam até terminar). */
    fun replacePlaylist(playlistId: String, fill: ((Entry) -> Unit) -> Unit): Counts {
        val db = writableDatabase
        val counts = Counts()
        db.beginTransaction()
        try {
            db.delete("entries", "playlist = ?", arrayOf(playlistId))
            val st = db.compileStatement(
                "INSERT INTO entries(playlist,name,url,grp,logo,tvg_id,type,ua,ref,show_key,series,season,episode,norm) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            fill { e ->
                st.clearBindings()
                st.bindString(1, playlistId)
                val cleanName = Normalize.clean(e.name)
                st.bindString(2, cleanName)
                st.bindString(3, e.url)
                st.bindString(4, Normalize.label(e.group).ifBlank { "Outros" })
                e.logo?.let { st.bindString(5, it) }
                e.tvgId?.let { st.bindString(6, it) }
                st.bindLong(7, e.type.ordinal.toLong())
                e.userAgent?.let { st.bindString(8, it) }
                e.referrer?.let { st.bindString(9, it) }
                if (e.type == ContentType.SERIES) {
                    val name = Normalize.clean(e.seriesName ?: e.name)
                    st.bindString(10, Normalize.name(name).ifEmpty { name })
                    st.bindString(11, name)
                }
                e.season?.let { st.bindLong(12, it.toLong()) }
                e.episode?.let { st.bindLong(13, it.toLong()) }
                st.bindString(14, Normalize.name(cleanName).ifEmpty { cleanName.lowercase() })
                st.executeInsert()
                when (e.type) {
                    ContentType.LIVE -> counts.live++
                    ContentType.MOVIE -> counts.movie++
                    ContentType.SERIES -> counts.series++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return counts
    }

    fun deletePlaylist(playlistId: String) {
        writableDatabase.delete("entries", "playlist = ?", arrayOf(playlistId))
    }

    /** Apaga itens de playlists que não existem mais (só olha o índice, é rápido). */
    fun deleteExcept(keep: Collection<String>) {
        val present = readableDatabase.rawQuery("SELECT DISTINCT playlist FROM entries", null).use { c -> c.map { it.getString(0) } }
        present.filter { it !in keep }.forEach { deletePlaylist(it) }
    }

    fun groups(type: ContentType): List<GroupInfo> = readableDatabase.rawQuery(
        "SELECT grp, COUNT(*) FROM entries WHERE type = ? GROUP BY grp ORDER BY MIN(id)",
        arrayOf(type.ordinal.toString()),
    ).use { c -> c.map { GroupInfo(it.getString(0), it.getInt(1)) } }

    fun showGroups(): List<GroupInfo> = readableDatabase.rawQuery(
        "SELECT grp, COUNT(DISTINCT show_key) FROM entries WHERE type = 2 GROUP BY grp ORDER BY MIN(id)", null,
    ).use { c -> c.map { GroupInfo(it.getString(0), it.getInt(1)) } }

    fun entries(type: ContentType, group: String?, limit: Int = 60_000): List<Entry> {
        val (where, args) = if (group == null) "type = ?" to arrayOf(type.ordinal.toString())
        else "type = ? AND grp = ?" to arrayOf(type.ordinal.toString(), group)
        return query("$where ORDER BY id LIMIT $limit", args)
    }

    fun shows(group: String?, limit: Int = 20_000): List<Show> {
        val (where, args) = if (group == null) "type = 2" to emptyArray<String>() else "type = 2 AND grp = ?" to arrayOf(group)
        return readableDatabase.rawQuery(
            "SELECT show_key, MIN(series), MIN(grp), MAX(logo), COUNT(*) FROM entries WHERE $where GROUP BY show_key ORDER BY MIN(id) LIMIT $limit",
            args,
        ).use { c -> c.map { Show(it.getString(0), it.getString(1), it.getString(2), it.getStringOrNull(3), it.getInt(4)) } }
    }

    fun episodes(showKey: String): List<Entry> =
        query("show_key = ? ORDER BY COALESCE(season, 0), COALESCE(episode, 99999), id", arrayOf(showKey))

    fun search(q: String, limit: Int = 300): Pair<List<Entry>, List<Show>> {
        val like = "%" + q.replace("%", "").replace("_", "") + "%"
        val entries = query("type != 2 AND name LIKE ? ORDER BY type, id LIMIT $limit", arrayOf(like))
        val shows = readableDatabase.rawQuery(
            "SELECT show_key, MIN(series), MIN(grp), MAX(logo), COUNT(*) FROM entries WHERE type = 2 AND series LIKE ? GROUP BY show_key ORDER BY MIN(id) LIMIT 100",
            arrayOf(like),
        ).use { c -> c.map { Show(it.getString(0), it.getString(1), it.getString(2), it.getStringOrNull(3), it.getInt(4)) } }
        return entries to shows
    }

    fun byUrls(urls: Collection<String>): List<Entry> {
        if (urls.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        urls.chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            query("url IN ($marks) ORDER BY id", chunk.toTypedArray()).forEach { if (seen.add(it.url)) out += it }
        }
        return out
    }

    /** Outras fontes do mesmo canal/filme (mesmo nome normalizado), em qualquer playlist. */
    fun alternates(type: ContentType, norm: String, exceptUrl: String): List<Entry> =
        query("type = ? AND norm = ? AND url != ? ORDER BY id LIMIT 30", arrayOf(type.ordinal.toString(), norm, exceptUrl))

    fun firstByUrl(url: String): Entry? = query("url = ? LIMIT 1", arrayOf(url)).firstOrNull()

    /** Canais ao vivo de uma playlist: ids e nomes para filtrar o guia de programação. */
    /** tvg-ids e nomes dos canais ao vivo de uma lista (ou de todas, com null). */
    fun liveKeys(playlistId: String?): Pair<Set<String>, Set<String>> {
        val ids = HashSet<String>()
        val names = HashSet<String>()
        val c0 = if (playlistId == null) readableDatabase.rawQuery("SELECT tvg_id, name FROM entries WHERE type = 0", null)
        else readableDatabase.rawQuery("SELECT tvg_id, name FROM entries WHERE playlist = ? AND type = 0", arrayOf(playlistId))
        c0.use { c ->
            while (c.moveToNext()) {
                c.getStringOrNull(0)?.let { ids += it }
                if (names.size < 50_000) Normalize.name(c.getString(1)).takeIf { it.isNotEmpty() }?.let { names += it }
            }
        }
        return ids to names
    }

    private fun query(where: String, args: Array<String>): List<Entry> = readableDatabase.rawQuery(
        "SELECT playlist,name,url,grp,logo,tvg_id,type,ua,ref,series,season,episode FROM entries WHERE $where", args,
    ).use { c ->
        c.map {
            Entry(
                playlistId = it.getString(0),
                name = it.getString(1),
                url = it.getString(2),
                group = it.getString(3),
                logo = it.getStringOrNull(4),
                tvgId = it.getStringOrNull(5),
                type = ContentType.entries[it.getInt(6)],
                userAgent = it.getStringOrNull(7),
                referrer = it.getStringOrNull(8),
                seriesName = it.getStringOrNull(9),
                season = if (it.isNull(10)) null else it.getInt(10),
                episode = if (it.isNull(11)) null else it.getInt(11),
            )
        }
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private inline fun <T> Cursor.map(block: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count.coerceAtLeast(0))
        while (moveToNext()) out += block(this)
        return out
    }
}
