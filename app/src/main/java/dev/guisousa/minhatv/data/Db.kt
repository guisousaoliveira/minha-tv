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
        // limpeza feita uma vez na importação: marcas (outro país, adulto), chave de repetido e se é o representante
        if ("hide" !in cols) db.execSQL("ALTER TABLE entries ADD COLUMN hide INTEGER NOT NULL DEFAULT 0")
        if ("mkey" !in cols) db.execSQL("ALTER TABLE entries ADD COLUMN mkey TEXT")
        if ("rep" !in cols) db.execSQL("ALTER TABLE entries ADD COLUMN rep INTEGER NOT NULL DEFAULT 1")
        // quando o item entrou no servidor (Xtream "added"): ordena os lançamentos
        if ("added" !in cols) db.execSQL("ALTER TABLE entries ADD COLUMN added INTEGER")
        // tabela à parte (não some quando as listas são baixadas de novo) e sem mudar a versão do banco
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS health(
                url TEXT PRIMARY KEY, ok INTEGER NOT NULL, fails INTEGER NOT NULL DEFAULT 0,
                checked INTEGER NOT NULL, ms INTEGER)"""
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS tmdb(k TEXT PRIMARY KEY, poster TEXT, backdrop TEXT, overview TEXT, ts INTEGER NOT NULL)")
        // resumo das séries (uma linha por série), refeito só quando as listas mudam: as fileiras leem daqui na hora
        db.execSQL("CREATE TABLE IF NOT EXISTS show_info(show_key TEXT PRIMARY KEY, series TEXT, grp TEXT, logo TEXT, eps INTEGER, min_id INTEGER, max_id INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS show_grp(show_key TEXT NOT NULL, grp TEXT NOT NULL, min_id INTEGER, max_id INTEGER)")
        // resumo das séries de cada lista, feito durante a importação (o resumo geral junta estes, sem ler os episódios de novo)
        db.execSQL("CREATE TABLE IF NOT EXISTS show_part(playlist TEXT NOT NULL, show_key TEXT NOT NULL, series TEXT, grp TEXT, logo TEXT, eps INTEGER, min_id INTEGER, max_id INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS show_part_grp(playlist TEXT NOT NULL, show_key TEXT NOT NULL, grp TEXT NOT NULL, min_id INTEGER, max_id INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS part_done(playlist TEXT PRIMARY KEY)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_show_grp ON show_grp(grp, min_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
        // nomes únicos de canais e filmes, só para a busca "contém" (varre uma tabela pequena em vez da principal)
        db.execSQL("CREATE TABLE IF NOT EXISTS search_names(norm TEXT NOT NULL, type INTEGER NOT NULL, id INTEGER NOT NULL)")
    }

    fun tmdbGet(k: String): Array<String?>? = readableDatabase.rawQuery("SELECT poster, backdrop, overview, ts FROM tmdb WHERE k = ?", arrayOf(k)).use { c ->
        if (c.moveToFirst()) arrayOf(c.getStringOrNull(0), c.getStringOrNull(1), c.getStringOrNull(2), c.getLong(3).toString()) else null
    }

    fun tmdbPut(k: String, poster: String?, backdrop: String?, overview: String?) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO tmdb(k, poster, backdrop, overview, ts) VALUES (?, ?, ?, ?, ?)", arrayOf(k, poster, backdrop, overview, System.currentTimeMillis()))
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist TEXT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, grp TEXT NOT NULL,
                logo TEXT, tvg_id TEXT, type INTEGER NOT NULL, ua TEXT, ref TEXT,
                show_key TEXT, series TEXT, season INTEGER, episode INTEGER, norm TEXT, title TEXT, tag TEXT,
                hide INTEGER NOT NULL DEFAULT 0, mkey TEXT, rep INTEGER NOT NULL DEFAULT 1, added INTEGER)"""
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

    private companion object { const val MAX_LOGOS = 40 }

    class Counts { var live = 0; var movie = 0; var series = 0; var dropped = 0; var hidden = 0 }

    /** Marcas que as telas não mostram agora (some sempre o lixo; outro país e adulto conforme os Ajustes). */
    @Volatile var hideMask = CleanRules.JUNK or CleanRules.FOREIGN or CleanRules.ADULT

    /** false = a lista já vem limpa: guarda como está, sem passar pelas regras. */
    @Volatile var cleanOnImport = true

    /** Só o que está limpo e visível agora (filtro feito pelo banco, sem passar item por item). */
    private val vis get() = "(hide & $hideMask) = 0"

    /** Chave de repetido: mesmo tipo e mesmo título (filmes sem ano também pela capa). */
    private fun mergeKey(type: ContentType, title: String, norm: String, logo: String?, url: String): String? =
        if (type == ContentType.SERIES) null
        else Normalize.mergeKey(Entry(name = title, url = url, logo = logo, type = type, norm = norm))

    /** Troca todos os itens de uma playlist numa transação só (os antigos continuam até terminar). */
    fun replacePlaylist(playlistId: String, fill: ((Entry) -> Unit) -> Unit): Counts {
        val db = writableDatabase
        val counts = Counts()
        db.beginTransaction()
        try {
            db.delete("entries", "playlist = ?", arrayOf(playlistId))
            val st = db.compileStatement(
                "INSERT INTO entries(playlist,name,url,grp,logo,tvg_id,type,ua,ref,show_key,series,season,episode,norm,title,tag,hide,mkey,added) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            val rules = CleanRules.current
            val clean = cleanOnImport
            // listas grandes repetem a mesma categoria e a mesma série em milhares de linhas seguidas: calcula uma vez
            val groupCache = HashMap<String, String>()
            var lastSeries: String? = null
            var lastSeriesClean = ""
            var lastSeriesKey = ""
            val shows = HashMap<String, ShowAgg>()
            fill { e ->
                val group = groupCache.getOrPut(e.group) {
                    val label = Normalize.label(e.group).ifBlank { "Outros" }
                    if (clean) rules.group(label) else label
                }
                // episódio: o nome limpo fica para quando a série for aberta (são centenas de milhares e a tela só mostra os da série)
                val episode = e.type == ContentType.SERIES
                val d = if (episode) null else Normalize.display(e.name)
                val hide = if (clean) rules.classify(e.name + " " + (d?.title ?: ""), group, e.tvgId, e.type) else 0
                if (hide and CleanRules.JUNK != 0) { counts.dropped++; return@fill }
                if (hide != 0) counts.hidden++
                st.clearBindings()
                st.bindString(1, playlistId)
                val cleanName = Normalize.clean(e.name)
                st.bindString(2, cleanName)
                st.bindString(3, e.url)
                st.bindString(4, group)
                e.logo?.let { st.bindString(5, it) }
                e.tvgId?.let { st.bindString(6, it) }
                st.bindLong(7, e.type.ordinal.toLong())
                e.userAgent?.let { st.bindString(8, it) }
                e.referrer?.let { st.bindString(9, it) }
                if (episode) {
                    val raw = e.seriesName ?: e.name
                    if (raw != lastSeries) {
                        lastSeries = raw
                        lastSeriesClean = Normalize.clean(raw)
                        lastSeriesKey = Normalize.showKey(lastSeriesClean)
                    }
                    st.bindString(10, lastSeriesKey)
                    st.bindString(11, lastSeriesClean)
                }
                e.season?.let { st.bindLong(12, it.toLong()) }
                e.episode?.let { st.bindLong(13, it.toLong()) }
                if (d != null) {
                    val norm = Normalize.key(d.title)
                    st.bindString(14, norm)
                    st.bindString(15, d.title)
                    d.tag?.let { st.bindString(16, it) }
                    mergeKey(e.type, d.title, norm, e.logo, e.url)?.let { st.bindString(18, it) }
                }
                st.bindLong(17, hide.toLong())
                e.added?.let { st.bindLong(19, it) }
                val id = st.executeInsert()
                if (episode) {
                    val a = shows[lastSeriesKey] ?: ShowAgg(lastSeriesClean, group, id, id).also { shows[lastSeriesKey] = it }
                    a.add(id, lastSeriesClean, group, e.logo, e.season?.toLong(), e.episode?.toLong(), counted = !Xtream.isStub(e.url))
                }
                when (e.type) {
                    ContentType.LIVE -> counts.live++
                    ContentType.MOVIE -> counts.movie++
                    ContentType.SERIES -> counts.series++
                }
            }
            writeParts(db, playlistId, shows)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return counts
    }

    private fun writeParts(db: SQLiteDatabase, playlistId: String, shows: Map<String, ShowAgg>) {
        db.delete("show_part", "playlist = ?", arrayOf(playlistId))
        db.delete("show_part_grp", "playlist = ?", arrayOf(playlistId))
        val part = db.compileStatement("INSERT INTO show_part(playlist, show_key, series, grp, logo, eps, min_id, max_id) VALUES (?,?,?,?,?,?,?,?)")
        val pg = db.compileStatement("INSERT INTO show_part_grp(playlist, show_key, grp, min_id, max_id) VALUES (?,?,?,?,?)")
        for ((key, a) in shows) {
            part.clearBindings()
            part.bindString(1, playlistId); part.bindString(2, key)
            a.series?.let { part.bindString(3, it) }
            part.bindString(4, a.grp)
            a.bestLogo()?.let { part.bindString(5, it) }
            part.bindLong(6, a.episodeCount().toLong()); part.bindLong(7, a.minId); part.bindLong(8, a.maxId)
            part.executeInsert()
            for ((g, ids) in a.groups) {
                pg.clearBindings()
                pg.bindString(1, playlistId); pg.bindString(2, key); pg.bindString(3, g); pg.bindLong(4, ids[0]); pg.bindLong(5, ids[1])
                pg.executeInsert()
            }
        }
        db.execSQL("INSERT OR REPLACE INTO part_done(playlist) VALUES (?)", arrayOf(playlistId))
    }

    /**
     * Guarda os episódios de uma série Xtream pedidos ao abrir a série (a próxima abertura é instantânea).
     * Somem junto com a lista na próxima atualização dela e são pedidos de novo.
     */
    fun addEpisodes(playlistId: String, showKey: String, series: String, group: String, list: List<Entry>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT INTO entries(playlist,name,url,grp,logo,type,show_key,series,season,episode,ua) VALUES(?,?,?,?,?,2,?,?,?,?,?)"
            )
            for (e in list) {
                st.clearBindings()
                st.bindString(1, playlistId)
                st.bindString(2, Normalize.clean(e.name))
                st.bindString(3, e.url)
                st.bindString(4, group)
                e.logo?.let { st.bindString(5, it) }
                st.bindString(6, showKey)
                st.bindString(7, series)
                e.season?.let { st.bindLong(8, it.toLong()) }
                e.episode?.let { st.bindLong(9, it.toLong()) }
                e.userAgent?.let { st.bindString(10, it) }
                st.executeInsert()
            }
            val n = list.size.toString()
            db.execSQL("UPDATE show_part SET eps = ? WHERE playlist = ? AND show_key = ? AND eps < ?", arrayOf(n, playlistId, showKey, n))
            db.execSQL("UPDATE show_info SET eps = ? WHERE show_key = ? AND eps < ?", arrayOf(n, showKey, n))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Episódios de servidor Xtream ficam guardados depois da primeira abertura. Quando o jeito de ler
     * esses episódios muda (ex.: passar a achar a versão Legendada), os guardados com a leitura antiga
     * são apagados uma vez, e a série busca de novo no servidor na próxima vez que for aberta.
     */
    fun dropFetchedEpisodes(version: String, playlistIds: Collection<String>) {
        val key = "xtream_eps_v"
        val now = readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (now == version) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in playlistIds) {
                db.execSQL("DELETE FROM entries WHERE playlist = ? AND +type = 2 AND url NOT LIKE 'xtream-series://%'", arrayOf(id))
            }
            db.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES (?, ?)", arrayOf(key, version))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deletePlaylist(playlistId: String) {
        writableDatabase.delete("entries", "playlist = ?", arrayOf(playlistId))
        writableDatabase.delete("show_part", "playlist = ?", arrayOf(playlistId))
        writableDatabase.delete("show_part_grp", "playlist = ?", arrayOf(playlistId))
        writableDatabase.delete("part_done", "playlist = ?", arrayOf(playlistId))
    }

    /** Apaga itens de playlists que não existem mais (só olha o índice, é rápido). */
    fun deleteExcept(keep: Collection<String>) {
        val present = readableDatabase.rawQuery("SELECT DISTINCT playlist FROM entries", null).use { c -> c.map { it.getString(0) } }
        present.filter { it !in keep }.forEach { deletePlaylist(it) }
    }

    fun groups(type: ContentType): List<GroupInfo> = readableDatabase.rawQuery(
        "SELECT grp, COUNT(*) FROM entries WHERE type = ? AND rep = 1 AND $vis GROUP BY grp ORDER BY MIN(id)",
        arrayOf(type.ordinal.toString()),
    ).use { c -> c.map { GroupInfo(it.getString(0), it.getInt(1)) } }

    /**
     * Categorias de canais com a conta certa, direto do banco: sem os escondidos e sem repetidos
     * (o mesmo canal de várias listas conta uma vez). Categoria vazia nem aparece.
     */
    fun liveGroups(): List<GroupInfo> = readableDatabase.rawQuery(
        "SELECT grp, COUNT(DISTINCT COALESCE(norm, url)) FROM entries WHERE type = 0 AND $vis GROUP BY grp ORDER BY MIN(id)", null,
    ).use { c -> c.map { GroupInfo(it.getString(0), it.getInt(1)) } }

    /**
     * Marca o representante de cada título repetido (um por categoria): as telas leem só esses.
     * Roda uma vez depois de importar (junto com o resumo das séries).
     */
    private fun markRepresentatives(db: SQLiteDatabase) {
        db.execSQL("UPDATE entries SET rep = 0 WHERE type != 2 AND rep != 0")
        db.execSQL(
            "UPDATE entries SET rep = 1 WHERE id IN (SELECT MIN(id) FROM entries WHERE type != 2 GROUP BY type, grp, hide, COALESCE(mkey, url))"
        )
    }

    /**
     * Aplica regras novas no que já está no banco (sem baixar a lista de novo): marcas, categorias
     * juntadas e itens que agora somem sempre. Devolve quantos itens mudaram.
     */
    fun reclassify(rules: CleanRules): Int {
        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            val upd = db.compileStatement("UPDATE entries SET hide = ?, grp = ?, mkey = ? WHERE id = ?")
            val del = db.compileStatement("DELETE FROM entries WHERE id = ?")
            db.rawQuery("SELECT id, name, COALESCE(title, name), grp, tvg_id, type, COALESCE(norm, ''), logo, url, hide, mkey FROM entries", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val type = ContentType.entries[c.getInt(5)]
                    val title = c.getString(2)
                    val oldGroup = c.getString(3)
                    val group = rules.group(oldGroup)
                    val hide = rules.classify(c.getString(1) + " " + title, group, c.getStringOrNull(4), type)
                    if (hide and CleanRules.JUNK != 0) { del.bindLong(1, id); del.executeUpdateDelete(); changed++; continue }
                    val norm = c.getString(6).ifEmpty { Normalize.key(title) }
                    val mkey = mergeKey(type, title, norm, c.getStringOrNull(7), c.getString(8))
                    if (hide == c.getInt(9) && group == oldGroup && mkey == c.getStringOrNull(10)) continue
                    upd.clearBindings()
                    upd.bindLong(1, hide.toLong()); upd.bindString(2, group)
                    mkey?.let { upd.bindString(3, it) }
                    upd.bindLong(4, id)
                    upd.executeUpdateDelete()
                    changed++
                }
            }
            // força refazer representantes, resumo das séries e busca na próxima organização
            db.execSQL("DELETE FROM meta WHERE k = 'show_sig'")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return changed
    }

    /** Quantos itens há e quantos aparecem nas telas agora (para o registro de tempos). */
    fun cleanStats(): Pair<Int, Int> = readableDatabase.rawQuery(
        "SELECT COUNT(*), SUM(CASE WHEN rep = 1 AND $vis THEN 1 ELSE 0 END) FROM entries", null,
    ).use { c -> c.moveToFirst(); c.getInt(0) to c.getInt(1) }

    fun showGroups(): List<GroupInfo> = if (summaryReady) readableDatabase.rawQuery(
        "SELECT grp, COUNT(*) FROM show_grp GROUP BY grp ORDER BY MIN(min_id)", null,
    ).use { c -> c.map { GroupInfo(it.getString(0), it.getInt(1)) } } else readableDatabase.rawQuery(
        "SELECT grp, COUNT(DISTINCT show_key) FROM entries WHERE type = 2 GROUP BY grp ORDER BY MIN(id)", null,
    ).use { c -> c.map { GroupInfo(it.getString(0), it.getInt(1)) } }

    fun entries(type: ContentType, group: String?, limit: Int = 60_000): List<Entry> {
        val (where, args) = if (group == null) "type = ?" to arrayOf(type.ordinal.toString())
        else "type = ? AND grp = ?" to arrayOf(type.ordinal.toString(), group)
        return query("$where AND rep = 1 AND $vis ORDER BY id LIMIT $limit", args)
    }

    /**
     * Séries em 2 passos (rápido mesmo em categorias com dezenas de milhares de episódios):
     * 1) só as chaves das séries, na ordem e com o limite; 2) os detalhes só dessas séries, pelo índice.
     */
    private fun showsByKeys(keys: List<String>): List<Show> {
        if (keys.isEmpty()) return emptyList()
        if (summaryReady) {
            val info = HashMap<String, Show>()
            keys.chunked(400).forEach { chunk ->
                summaryShows("SELECT $infoCols FROM show_info i WHERE i.show_key IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).forEach { info[it.key] = it }
            }
            return keys.mapNotNull { info[it] }
        }
        val info = HashMap<String, Show>()
        keys.chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            val args = chunk.toTypedArray()
            val logos = HashMap<String, Pair<String, Int>>()
            // capa = a imagem que mais se repete entre os episódios (as outras costumam ser quadros do episódio)
            readableDatabase.rawQuery(
                "SELECT show_key, logo, COUNT(*) FROM entries WHERE show_key IN ($marks) AND logo IS NOT NULL GROUP BY show_key, logo", args,
            ).use { c -> while (c.moveToNext()) { val k = c.getString(0); val n = c.getInt(2); if ((logos[k]?.second ?: -1) < n) logos[k] = c.getString(1) to n } }
            readableDatabase.rawQuery(
                "SELECT show_key, MIN(series), MIN(grp), COUNT(DISTINCT CASE WHEN episode IS NULL THEN 'u' || id ELSE COALESCE(season, 0) || 'x' || episode END) FROM entries WHERE show_key IN ($marks) AND +type = 2 GROUP BY show_key", args,
            ).use { c -> while (c.moveToNext()) { val k = c.getString(0); info[k] = Show(k, Normalize.displayCached(c.getString(1)).title, c.getString(2), logos[k]?.first, c.getInt(3)) } }
        }
        return keys.mapNotNull { info[it] }
    }

    private fun keys(sql: String, args: Array<String>): List<String> =
        readableDatabase.rawQuery(sql, args).use { c -> c.map { it.getString(0) } }.filterNotNull()

    /** true quando o resumo das séries bate com o que está no banco (senão as consultas antigas são usadas). */
    @Volatile var summaryReady = false
        private set

    /**
     * Refaz o resumo das séries se as listas mudaram (3 varreduras do banco, uma vez por importação).
     * Depois disso cada fileira de séries é uma consulta pequena, em vez de agrupar dezenas de milhares de episódios.
     */
    /** Uma série no resumo, montada numa passada só pelo banco. */
    private class ShowAgg(var series: String?, var grp: String, var minId: Long, var maxId: Long) {
        val eps = HashSet<Long>()
        var unnumbered = 0
        /** contagem de capas (no máximo [MAX_LOGOS] diferentes: a capa da série aparece logo nos primeiros episódios) */
        val logos = HashMap<String, Int>()
        val groups = LinkedHashMap<String, LongArray>()
        /** contagem vinda de um resumo de lista (quando o resumo geral é montado pelos resumos) */
        var partEps = 0

        /** [counted] = false: linha da capa de uma série Xtream (os episódios só vêm quando ela é aberta). */
        fun add(id: Long, series: String?, grp: String, logo: String?, season: Long?, episode: Long?, counted: Boolean = true) {
            if (series != null && (this.series == null || series < this.series!!)) this.series = series
            if (grp < this.grp) this.grp = grp
            if (id < minId) minId = id
            if (id > maxId) maxId = id
            if (counted) { if (episode == null) unnumbered++ else eps += ((season ?: 0L) shl 20) or episode }
            if (logo != null) {
                val n = logos[logo]
                if (n != null) logos[logo] = n + 1 else if (logos.size < MAX_LOGOS) logos[logo] = 1
            }
            val g = groups[grp]
            if (g == null) groups[grp] = longArrayOf(id, id) else { if (id < g[0]) g[0] = id; if (id > g[1]) g[1] = id }
        }

        fun episodeCount() = maxOf(partEps, eps.size + unnumbered)

        /** capa = a imagem que mais se repete entre os episódios (as outras costumam ser quadros do episódio) */
        fun bestLogo() = logos.maxByOrNull { it.value }?.key
    }

    /** true se toda lista com itens no banco tem o resumo de séries feito na importação. */
    private fun partsComplete(): Boolean {
        val present = readableDatabase.rawQuery("SELECT DISTINCT playlist FROM entries", null).use { c -> c.map { it.getString(0) } }
        val done = readableDatabase.rawQuery("SELECT playlist FROM part_done", null).use { c -> c.map { it.getString(0) } }.toSet()
        return present.all { it in done }
    }

    /** Resumo geral a partir dos resumos de cada lista (milhares de linhas em vez de centenas de milhares). */
    private fun showsFromParts(): HashMap<String, ShowAgg> {
        val shows = HashMap<String, ShowAgg>()
        val bestEps = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT show_key, series, grp, logo, eps, min_id, max_id FROM show_part", null).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                val series = c.getStringOrNull(1)
                val grp = c.getStringOrNull(2) ?: "Outros"
                val eps = c.getInt(4)
                val a = shows[key] ?: ShowAgg(series, grp, c.getLong(5), c.getLong(6)).also { shows[key] = it }
                if (series != null && (a.series == null || series < a.series!!)) a.series = series
                if (grp < a.grp) a.grp = grp
                a.minId = minOf(a.minId, c.getLong(5)); a.maxId = maxOf(a.maxId, c.getLong(6))
                // capa e contagem da lista que tem mais episódios dessa série
                if (eps > (bestEps[key] ?: -1)) {
                    bestEps[key] = eps
                    a.partEps = eps
                    a.logos.clear()
                    c.getStringOrNull(3)?.let { a.logos[it] = 1 }
                }
            }
        }
        readableDatabase.rawQuery("SELECT show_key, grp, MIN(min_id), MAX(max_id) FROM show_part_grp GROUP BY show_key, grp", null).use { c ->
            while (c.moveToNext()) shows[c.getString(0)]?.groups?.put(c.getString(1), longArrayOf(c.getLong(2), c.getLong(3)))
        }
        return shows
    }

    fun refreshShowSummary(): Boolean {
        val sig = "v5:" + readableDatabase.rawQuery("SELECT COUNT(*), MAX(id) FROM entries", null).use { c -> c.moveToFirst(); "${c.getLong(0)}:${c.getLong(1)}" }
        val old = readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'show_sig'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (old == sig) { summaryReady = true; return false }
        summaryReady = false
        fixShowKeys()
        val db = writableDatabase
        val t0 = System.currentTimeMillis()
        val fromParts = partsComplete()
        val shows = if (fromParts) showsFromParts() else HashMap<String, ShowAgg>().also { shows ->
            // banco de versões antigas: uma leitura em ordem dos episódios (só até a próxima importação)
            db.rawQuery("SELECT id, show_key, series, grp, logo, season, episode FROM entries WHERE +type = 2 AND show_key IS NOT NULL", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val key = c.getString(1)
                    val series = c.getStringOrNull(2)
                    val grp = c.getString(3)
                    val a = shows[key] ?: ShowAgg(series, grp, id, id).also { shows[key] = it }
                    a.add(id, series, grp, c.getStringOrNull(4), if (c.isNull(5)) null else c.getLong(5), if (c.isNull(6)) null else c.getLong(6))
                }
            }
        }
        val t1 = System.currentTimeMillis()
        db.beginTransaction()
        try {
            markRepresentatives(db)
            db.execSQL("DELETE FROM show_info")
            db.execSQL("DELETE FROM show_grp")
            db.execSQL("DELETE FROM search_names")
            db.execSQL("INSERT INTO search_names SELECT norm, type, MIN(id) FROM entries WHERE +type != 2 AND norm IS NOT NULL GROUP BY norm, type")
            val info = db.compileStatement("INSERT INTO show_info(show_key, series, grp, logo, eps, min_id, max_id) VALUES (?,?,?,?,?,?,?)")
            val grpSt = db.compileStatement("INSERT INTO show_grp(show_key, grp, min_id, max_id) VALUES (?,?,?,?)")
            for ((key, a) in shows) {
                info.clearBindings()
                info.bindString(1, key)
                a.series?.let { info.bindString(2, it) }
                info.bindString(3, a.grp)
                // capa = a imagem que mais se repete entre os episódios (as outras costumam ser quadros do episódio)
                a.bestLogo()?.let { info.bindString(4, it) }
                info.bindLong(5, a.episodeCount().toLong())
                info.bindLong(6, a.minId)
                info.bindLong(7, a.maxId)
                info.executeInsert()
                for ((g, ids) in a.groups) {
                    grpSt.clearBindings()
                    grpSt.bindString(1, key); grpSt.bindString(2, g); grpSt.bindLong(3, ids[0]); grpSt.bindLong(4, ids[1])
                    grpSt.executeInsert()
                }
            }
            db.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES ('show_sig', ?)", arrayOf(sig))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        android.util.Log.i("MinhaTV", "tempo: resumo de ${shows.size} séries (${if (fromParts) "pelos resumos das listas" else "lendo os episódios"}): leitura ${t1 - t0} ms, gravação ${System.currentTimeMillis() - t1} ms")
        summaryReady = true
        return true
    }

    /**
     * Séries que só diferem pelo ano entre parênteses ("Smallville" e "Smallville (2001)") viram
     * uma só. Importações novas já saem assim; isto arruma o que foi importado antes (uma vez).
     */
    private fun fixShowKeys() {
        val done = readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'show_key_v'", null).use { c -> c.moveToFirst() && c.getString(0) == "3" }
        if (done) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val pairs = db.rawQuery("SELECT DISTINCT show_key, series FROM entries WHERE +type = 2", null)
                .use { c -> c.map { it.getStringOrNull(0) to it.getStringOrNull(1) } }
            val upd = db.compileStatement("UPDATE entries SET show_key = ? WHERE show_key = ? AND series = ?")
            for ((key, series) in pairs) {
                if (key == null || series == null) continue
                val fixed = Normalize.showKey(series)
                if (fixed == key) continue
                upd.bindString(1, fixed); upd.bindString(2, key); upd.bindString(3, series)
                upd.executeUpdateDelete()
            }
            db.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES ('show_key_v', '3')")
            // chaves mudaram: os resumos por lista ficaram velhos (refeitos na próxima importação)
            db.execSQL("DELETE FROM part_done")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun summaryShows(sql: String, args: Array<String>): List<Show> =
        readableDatabase.rawQuery(sql, args).use { c ->
            c.map { Show(it.getString(0), Normalize.displayCached(it.getString(1) ?: "").title, it.getString(2), if (it.isNull(3)) null else it.getString(3), it.getInt(4)) }
        }

    private val infoCols = "i.show_key, i.series, i.grp, i.logo, i.eps"

    fun shows(group: String?, limit: Int = 20_000): List<Show> {
        if (summaryReady) return if (group == null) summaryShows("SELECT $infoCols FROM show_info i ORDER BY i.min_id LIMIT $limit", emptyArray())
        else summaryShows("SELECT $infoCols FROM show_grp g JOIN show_info i ON i.show_key = g.show_key WHERE g.grp = ? ORDER BY g.min_id LIMIT $limit", arrayOf(group))
        val (where, args) = if (group == null) "type = 2" to emptyArray<String>() else "type = 2 AND grp = ?" to arrayOf(group)
        return showsByKeys(keys("SELECT show_key FROM entries INDEXED BY idx_type_grp WHERE $where GROUP BY show_key ORDER BY MIN(id) LIMIT $limit", args))
    }

    private fun letterWhere(col: String, letter: Char): Pair<String, Array<String>> =
        if (letter == '#') "NOT (upper(substr($col, 1, 1)) BETWEEN 'A' AND 'Z')" to emptyArray()
        else "$col LIKE ?" to arrayOf("$letter%")

    /** Grade "Todos": só os filmes de uma letra (uma página), para não carregar 100 mil itens de uma vez. */
    fun moviesByLetter(group: String?, letter: Char, limit: Int): List<Entry> {
        val (lw, la) = letterWhere("COALESCE(title, name)", letter)
        val (gw, ga) = if (group == null) "" to emptyArray<String>() else " AND grp = ?" to arrayOf(group)
        return query("type = 1$gw AND $lw AND rep = 1 AND $vis ORDER BY COALESCE(title, name) COLLATE NOCASE LIMIT $limit", ga + la)
    }

    fun moviesNewest(group: String?, limit: Int): List<Entry> =
        if (group == null) query("type = 1 AND rep = 1 AND $vis ORDER BY id DESC LIMIT $limit", emptyArray())
        else query("type = 1 AND grp = ? AND rep = 1 AND $vis ORDER BY id DESC LIMIT $limit", arrayOf(group))

    fun showsPage(group: String?, letter: Char?, limit: Int): List<Show> {
        if (summaryReady) {
            val (lw, la) = if (letter == null) "1" to emptyArray<String>() else letterWhere("i.series", letter)
            val order = if (letter == null) "i.max_id DESC" else "i.series COLLATE NOCASE"
            return if (group == null) summaryShows("SELECT $infoCols FROM show_info i WHERE $lw ORDER BY $order LIMIT $limit", la)
            else summaryShows("SELECT $infoCols FROM show_grp g JOIN show_info i ON i.show_key = g.show_key WHERE g.grp = ? AND $lw ORDER BY $order LIMIT $limit", arrayOf(group) + la)
        }
        val (lw, la) = if (letter == null) "1" to emptyArray<String>() else letterWhere("series", letter)
        val (gw, ga) = if (group == null) "" to emptyArray<String>() else " AND grp = ?" to arrayOf(group)
        val order = if (letter == null) "MAX(id) DESC" else "MIN(series) COLLATE NOCASE"
        return showsByKeys(keys("SELECT show_key FROM entries INDEXED BY idx_type_grp WHERE type = 2$gw AND $lw GROUP BY show_key ORDER BY $order LIMIT $limit", ga + la))
    }

    /**
     * Lançamentos: de preferência os filmes da categoria "Lançamentos" do servidor; senão, os com o
     * ano atual ou o anterior no nome. Os que chegaram por último ao servidor (added) vêm primeiro.
     * (Antes era só a ordem de importação, que trazia o fim da lista: títulos legendados em hindi/tâmil.)
     */
    fun recent(limit: Int): List<Entry> {
        val y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val order = "ORDER BY COALESCE(added, 0) DESC, (name LIKE ?) DESC, id DESC LIMIT $limit"
        val cat = query("type = 1 AND grp LIKE '%lan_amento%' AND rep = 1 AND $vis $order", arrayOf("%$y%"))
        if (cat.size >= 12) return cat
        return query("type = 1 AND (name LIKE ? OR name LIKE ?) AND rep = 1 AND $vis $order", arrayOf("%$y%", "%${y - 1}%", "%$y%"))
    }

    /** Filmes da lista com estas chaves exatas (títulos em alta do TMDB), na ordem pedida. */
    fun moviesByKeys(keys: List<String>): List<Entry> {
        if (keys.isEmpty()) return emptyList()
        val found = HashMap<String, Entry>()
        keys.distinct().chunked(400).forEach { chunk ->
            query("norm IN (${chunk.joinToString(",") { "?" }}) AND +type = 1 AND rep = 1 AND $vis", chunk.toTypedArray())
                .forEach { e -> e.norm?.let { found.putIfAbsent(it, e) } }
        }
        return keys.mapNotNull { found[it] }
    }

    /** A playlist tem itens guardados (o banco pode ter sido recriado). */
    fun hasPlaylist(playlistId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM entries WHERE playlist = ? LIMIT 1", arrayOf(playlistId)).use { it.moveToFirst() }

    /** Séries com o ano atual ou o anterior no nome. */
    fun recentShows(limit: Int): List<Show> {
        val y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        if (summaryReady) return summaryShows("SELECT $infoCols FROM show_info i WHERE i.series LIKE ? OR i.series LIKE ? ORDER BY i.max_id DESC LIMIT $limit", arrayOf("%$y%", "%${y - 1}%"))
        return showsByKeys(keys(
            "SELECT show_key FROM entries INDEXED BY idx_type_grp WHERE type = 2 AND (series LIKE ? OR series LIKE ?) GROUP BY show_key ORDER BY MAX(id) DESC LIMIT $limit",
            arrayOf("%$y%", "%${y - 1}%"),
        ))
    }

    /** Filmes da lista cujo nome começa com [norm] (para achar os títulos parecidos do TMDB). */
    fun movieByNorm(norm: String): Entry? =
        if (norm.length < 3) null else query("norm >= ? AND norm < ? AND +type = 1 AND $vis LIMIT 1", arrayOf(norm, norm + "\uFFFF")).firstOrNull()

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
            return query("type != 2 AND name LIKE ? AND $vis LIMIT $limit", arrayOf(like)) to emptyList()
        }
        val end = n + "\uFFFF"
        val seen = HashSet<String>()
        val entries = ArrayList<Entry>()
        fun add(list: List<Entry>) = list.forEach { if (entries.size < limit && seen.add(it.url)) entries += it }
        // "+type" impede o SQLite de escolher o índice de tipo (que varria a tabela inteira: 35 s na TV)
        for (t in listOf(0, 1)) add(query("norm >= ? AND norm < ? AND +type = $t AND $vis LIMIT $limit", arrayOf(n, end)))
        if (!quick && entries.size < limit) {
            if (summaryReady) {
                val ids = keys("SELECT id FROM search_names WHERE norm LIKE ? LIMIT $limit", arrayOf("%$n%"))
                ids.chunked(400).forEach { ch -> add(query("id IN (${ch.joinToString(",")}) AND $vis", emptyArray())) }
            } else add(query("type != 2 AND norm LIKE ? AND $vis LIMIT $limit", arrayOf("%$n%")))
        }
        entries.sortBy { it.type.ordinal }

        fun shows(where: String, args: Array<String>) = showsByKeys(keys(
            if (summaryReady) "SELECT show_key FROM show_info WHERE $where LIMIT 100" else "SELECT DISTINCT show_key FROM entries WHERE +type = 2 AND $where LIMIT 100", args))
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
        val rows = readableDatabase.rawQuery("SELECT id, name FROM entries WHERE title IS NULL AND type != 2 LIMIT $batch", null)
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
        "SELECT playlist,name,url,grp,logo,tvg_id,type,ua,ref,series,season,episode,title,tag,norm,hide,added FROM entries WHERE $where", args,
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
                hidden = it.getInt(15),
                added = if (it.isNull(16)) null else it.getLong(16),
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
