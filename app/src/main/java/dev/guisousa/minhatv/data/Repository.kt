package dev.guisousa.minhatv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@OptIn(ExperimentalSerializationApi::class)
object Repository {

    const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"
    val INTERVAL_OPTIONS = listOf(3, 6, 12, 24)

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private lateinit var db: Db
    private var version = 0L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists
    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources
    private val _library = MutableStateFlow(Library.EMPTY)
    val library: StateFlow<Library> = _library
    private val _epg = MutableStateFlow(EpgData.EMPTY)
    val epg: StateFlow<EpgData> = _epg
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites
    private val _status = MutableStateFlow<String?>(null)
    /** Mensagem de progresso ("Atualizando…"), ou null quando parado. */
    val status: StateFlow<String?> = _status
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("minhatv", Context.MODE_PRIVATE)
        db = Db(app)
        _favorites.value = prefs.getStringSet("favorites", emptySet())!!.toSet()
        scope.launch {
            // 1) rápido: listas salvas + resumo das categorias da última vez -> a tela abre na hora
            try {
                mutex.withLock {
                    _playlists.value = readList(file("playlists.json"), Playlist.serializer())
                    _sources.value = readList(file("sources.json"), Source.serializer())
                }
                readLibraryCache()?.let { _library.value = it }
            } finally {
                _isLoading.value = false
            }
            // 2) em segundo plano: abre o banco, arruma o que precisar e carrega o guia
            try {
                val hadCache = _library.value !== Library.EMPTY
                if (!hadCache) _status.value = "Preparando…"
                mutex.withLock {
                    // versão 1.0.1 guardava os itens em JSON: apaga e força baixar de novo para o banco
                    val legacy = app.filesDir.listFiles { f -> f.name.startsWith("items_") }.orEmpty()
                    if (legacy.isNotEmpty()) {
                        legacy.forEach { it.delete() }
                        _playlists.value = _playlists.value.map { it.copy(lastUpdated = 0) }
                        _sources.value = _sources.value.map { it.copy(lastChecked = 0) }
                        savePlaylists(); saveSources()
                    }
                    db.writableDatabase // abre (e atualiza o formato do banco, se preciso)
                    if (db.wasReset) {
                        _playlists.value = _playlists.value.map { it.copy(lastUpdated = 0) }
                        _sources.value = _sources.value.map { it.copy(lastChecked = 0) }
                        savePlaylists(); saveSources()
                    }
                    db.deleteExcept(_playlists.value.map { it.id })
                }
                if (!hadCache || db.wasReset) rebuild() else loadEpg()
            } catch (e: Exception) {
                android.util.Log.e("MinhaTV", "init", e)
            } finally {
                _status.value = null
                loaded.complete(Unit)
            }
        }
    }

    @kotlinx.serialization.Serializable
    private data class LibraryCache(val live: List<GroupInfo>, val movie: List<GroupInfo>, val show: List<GroupInfo>)

    private fun readLibraryCache(): Library? = runCatching {
        val f = file("library_cache.json")
        if (!f.exists()) return null
        val c = f.inputStream().buffered().use { json.decodeFromStream(LibraryCache.serializer(), it) }
        Library(++version, c.live, c.movie, c.show)
    }.getOrNull()

    private fun writeLibraryCache(l: Library) = runCatching {
        val tmp = file("library_cache.json.tmp")
        tmp.outputStream().buffered().use { json.encodeToStream(LibraryCache.serializer(), LibraryCache(l.liveGroups, l.movieGroups, l.showGroups), it) }
        tmp.renameTo(file("library_cache.json"))
    }

    private fun loadEpg() {
        var epg = EpgData.EMPTY
        for (p in _playlists.value) {
            val f = file("epg_${p.id}.json")
            if (f.exists()) runCatching {
                f.inputStream().buffered().use { json.decodeFromStream(EpgData.serializer(), it) }
            }.getOrNull()?.let { epg = epg.merge(it) }
        }
        _epg.value = epg
    }

    suspend fun awaitLoaded() = loaded.await()

    // ---------- Configurações ----------

    var refreshHours: Int
        get() = prefs.getInt("refresh_hours", 12)
        set(v) { prefs.edit().putInt("refresh_hours", v).apply() }

    fun toggleFavorite(url: String): Boolean {
        val now = _favorites.value.toMutableSet()
        val added = if (url in now) { now.remove(url); false } else { now.add(url); true }
        _favorites.value = now
        prefs.edit().putStringSet("favorites", now).apply()
        return added
    }

    fun getResume(url: String): Long = prefs.getLong("resume_" + url.hashCode(), 0)

    fun saveResume(url: String, position: Long, duration: Long) {
        val key = "resume_" + url.hashCode()
        if (position > 30_000 && (duration <= 0 || position < duration - 60_000)) {
            prefs.edit().putLong(key, position).putLong("dur_" + url.hashCode(), duration).apply()
        } else {
            prefs.edit().remove(key).remove("dur_" + url.hashCode()).apply()
        }
    }

    /** Quanto do filme/episódio já foi visto (0..1), ou null se não começou. */
    fun progress(url: String): Float? {
        val pos = getResume(url)
        val dur = prefs.getLong("dur_" + url.hashCode(), 0)
        return if (pos <= 0 || dur <= 0) null else pos.toFloat() / dur
    }

    /** Ao abrir o app, já começa a tocar o último canal assistido (como uma TV comum). */
    var autoplayLast: Boolean
        get() = prefs.getBoolean("autoplay_last", false)
        set(v) { prefs.edit().putBoolean("autoplay_last", v).apply() }

    /** Esconde categorias adultas (ligado por padrão). */
    var hideAdult: Boolean
        get() = prefs.getBoolean("hide_adult", true)
        set(v) { prefs.edit().putBoolean("hide_adult", v).apply(); scope.launch { rebuild() } }

    /** Formato da tela do player (AspectRatioFrameLayout.RESIZE_MODE_*). */
    var resizeMode: Int
        get() = prefs.getInt("resize_mode", 0)
        set(v) { prefs.edit().putInt("resize_mode", v).apply() }

    var lastChannelUrl: String?
        get() = prefs.getString("last_channel", null)
        set(v) { prefs.edit().putString("last_channel", v).apply() }

    // ---------- Adicionar / remover ----------

    /**
     * Adiciona qualquer link: se for uma playlist M3U, adiciona direto;
     * se for uma página (README, site), procura todas as playlists dentro dela
     * e guarda a página para buscar listas novas nas próximas atualizações.
     */
    fun addFromUrl(rawUrl: String, name: String? = null, epgUrl: String? = null, onDone: (String) -> Unit = {}) {
        scope.launch {
            val msg = try {
                awaitLoaded()
                addFromUrlInternal(rawUrl.trim(), name?.trim()?.ifBlank { null }, epgUrl?.trim()?.ifBlank { null })
            } catch (e: Exception) {
                "Erro: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _status.value = null
            }
            withContext(Dispatchers.Main) { onDone(msg) }
        }
    }

    private suspend fun addFromUrlInternal(input: String, name: String?, epgUrl: String?): String {
        require(input.startsWith("http://") || input.startsWith("https://")) { "o link precisa começar com http:// ou https://" }
        val url = LinkExtractor.toRaw(input)
        _status.value = "Lendo link…"
        val head = fetchHead(url)
        if (M3uParser.isChannelPlaylist(head) || M3uParser.isHlsStream(head)) {
            if (_playlists.value.any { it.url == url }) return "Essa playlist já estava adicionada."
            val p = Playlist(UUID.randomUUID().toString(), name ?: LinkExtractor.nameFromUrl(url), url, epgUrl)
            mutex.withLock { _playlists.value = _playlists.value + p; savePlaylists() }
            refreshPlaylists(listOf(p.id))
            val updated = _playlists.value.firstOrNull { it.id == p.id }
            return updated?.lastError?.let { "Playlist adicionada, mas deu erro: $it" }
                ?: "Playlist \"${p.name}\" adicionada: ${updated?.liveCount ?: 0} canais, ${updated?.movieCount ?: 0} filmes, ${updated?.seriesCount ?: 0} episódios."
        }
        // É uma página: procura playlists dentro dela.
        if (_sources.value.any { it.url == url }) {
            refreshAll()
            return "Essa página já estava adicionada. Atualizei as listas."
        }
        val src = Source(UUID.randomUUID().toString(), url)
        mutex.withLock { _sources.value = _sources.value + src; saveSources() }
        val added = scanSource(src)
        if (added.isEmpty()) {
            val found = _sources.value.firstOrNull { it.id == src.id }?.found ?: 0
            return if (found == 0) "Não encontrei nenhuma playlist M3U nessa página." else "Nenhuma playlist nova na página."
        }
        refreshPlaylists(added)
        val ok = _playlists.value.count { it.id in added && it.lastError == null }
        return "Encontrei ${added.size} playlists na página ($ok carregaram). Vou verificar essa página de novo a cada atualização."
    }

    /** Lê a página e cria playlists para os links novos. Devolve os ids criados. */
    private suspend fun scanSource(src: Source): List<String> {
        return try {
            _status.value = "Procurando playlists na página…"
            val links = LinkedHashSet<String>()
            LinkExtractor.githubRepo(src.url)?.let { (owner, repo) ->
                links += githubPlaylists(owner, repo)
            }
            val pageError = runCatching {
                links += LinkExtractor.extract(src.url, fetchText(src.url, maxChars = 2_000_000))
            }.exceptionOrNull()
            if (links.isEmpty() && pageError != null) throw pageError
            val known = _playlists.value.map { it.url }.toSet()
            val fresh = links.filter { it !in known }.map {
                Playlist(UUID.randomUUID().toString(), LinkExtractor.nameFromUrl(it), it, sourceId = src.id)
            }
            mutex.withLock {
                _playlists.value = _playlists.value + fresh
                _sources.value = _sources.value.map {
                    if (it.id == src.id) it.copy(lastChecked = System.currentTimeMillis(), lastError = null, found = links.size) else it
                }
                savePlaylists(); saveSources()
            }
            fresh.map { it.id }
        } catch (e: Exception) {
            mutex.withLock {
                _sources.value = _sources.value.map { if (it.id == src.id) it.copy(lastError = e.message ?: "erro") else it }
                saveSources()
            }
            emptyList()
        }
    }

    /** Lista os arquivos .m3u/.m3u8 de um repositório do GitHub (pela API pública). */
    private fun githubPlaylists(owner: String, repo: String): List<String> = runCatching {
        val text = fetchText(LinkExtractor.githubTreeApi(owner, repo), maxChars = 5_000_000)
        Json.parseToJsonElement(text).jsonObject["tree"]!!.jsonArray.mapNotNull { node ->
            val o = node.jsonObject
            val path = o["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (o["type"]?.jsonPrimitive?.content != "blob") return@mapNotNull null
            val lower = path.lowercase()
            if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) LinkExtractor.githubRawFile(owner, repo, path) else null
        }
    }.getOrDefault(emptyList())

    fun removePlaylist(id: String) {
        scope.launch {
            mutex.withLock {
                _playlists.value = _playlists.value.filterNot { it.id == id }
                savePlaylists()
                db.deletePlaylist(id)
                file("epg_$id.json").delete()
            }
            rebuild()
        }
    }

    /** Remove a página e todas as playlists que vieram dela. */
    fun removeSource(id: String) {
        scope.launch {
            mutex.withLock {
                val gone = _playlists.value.filter { it.sourceId == id }
                gone.forEach { db.deletePlaylist(it.id); file("epg_${it.id}.json").delete() }
                _playlists.value = _playlists.value.filterNot { it.sourceId == id }
                _sources.value = _sources.value.filterNot { it.id == id }
                savePlaylists(); saveSources()
            }
            rebuild()
        }
    }

    // ---------- Atualização ----------

    fun refreshAllAsync() { scope.launch { refreshAll() } }

    fun refreshOneAsync(id: String) { scope.launch { awaitLoaded(); refreshPlaylists(listOf(id)) } }

    /** Atualiza só se a última atualização for mais velha que o intervalo escolhido. */
    fun refreshIfStale() {
        scope.launch {
            awaitLoaded()
            val limit = System.currentTimeMillis() - refreshHours * 3_600_000L
            if (_playlists.value.any { it.lastUpdated < limit } || _sources.value.any { it.lastChecked < limit }) refreshAll(onlyStale = true)
        }
    }

    private val refreshLock = Mutex()

    /**
     * Atualiza páginas e playlists. Com [onlyStale], pula as que foram atualizadas há pouco:
     * se o sistema interromper no meio, a próxima rodada continua de onde parou.
     */
    suspend fun refreshAll(onlyStale: Boolean = false) {
        awaitLoaded()
        if (refreshLock.isLocked) return
        refreshLock.withLock {
            try {
                val limit = System.currentTimeMillis() - (refreshHours * 3_600_000L - 3_600_000L).coerceAtLeast(3_600_000L)
                _sources.value.filter { !onlyStale || it.lastChecked < limit }.forEach { scanSource(it) }
                val ids = _playlists.value.filter { !onlyStale || it.lastUpdated < limit }.map { it.id }
                if (ids.isNotEmpty()) refreshPlaylists(ids, locked = true)
            } finally {
                _status.value = null
            }
        }
    }

    private suspend fun refreshPlaylists(ids: List<String>, locked: Boolean = false) {
        if (!locked && refreshLock.isLocked) {
            // outra atualização em andamento: espera ela acabar e atualiza só estas
            refreshLock.withLock { }
        }
        val total = ids.size
        ids.forEachIndexed { i, id ->
            val p = _playlists.value.firstOrNull { it.id == id } ?: return@forEachIndexed
            _status.value = if (total > 1) "Atualizando ${i + 1}/$total: ${p.name}" else "Atualizando ${p.name}…"
            val updated = refreshOne(p)
            mutex.withLock {
                _playlists.value = _playlists.value.map { if (it.id == id) updated else it }
                savePlaylists()
            }
            // mostra os canais desta lista já, sem esperar as outras terminarem
            if (total > 1) runCatching { rebuild() }
        }
        _status.value = "Organizando…"
        rebuild()
        _status.value = null
    }

    private suspend fun refreshOne(p: Playlist): Playlist = withContext(Dispatchers.IO) {
        try {
            var detectedEpg: String? = null
            val head = fetchHead(p.url)
            val counts: Db.Counts
            if (M3uParser.isHlsStream(head)) {
                // o link é um único canal (stream HLS), não uma lista
                counts = db.replacePlaylist(p.id) { add -> add(Entry(name = p.name, url = p.url, group = p.name, playlistId = p.id)) }
            } else {
                if (!M3uParser.isChannelPlaylist(head)) throw IOException("não parece uma playlist M3U")
                val label = _status.value ?: "Atualizando ${p.name}"
                counts = open(p.url) { input ->
                    input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        db.replacePlaylist(p.id) { add ->
                            var n = 0
                            detectedEpg = M3uParser.parseTo(lines, p.id) { e ->
                                add(e)
                                if (++n % 5000 == 0) _status.value = "$label · $n itens"
                            }
                        }
                    }
                }
            }
            if (counts.live + counts.movie + counts.series == 0) throw IOException("lista vazia")

            val epgUrl = p.epgUrl ?: detectedEpg
            if (epgUrl != null && counts.live > 0) {
                _status.value = "Baixando guia de programação de ${p.name}…"
                runCatching { downloadEpg(p.id, epgUrl) }
            }
            p.copy(
                lastUpdated = System.currentTimeMillis(),
                lastError = null,
                detectedEpgUrl = detectedEpg,
                liveCount = counts.live,
                movieCount = counts.movie,
                seriesCount = counts.series,
            )
        } catch (e: Throwable) {
            // mantém os canais antigos se a atualização falhar
            p.copy(lastError = e.message ?: e.javaClass.simpleName, lastUpdated = System.currentTimeMillis())
        }
    }

    private fun downloadEpg(id: String, url: String) {
        val (ids, names) = db.liveKeys(id)
        val now = System.currentTimeMillis()
        val data = open(url) { EpgParser.parse(it, ids, names, now - 2 * 3_600_000L, now + 24 * 3_600_000L) }
        file("epg_$id.json").outputStream().buffered().use { json.encodeToStream(EpgData.serializer(), data, it) }
    }

    private suspend fun rebuild() = withContext(Dispatchers.IO) {
        val hide = hideAdult
        fun List<GroupInfo>.visible() = Library.sortGroups(if (hide) filterNot { Library.isAdult(it.name) } else this) { it.name }
        val lib = Library(
            version = ++version,
            liveGroups = db.groups(ContentType.LIVE).visible(),
            movieGroups = db.groups(ContentType.MOVIE).visible(),
            showGroups = db.showGroups().visible(),
        )
        _library.value = lib
        writeLibraryCache(lib)
        loadEpg()
    }

    // ---------- Consultas para as telas (rodam fora da thread principal) ----------

    private fun List<Entry>.visible() = if (hideAdult) filterNot { Library.isAdult(it.group) } else this

    /** Junta itens repetidos (mesmo nome em várias fontes): o player tenta as outras se uma falhar. */
    private fun List<Entry>.unique(): List<Entry> {
        val seen = HashSet<String>()
        return filter { e -> if (e.type == ContentType.SERIES) true else seen.add(Normalize.name(e.name).ifEmpty { e.url }) }
    }

    suspend fun entries(type: ContentType, group: String?, limit: Int = 60_000): List<Entry> = withContext(Dispatchers.IO) {
        runCatching { db.entries(type, group, limit * 2).visible().unique().take(limit) }.getOrDefault(emptyList())
    }

    suspend fun alternates(e: Entry): List<Entry> = withContext(Dispatchers.IO) {
        runCatching { db.alternates(e.type, Normalize.name(e.name).ifEmpty { e.name.lowercase() }, e.url) }.getOrDefault(emptyList())
    }

    // ---------- Recentes ----------

    private val recentLock = Any()

    fun addRecent(e: Entry) {
        val key = if (e.type == ContentType.LIVE) "recent_live" else "recent_vod"
        synchronized(recentLock) {
            val list = (listOf(e) + readRecent(key).filterNot { it.url == e.url }).take(30)
            prefs.edit().putString(key, json.encodeToString(ListSerializer(Entry.serializer()), list)).apply()
        }
    }

    private fun readRecent(key: String): List<Entry> = runCatching {
        json.decodeFromString(ListSerializer(Entry.serializer()), prefs.getString(key, null) ?: return emptyList())
    }.getOrDefault(emptyList())

    fun recentLive(): List<Entry> = readRecent("recent_live")

    /** Filmes/episódios começados e não terminados. */
    fun continueWatching(): List<Entry> = readRecent("recent_vod").filter { getResume(it.url) > 0 }

    suspend fun shows(group: String?): List<Show> = withContext(Dispatchers.IO) {
        runCatching { db.shows(group).let { l -> if (hideAdult) l.filterNot { Library.isAdult(it.group) } else l } }
            .getOrDefault(emptyList())
    }

    suspend fun episodes(show: Show): List<Entry> = withContext(Dispatchers.IO) {
        runCatching { db.episodes(show.key) }.getOrDefault(emptyList())
    }

    suspend fun search(q: String): Pair<List<Entry>, List<Show>> = withContext(Dispatchers.IO) {
        runCatching {
            val (e, s) = db.search(q)
            e.visible().unique() to (if (hideAdult) s.filterNot { Library.isAdult(it.group) } else s)
        }.getOrDefault(emptyList<Entry>() to emptyList())
    }

    suspend fun favoriteEntries(): List<Entry> = withContext(Dispatchers.IO) {
        runCatching { db.byUrls(_favorites.value).unique() }.getOrDefault(emptyList())
    }

    /** Último canal assistido + a categoria dele (para trocar de canal com ▲▼). */
    suspend fun lastChannelQueue(): Pair<List<Entry>, Int>? = withContext(Dispatchers.IO) {
        val url = lastChannelUrl ?: return@withContext null
        val e = runCatching { db.firstByUrl(url) }.getOrNull() ?: return@withContext null
        val list = db.entries(ContentType.LIVE, e.group)
        val i = list.indexOfFirst { it.url == url }
        if (i < 0) null else list to i
    }

    // ---------- Rede ----------

    private fun <T> open(url: String, block: (InputStream) -> T): T {
        val req = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("servidor respondeu ${resp.code}")
            val raw = BufferedInputStream(resp.body!!.byteStream(), 64 * 1024)
            raw.mark(4)
            val b1 = raw.read()
            val b2 = raw.read()
            raw.reset()
            val stream: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
            return block(stream)
        }
    }

    /** Lê só o começo do arquivo para descobrir o que ele é. */
    private fun fetchHead(url: String): String = open(url) { input ->
        val buf = CharArray(16 * 1024)
        val reader = input.bufferedReader(Charsets.UTF_8)
        var n = 0
        while (n < buf.size) {
            val r = reader.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        String(buf, 0, n)
    }

    private fun fetchText(url: String, maxChars: Int): String = open(url) { input ->
        val sb = StringBuilder()
        val reader = input.bufferedReader(Charsets.UTF_8)
        val buf = CharArray(8192)
        while (sb.length < maxChars) {
            val r = reader.read(buf)
            if (r < 0) break
            sb.append(buf, 0, r)
        }
        sb.toString()
    }

    // ---------- Arquivos ----------

    private fun file(name: String) = File(app.filesDir, name)

    private fun savePlaylists() = writeList(file("playlists.json"), Playlist.serializer(), _playlists.value)
    private fun saveSources() = writeList(file("sources.json"), Source.serializer(), _sources.value)

    private fun <T> readList(f: File, s: kotlinx.serialization.KSerializer<T>): List<T> {
        if (!f.exists()) return emptyList()
        return runCatching { f.inputStream().buffered().use { json.decodeFromStream(ListSerializer(s), it) } }
            .getOrDefault(emptyList())
    }

    private fun <T> writeList(f: File, s: kotlinx.serialization.KSerializer<T>, list: List<T>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().buffered().use { json.encodeToStream(ListSerializer(s), list, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
