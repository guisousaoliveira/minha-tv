package dev.guisousa.minhatv.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

@kotlinx.serialization.Serializable
data class GroupInfo(val name: String, val count: Int)

/** Resultado do último teste de um link. */
data class Health(val ok: Boolean, val fails: Int, val checked: Long, val ms: Long?) {
    /** Falhou de novo e de novo (no teste em segundo plano e/ou no player): some da tela. */
    val dead get() = !ok && fails >= DEAD_FAILS

    companion object { const val DEAD_FAILS = 2 }
}

/** Série (episódios ficam no banco e são lidos ao abrir). */
@kotlinx.serialization.Serializable
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

    override fun onOpen(db: SQLiteDatabase) {
        // nome limpo e etiqueta (FHD, Dublado...) guardados na importação; bancos antigos ganham as colunas aqui
        val cols = db.rawQuery("PRAGMA table_info(entries)", null).use { c -> c.map { it.getString(1) } }
        if ("title" !in cols) db.execSQL("ALTER TABLE entries ADD COLUMN title TEXT")
        if ("tag" !in cols) db.execSQL("ALTER TABLE entries ADD COLUMN tag TEXT")
        // tabela à parte (não some quando as listas são baixadas de novo) e sem mudar a versão do banco
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS health(
                url TEXT PRIMARY KEY, ok INTEGER NOT NULL, fails INTEGER NOT NULL DEFAULT 0,
                checked INTEGER NOT NULL, ms INTEGER)"""
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist TEXT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, grp TEXT NOT NULL,
                logo TEXT, tvg_id TEXT, type INTEGER NOT NULL, ua TEXT, ref TEXT,
                show_key TEXT, series TEXT, season INTEGER, episode INTEGER, norm TEXT, title TEXT, tag TEXT)"""
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
                "INSERT INTO entries(playlist,name,url,grp,logo,tvg_id,type,ua,ref,show_key,series,season,episode,norm,title,tag) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
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
                val d = Normalize.display(e.name)
                st.bindString(14, Normalize.key(d.title))
                st.bindString(15, d.title)
                d.tag?.let { st.bindString(16, it) }
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
            "SELECT show_key, MIN(series), MIN(grp), MAX(logo), COUNT(DISTINCT CASE WHEN episode IS NULL THEN 'u' || id ELSE COALESCE(season, 0) || 'x' || episode END) FROM entries WHERE $where GROUP BY show_key ORDER BY MIN(id) LIMIT $limit",
            args,
        ).use { c -> c.map { Show(it.getString(0), Normalize.displayCached(it.getString(1)).title, it.getString(2), it.getStringOrNull(3), it.getInt(4)) } }
    }

    private fun letterWhere(col: String, letter: Char): Pair<String, Array<String>> =
        if (letter == '#') "NOT (upper(substr($col, 1, 1)) BETWEEN 'A' AND 'Z')" to emptyArray()
        else "$col LIKE ?" to arrayOf("$letter%")

    /** Grade "Todos": só os filmes de uma letra (uma página), para não carregar 100 mil itens de uma vez. */
    fun moviesByLetter(group: String?, letter: Char, limit: Int): List<Entry> {
        val (lw, la) = letterWhere("COALESCE(title, name)", letter)
        val (gw, ga) = if (group == null) "" to emptyArray<String>() else " AND grp = ?" to arrayOf(group)
        return query("type = 1$gw AND $lw ORDER BY COALESCE(title, name) COLLATE NOCASE LIMIT $limit", ga + la)
    }

    fun moviesNewest(group: String?, limit: Int): List<Entry> =
        if (group == null) query("type = 1 ORDER BY id DESC LIMIT $limit", emptyArray())
        else query("type = 1 AND grp = ? ORDER BY id DESC LIMIT $limit", arrayOf(group))

    fun showsPage(group: String?, letter: Char?, limit: Int): List<Show> {
        val (lw, la) = if (letter == null) "1" to emptyArray<String>() else letterWhere("series", letter)
        val (gw, ga) = if (group == null) "" to emptyArray<String>() else " AND grp = ?" to arrayOf(group)
        val order = if (letter == null) "MAX(id) DESC" else "MIN(series) COLLATE NOCASE"
        return readableDatabase.rawQuery(
            "SELECT show_key, MIN(series), MIN(grp), MAX(logo), COUNT(*) FROM entries WHERE type = 2$gw AND $lw GROUP BY show_key ORDER BY $order LIMIT $limit",
            ga + la,
        ).use { c -> c.map { Show(it.getString(0), Normalize.displayCached(it.getString(1)).title, it.getString(2), it.getStringOrNull(3), it.getInt(4)) } }
    }

    /** Lançamentos: filmes com o ano atual ou o anterior no nome, os adicionados por último primeiro. */
    fun recent(limit: Int): List<Entry> {
        val y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return query("type = 1 AND (name LIKE ? OR name LIKE ?) ORDER BY id DESC LIMIT $limit", arrayOf("%$y%", "%${y - 1}%"))
    }

    /** Séries com o ano atual ou o anterior no nome. */
    fun recentShows(limit: Int): List<Show> {
        val y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return readableDatabase.rawQuery(
            "SELECT show_key, MIN(series), MIN(grp), MAX(logo), COUNT(*) FROM entries WHERE type = 2 AND (series LIKE ? OR series LIKE ?) GROUP BY show_key ORDER BY MAX(id) DESC LIMIT $limit",
            arrayOf("%$y%", "%${y - 1}%"),
        ).use { c -> c.map { Show(it.getString(0), Normalize.displayCached(it.getString(1)).title, it.getString(2), it.getStringOrNull(3), it.getInt(4)) } }
    }

    fun episodes(showKey: String): List<Entry> =
        query("show_key = ? ORDER BY COALESCE(season, 0), COALESCE(episode, 99999), id", arrayOf(showKey))

    /**
     * Busca rápida: primeiro os nomes que começam com o texto (usa o índice, sai na hora),
     * depois os que contêm o texto em qualquer parte. Compara sem acento, maiúscula nem espaço.
     */
    fun search(q: String, limit: Int = 300, quick: Boolean = false): Pair<List<Entry>, List<Show>> {
        val n = Normalize.name(q)
        if (n.isEmpty()) {
            val like = "%" + q.replace("%", "").replace("_", "") + "%"
            return query("type != 2 AND name LIKE ? LIMIT $limit", arrayOf(like)) to emptyList()
        }
        val end = n + "\uFFFF"
        val seen = HashSet<String>()
        val entries = ArrayList<Entry>()
        fun add(list: List<Entry>) = list.forEach { if (entries.size < limit && seen.add(it.url)) entries += it }
        // "+type" impede o SQLite de escolher o índice de tipo (que varria a tabela inteira: 35 s na TV)
        for (t in listOf(0, 1)) add(query("norm >= ? AND norm < ? AND +type = $t LIMIT $limit", arrayOf(n, end)))
        if (!quick && entries.size < limit) add(query("type != 2 AND norm LIKE ? LIMIT $limit", arrayOf("%$n%")))
        entries.sortBy { it.type.ordinal }

        fun shows(where: String, args: Array<String>) = readableDatabase.rawQuery(
            "SELECT show_key, MIN(series), MIN(grp), MAX(logo), COUNT(DISTINCT CASE WHEN episode IS NULL THEN 'u' || id ELSE COALESCE(season, 0) || 'x' || episode END) FROM entries WHERE +type = 2 AND $where GROUP BY show_key LIMIT 100", args,
        ).use { c -> c.map { Show(it.getString(0), Normalize.displayCached(it.getString(1)).title, it.getString(2), it.getStringOrNull(3), it.getInt(4)) } }
        val showList = LinkedHashMap<String, Show>()
        shows("show_key >= ? AND show_key < ?", arrayOf(n, end)).forEach { showList[it.key] = it }
        if (!quick && showList.size < 100) shows("show_key LIKE ?", arrayOf("%$n%")).forEach { showList.putIfAbsent(it.key, it) }
        return entries to showList.values.toList()
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
    /** Outras cópias do mesmo episódio (mesma série, temporada e número), em qualquer playlist. */
    fun episodeAlternates(showKey: String, season: Int?, episode: Int, exceptUrl: String): List<Entry> =
        query(
            "type = 2 AND show_key = ? AND COALESCE(season, 0) = ? AND episode = ? AND url != ? ORDER BY id LIMIT 30",
            arrayOf(showKey, (season ?: 0).toString(), episode.toString(), exceptUrl),
        )

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

    /**
     * Preenche nome limpo, etiqueta e chave de quem foi importado antes desta versão, aos poucos.
     * Devolve quantos itens arrumou (0 = acabou).
     */
    fun backfillTitles(batch: Int = 2000): Int {
        val rows = readableDatabase.rawQuery("SELECT id, name FROM entries WHERE title IS NULL LIMIT $batch", null)
            .use { c -> c.map { it.getLong(0) to it.getString(1) } }
        if (rows.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("UPDATE entries SET title = ?, tag = ?, norm = ? WHERE id = ?")
            for ((id, name) in rows) {
                val d = Normalize.display(name)
                st.clearBindings()
                st.bindString(1, d.title)
                d.tag?.let { st.bindString(2, it) }
                st.bindString(3, Normalize.key(d.title))
                st.bindLong(4, id)
                st.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return rows.size
    }

    // ---------- Saúde das fontes ----------

    /** Resultado dos testes de cada link (só dos que já foram testados). */
    fun health(urls: Collection<String>): Map<String, Health> {
        if (urls.isEmpty()) return emptyMap()
        val out = HashMap<String, Health>(urls.size)
        urls.toSet().chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT url, ok, fails, checked, ms FROM health WHERE url IN ($marks)", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = Health(c.getInt(1) == 1, c.getInt(2), c.getLong(3), if (c.isNull(4)) null else c.getLong(4))
            }
        }
        return out
    }

    /** Guarda o resultado de um teste. Falhas seguidas se acumulam; um sucesso zera. */
    fun setHealth(url: String, ok: Boolean, ms: Long?, now: Long = System.currentTimeMillis()) {
        // sem "ON CONFLICT ... DO UPDATE": o SQLite das TVs mais antigas não tem
        val db = writableDatabase
        val okInt = if (ok) 1 else 0
        val st = db.compileStatement(
            """UPDATE health SET ok = ?, checked = ?,
                 fails = CASE WHEN ? = 1 THEN 0 ELSE fails + 1 END,
                 ms = CASE WHEN ? = 1 THEN COALESCE(?, ms) ELSE ms END
               WHERE url = ?"""
        )
        st.bindLong(1, okInt.toLong()); st.bindLong(2, now); st.bindLong(3, okInt.toLong()); st.bindLong(4, okInt.toLong())
        if (ms != null) st.bindLong(5, ms) else st.bindNull(5)
        st.bindString(6, url)
        if (st.executeUpdateDelete() == 0) {
            db.execSQL("INSERT OR IGNORE INTO health(url, ok, fails, checked, ms) VALUES(?, ?, ?, ?, ?)", arrayOf<Any?>(url, okInt, 1 - okInt, now, ms))
        }
    }

    class Probe(val url: String, val userAgent: String?, val referrer: String?)

    /**
     * Próximos links a testar: canais ao vivo primeiro, depois filmes; os nunca testados antes
     * dos antigos. Bons são testados de novo depois de [okAgeMs], ruins depois de [badAgeMs].
     */
    fun toProbe(limit: Int, okAgeMs: Long, badAgeMs: Long, now: Long = System.currentTimeMillis()): List<Probe> =
        readableDatabase.rawQuery(
            """SELECT e.url, MIN(e.ua), MIN(e.ref) FROM entries e LEFT JOIN health h ON h.url = e.url
               WHERE e.type IN (0, 1) AND (h.url IS NULL OR (h.ok = 1 AND h.checked < ?) OR (h.ok = 0 AND h.checked < ?))
               GROUP BY e.url ORDER BY MIN(e.type), h.url IS NOT NULL, MIN(h.checked), MIN(e.id) LIMIT $limit""",
            arrayOf((now - okAgeMs).toString(), (now - badAgeMs).toString()),
        ).use { c -> c.map { Probe(it.getString(0), it.getStringOrNull(1), it.getStringOrNull(2)) } }

    /** Dos nomes dados, quais ainda têm alguma fonte que não está morta (em qualquer categoria). */
    fun aliveNorms(type: ContentType, norms: Collection<String>): Set<String> {
        val out = HashSet<String>()
        norms.toSet().chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                """SELECT DISTINCT e.norm FROM entries e LEFT JOIN health h ON h.url = e.url
                   WHERE e.type = ? AND e.norm IN ($marks) AND (h.url IS NULL OR h.ok = 1 OR h.fails < ${Health.DEAD_FAILS})""",
                arrayOf(type.ordinal.toString()) + chunk,
            ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        }
        return out
    }

    /** Esquece testes de links que não estão mais em nenhuma lista. */
    fun pruneHealth() {
        writableDatabase.execSQL("DELETE FROM health WHERE url NOT IN (SELECT url FROM entries)")
    }

    private fun query(where: String, args: Array<String>): List<Entry> = readableDatabase.rawQuery(
        "SELECT playlist,name,url,grp,logo,tvg_id,type,ua,ref,series,season,episode,title,tag,norm FROM entries WHERE $where", args,
    ).use { c ->
        c.map {
            val title = it.getStringOrNull(12)
            // itens importados antes desta versão: limpa agora (e o preenchimento em segundo plano guarda)
            val d = if (title == null) Normalize.displayCached(it.getString(1)) else null
            Entry(
                playlistId = it.getString(0),
                name = title ?: d!!.title,
                quality = if (title != null) it.getStringOrNull(13) else d!!.tag,
                norm = it.getStringOrNull(14),
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
