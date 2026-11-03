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
        _favorites.value = prefs.getStringSet("favorites", emptySet())!!.toSet()
        scope.launch {
            try {
                mutex.withLock {
                    _playlists.value = readList(file("playlists.json"), Playlist.serializer())
                    _sources.value = readList(file("sources.json"), Source.serializer())
                }
                rebuild()
            } finally {
                _isLoading.value = false
                loaded.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    // ---------- Configurações ----------

    var refreshHours: Int
        get() = prefs.getInt("refresh_hours", 6)
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
            prefs.edit().putLong(key, position).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    /** Ao abrir o app, já começa a tocar o último canal assistido (como uma TV comum). */
    var autoplayLast: Boolean
        get() = prefs.getBoolean("autoplay_last", false)
        set(v) { prefs.edit().putBoolean("autoplay_last", v).apply() }

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
            val text = fetchText(src.url, maxChars = 2_000_000)
            val links = LinkExtractor.extract(src.url, text)
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

    fun removePlaylist(id: String) {
        scope.launch {
            mutex.withLock {
                _playlists.value = _playlists.value.filterNot { it.id == id }
                savePlaylists()
                file("items_$id.json").delete()
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
                gone.forEach { file("items_${it.id}.json").delete(); file("epg_${it.id}.json").delete() }
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
            if (_playlists.value.any { it.lastUpdated < limit } || _sources.value.any { it.lastChecked < limit }) refreshAll()
        }
    }

    private val refreshLock = Mutex()

    suspend fun refreshAll() {
        awaitLoaded()
        if (refreshLock.isLocked) return
        refreshLock.withLock {
            try {
                _sources.value.forEach { scanSource(it) }
                refreshPlaylists(_playlists.value.map { it.id }, locked = true)
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
        }
        _status.value = "Organizando…"
        rebuild()
        _status.value = null
    }

    private suspend fun refreshOne(p: Playlist): Playlist = withContext(Dispatchers.IO) {
        try {
            val entries: List<Entry>
            var detectedEpg: String? = null
            val head = fetchHead(p.url)
            if (M3uParser.isHlsStream(head)) {
                // o link é um único canal (stream HLS), não uma lista
                entries = listOf(Entry(name = p.name, url = p.url, group = p.name, playlistId = p.id))
            } else {
                if (!M3uParser.isChannelPlaylist(head)) throw IOException("não parece uma playlist M3U")
                val result = open(p.url) { input ->
                    input.bufferedReader(Charsets.UTF_8).useLines { M3uParser.parse(it, p.id) }
                }
                entries = result.entries
                detectedEpg = result.epgUrl
            }
            if (entries.isEmpty()) throw IOException("lista vazia")
            mutex.withLock { writeList(file("items_${p.id}.json"), Entry.serializer(), entries) }

            val epgUrl = p.epgUrl ?: detectedEpg
            if (epgUrl != null) {
                _status.value = "Baixando guia de programação de ${p.name}…"
                runCatching { downloadEpg(p.id, epgUrl, entries) }
            }
            p.copy(
                lastUpdated = System.currentTimeMillis(),
                lastError = null,
                detectedEpgUrl = detectedEpg,
                liveCount = entries.count { it.type == ContentType.LIVE },
                movieCount = entries.count { it.type == ContentType.MOVIE },
                seriesCount = entries.count { it.type == ContentType.SERIES },
            )
        } catch (e: Exception) {
            // mantém os canais antigos se a atualização falhar
            p.copy(lastError = e.message ?: e.javaClass.simpleName, lastUpdated = System.currentTimeMillis())
        }
    }

    private fun downloadEpg(id: String, url: String, entries: List<Entry>) {
        val live = entries.filter { it.type == ContentType.LIVE }
        if (live.isEmpty()) return
        val ids = live.mapNotNull { it.tvgId }.toSet()
        val names = live.map { Normalize.name(it.name) }.filter { it.isNotEmpty() }.toSet()
        val now = System.currentTimeMillis()
        val data = open(url) { EpgParser.parse(it, ids, names, now - 2 * 3_600_000L, now + 24 * 3_600_000L) }
        file("epg_$id.json").outputStream().buffered().use { json.encodeToStream(EpgData.serializer(), data, it) }
    }

    private suspend fun rebuild() {
        val lists = _playlists.value
        val all = ArrayList<Entry>()
        var epg = EpgData.EMPTY
        mutex.withLock {
            for (p in lists) {
                all += readList(file("items_${p.id}.json"), Entry.serializer())
                val f = file("epg_${p.id}.json")
                if (f.exists()) runCatching {
                    f.inputStream().buffered().use { json.decodeFromStream(EpgData.serializer(), it) }
                }.getOrNull()?.let { epg = epg.merge(it) }
            }
        }
        _library.value = Library.build(all)
        _epg.value = epg
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
