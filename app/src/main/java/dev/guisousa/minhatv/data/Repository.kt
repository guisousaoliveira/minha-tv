package dev.guisousa.minhatv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
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
    /**
     * Sem atualização periódica: ao abrir (ou voltar para) o app, ele pergunta se as listas mudaram.
     * Servidor Xtream e lista com ETag/Last-Modified respondem isso sem baixar nada: confere no máximo a cada 10 min.
     */
    private const val CHECK_QUICK_MS = 10 * 60_000L
    /** Lista que não diz se mudou (teria que ser baixada inteira) e páginas com playlists: no máximo a cada 6 horas. */
    private const val CHECK_FULL_MS = 6 * 3_600_000L
    private const val BUILTIN_EPG_URL = "https://raw.githubusercontent.com/marcoshimselfxd/EPG-Scrapper-BR/main/epg.xml"
    private const val BUILTIN_EPG_FILE = "epg_builtin.json"
    /** Fontes no máximo por canal/filme (contando a primeira). */
    const val MAX_SOURCES = 5
    /** A partir de quanto tempo assistido o filme/episódio entra em "Continuar assistindo". */
    private const val MIN_RESUME_MS = 5_000L
    /** Suba quando mudar o jeito de aplicar a limpeza no código (força aplicar as regras de novo). */
    private const val CLEAN_CODE = 3
    private const val RULES_FILE = "limpeza.json"
    private const val CHECK_PARALLEL = 3
    private const val CHECK_BATCH = 60
    private const val CHECK_OK_AGE = 24 * 3_600_000L
    private const val CHECK_BAD_AGE = 6 * 3_600_000L

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Uma única linha de baixa prioridade para importar: a tela e o player ficam com o resto da CPU. */
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
            r.run()
        }, "minhatv-import")
    }.asCoroutineDispatcher()

    /** Verdadeiro enquanto o player está aberto: a atualização automática espera o vídeo acabar. */
    @Volatile var playbackActive = false

    /** Status da importação no máximo uma vez por segundo. */
    private var lastStatusAt = 0L
    private fun importStatus(text: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStatusAt >= 1000) { lastStatusAt = now; _status.value = text }
    }
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    internal lateinit var db: Db
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

    /**
     * Pedidos pequenos com a tela esperando (episódios de uma série). Pedido que fica parado sem mandar
     * nada é largado em 6 s e feito de novo na hora: o Ted Lasso levou 96 s porque o 1º pedido ficou
     * mudo 90 s (o limite das listas grandes) e o 2º respondeu em ~1 s.
     */
    private val quickHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
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
        loadSavedRules()
        db.hideMask = currentMask()
        db.cleanOnImport = !listReady
        _favorites.value = prefs.getStringSet("favorites", emptySet())!!.toSet()
        scope.launch {
            // 1) rápido: listas salvas + resumo das categorias da última vez -> a tela abre na hora
            try {
                mutex.withLock {
                    _playlists.value = readList(file("playlists.json"), Playlist.serializer())
                    // versões até a 1.0.74 adicionavam o mesmo arquivo duas vezes (README + repositório): fica só um
                    val dedup = _playlists.value.distinctBy { LinkExtractor.sameFileKey(it.url) }
                    if (dedup.size != _playlists.value.size) { _playlists.value = dedup; savePlaylists() }
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
                // regras de limpeza novas (ou app novo): aplica no que já está no banco, uma vez
                val stale = !listReady && prefs.getString("rules_applied", null) != rulesStamp()
                if (stale) withContext(background) { applyRulesToDb() }
                if (!hadCache || db.wasReset || stale) {
                    rebuild()
                } else {
                    loadEpg()
                    // sem reorganizar na abertura: só confere se o resumo das séries está em dia (refaz se não)
                    scope.launch { refreshSummary() }
                }
            } catch (e: Exception) {
                android.util.Log.e("MinhaTV", "init", e)
            } finally {
                _status.value = null
                loaded.complete(Unit)
            }
        }
        // testa as fontes um pouco depois de abrir e a cada 2 horas enquanto o app estiver aberto
        scope.launch {
            prefs.edit().remove("failed_urls").apply() // formato antigo, agora fica no banco
            awaitLoaded()
            // nomes limpos para itens importados por versões antigas (sem baixar as listas de novo)
            withContext(background) {
                while (true) {
                    while (playbackActive) kotlinx.coroutines.delay(5_000)
                    val n = runCatching { db.backfillTitles() }.getOrDefault(0)
                    if (n == 0) break
                }
            }
            runCatching { refreshRules() }
            kotlinx.coroutines.delay(30_000)
            while (true) {
                runCatching { checkSources() }
                kotlinx.coroutines.delay(2 * 3_600_000L)
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

    // ---------- Regras de limpeza (regras/limpeza.json no GitHub: só regras, sem links) ----------

    private fun rulesStamp() = "$CLEAN_CODE:${CleanRules.current.version}"

    private fun currentMask(): Int = if (listReady) 0 else CleanRules.JUNK or
        (if (hideForeign) CleanRules.FOREIGN else 0) or (if (hideAdult) CleanRules.ADULT else 0)

    /** Regras baixadas antes (se forem mais novas que a cópia do app). */
    private fun loadSavedRules() {
        runCatching {
            val f = file(RULES_FILE)
            if (!f.exists()) return
            val r = CleanRules.parse(f.readText())
            if (r.version >= CleanRules.builtin.version) CleanRules.use(r)
        }.onFailure { android.util.Log.w("MinhaTV", "regras salvas inválidas", it) }
    }

    /** Aplica as regras atuais em tudo que está no banco (marcas, categorias, lixo) e guarda que aplicou. */
    private fun applyRulesToDb() {
        val t0 = System.currentTimeMillis()
        val n = runCatching { db.reclassify(CleanRules.current) }.getOrElse {
            android.util.Log.w("MinhaTV", "limpeza: falhou ao aplicar regras", it); return
        }
        prefs.edit().putString("rules_applied", rulesStamp()).apply()
        android.util.Log.i("MinhaTV", "tempo: limpeza aplicou regras v${CleanRules.current.version} em ${System.currentTimeMillis() - t0} ms ($n itens mudaram)")
    }

    /**
     * Confere no GitHub se as regras mudaram (no máximo a cada 6 horas). Se mudaram, aplica no
     * banco em segundo plano e reorganiza. Itens que regras velhas tiraram voltam na próxima
     * atualização da lista (o app baixa a lista inteira de novo).
     */
    private suspend fun refreshRules(force: Boolean = false) = withContext(background) {
        if (listReady) return@withContext
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("rules_checked", 0) < 6 * 3_600_000L) return@withContext
        val text = runCatching { fetchText(CleanRules.URL, 256 * 1024) }.getOrElse {
            android.util.Log.w("MinhaTV", "regras: não baixou (${it.message})"); return@withContext
        }
        prefs.edit().putLong("rules_checked", now).apply()
        val r = runCatching { CleanRules.parse(text) }.getOrElse {
            android.util.Log.w("MinhaTV", "regras: arquivo inválido", it); return@withContext
        }
        if (r.version <= CleanRules.current.version) return@withContext
        file(RULES_FILE).writeText(text)
        CleanRules.use(r)
        android.util.Log.i("MinhaTV", "regras: versão ${r.version} baixada")
        // as listas são baixadas inteiras na próxima atualização (para voltar o que regras velhas tiraram)
        _playlists.value.forEach { p -> prefs.edit().remove("etag_${p.id}").remove("lastmod_${p.id}").remove("listver_${p.id}").apply() }
        applyRulesToDb()
        rebuild()
    }

    private fun loadEpg() {
        // guia embutido primeiro; o guia de cada lista (se tiver) vem por cima
        var epg = runCatching {
            file(BUILTIN_EPG_FILE).takeIf { it.exists() }?.inputStream()?.buffered()?.use { json.decodeFromStream(EpgData.serializer(), it) }
        }.getOrNull() ?: EpgData.EMPTY
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

    fun toggleFavorite(url: String): Boolean {
        val now = _favorites.value.toMutableSet()
        val added = if (url in now) { now.remove(url); false } else { now.add(url); true }
        _favorites.value = now
        prefs.edit().putStringSet("favorites", now).apply()
        return added
    }

    fun getResume(url: String): Long = prefs.getLong("resume_" + url.hashCode(), 0)

    /** Igual ao Saimo: guarda a posição depois de 1 min; faltando menos de 2 min para o fim, conta como visto. */
    fun saveResume(url: String, position: Long, duration: Long) {
        val key = "resume_" + url.hashCode()
        val nearEnd = duration > 0 && position >= duration - 120_000
        if (nearEnd && position > 60_000) markWatched(url)
        // poucos segundos já contam: o filme aparece em "Continuar assistindo" e pergunta se continua
        if (position > MIN_RESUME_MS && !nearEnd) {
            prefs.edit().putLong(key, position).putLong("dur_" + url.hashCode(), duration).apply()
        } else if (nearEnd && position > 60_000) {
            prefs.edit().remove(key).remove("dur_" + url.hashCode()).apply()
        }
    }

    private val _watched by lazy { MutableStateFlow(prefs.getStringSet("watched", emptySet())!!.toSet()) }
    /** Hashes dos links já vistos até o fim. */
    val watched: StateFlow<Set<String>> get() = _watched
    fun isWatched(url: String) = url.hashCode().toString() in _watched.value
    fun markWatched(url: String) {
        val h = url.hashCode().toString()
        if (h in _watched.value) return
        _watched.value = _watched.value + h
        prefs.edit().putStringSet("watched", _watched.value).apply()
    }

    /** Duração conhecida (ms), guardada quando foi assistido. 0 = não sei. */
    fun duration(url: String): Long = prefs.getLong("dur_" + url.hashCode(), 0)

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

    /** Esconde canais ao vivo de outros países, menos os de esporte (ligado por padrão). */
    var hideForeign: Boolean
        get() = prefs.getBoolean("hide_foreign", true)
        set(v) { prefs.edit().putBoolean("hide_foreign", v).apply(); db.hideMask = currentMask(); scope.launch { rebuild() } }

    /** Esconde categorias adultas (ligado por padrão). */
    // ---- Versão (dublado/legendado), igual ao Saimo: separada da fonte ----
    const val DUB = "dub"
    const val LEG = "leg"

    /** Versão preferida em todo o app (padrão: Dublado). */
    var preferredVersion: String
        get() = prefs.getString("pref_version", DUB) ?: DUB
        set(v) { prefs.edit().putString("pref_version", v).apply() }

    private fun titleKey(e: Entry) = "ver_" + (if (e.type == ContentType.SERIES) e.seriesName ?: e.name else e.norm ?: e.name).hashCode()

    /** Versão escolhida para este título (botão "Versão"), ou a preferida do app. */
    fun versionFor(e: Entry): String = prefs.getString(titleKey(e), null) ?: preferredVersion
    fun setVersionFor(e: Entry, v: String) { prefs.edit().putString(titleKey(e), v).apply() }

    /** Dublado ou legendado, pelo nome, etiqueta ou categoria. null = não diz. */
    fun versionOf(e: Entry): String? {
        val t = listOfNotNull(e.quality, e.name, e.group).joinToString(" ").lowercase()
        return when {
            Regex("""legend|\bleg\b|\bsub(bed|titled)?\b""").containsMatchIn(t) -> LEG
            Regex("""dublad|\bdub\b|dual|nacional""").containsMatchIn(t) -> DUB
            else -> null
        }
    }

    /**
     * Versão de um filme/episódio contando a cópia sem marca como Dublado: as listas brasileiras só marcam
     * o legendado ("[L]", "Legendado"), e o dublado vem sem nada. Sem isso, um filme que só tem dublado
     * parecia "versão desconhecida" e o app deixava trocar para Legendado, tocando o dublado calado.
     */
    fun versionOrDub(e: Entry): String = versionOf(e) ?: DUB

    fun versionName(v: String) = if (v == LEG) "Legendado" else "Dublado"

    var hideAdult: Boolean
        get() = prefs.getBoolean("hide_adult", true)
        set(v) { prefs.edit().putBoolean("hide_adult", v).apply(); db.hideMask = currentMask(); scope.launch { rebuild() } }

    /**
     * A lista já vem limpa de onde ela é baixada: a TV não aplica regras de limpeza, não esconde
     * nada e não testa as fontes em segundo plano. Só guarda a lista e mostra.
     */
    const val listReady = true

    /** Testa as fontes em segundo plano (ligado por padrão). */
    var checkSourcesEnabled: Boolean
        get() = prefs.getBoolean("check_sources", true)
        set(v) { prefs.edit().putBoolean("check_sources", v).apply(); if (v) checkSourcesAsync() }

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
        // acesso Xtream (get.php / player_api.php com usuário e senha): séries carregam episódios só ao abrir
        Xtream.parseLogin(input)?.let { (base, user, pass) ->
            val viaApi = input.substringBefore('?').lowercase().endsWith("player_api.php")
            val msg = runCatching { addXtream(base, user, pass, name, epgUrl) }
            // link get.php de painel sem a API: segue como playlist M3U comum
            if (msg.isSuccess || viaApi) return msg.getOrThrow()
        }
        val url = LinkExtractor.toRaw(input)
        _status.value = "Lendo link…"
        val head = fetchHead(url)
        if (M3uParser.isChannelPlaylist(head) || M3uParser.isHlsStream(head)) {
            if (_playlists.value.any { LinkExtractor.sameFileKey(it.url) == LinkExtractor.sameFileKey(url) }) return "Essa playlist já estava adicionada."
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

    private fun xtream(p: Playlist) = Xtream(p.url, p.xtreamUser!!, p.xtreamPass ?: "", DEFAULT_UA) { url, block -> open(url) { block(it) } }

    /** Para pedidos com a tela esperando: largado rápido se ficar mudo e feito de novo (até 4 vezes). */
    private fun xtreamQuick(p: Playlist) = Xtream(p.url, p.xtreamUser!!, p.xtreamPass ?: "", DEFAULT_UA) { url, block -> openQuick(url) { block(it) } }

    private fun <T> openQuick(url: String, block: (InputStream) -> T): T {
        val req = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()
        var last: IOException? = null
        repeat(4) { attempt ->
            if (attempt > 0) android.util.Log.w("MinhaTV", "tempo: pedido ao servidor sem resposta (${last?.message}), tentativa ${attempt + 1}")
            try {
                val t0 = System.currentTimeMillis()
                quickHttp.newCall(req).execute().use { resp ->
                    android.util.Log.i("MinhaTV", "tempo: servidor respondeu ${resp.code} em ${System.currentTimeMillis() - t0} ms (${resp.body?.contentLength() ?: -1} bytes)")
                    if (resp.code >= 500 && attempt < 3) { last = IOException("servidor respondeu ${resp.code}"); Thread.sleep(500); return@repeat }
                    if (!resp.isSuccessful) throw IOException("servidor respondeu ${resp.code}")
                    val raw = BufferedInputStream(resp.body!!.byteStream(), 64 * 1024)
                    raw.mark(4)
                    val b1 = raw.read(); val b2 = raw.read()
                    raw.reset()
                    return block(if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw)
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("sem conexão")
    }

    private suspend fun addXtream(base: String, user: String, pass: String, name: String?, epgUrl: String?): String {
        if (_playlists.value.any { it.isXtream && it.url.equals(base, true) && it.xtreamUser == user }) return "Esse servidor já estava adicionado."
        val host = runCatching { java.net.URI(base).host }.getOrNull() ?: base
        val p = Playlist(UUID.randomUUID().toString(), name ?: host, base, epgUrl, xtreamUser = user, xtreamPass = pass)
        _status.value = "Conectando ao servidor…"
        withContext(background) { xtream(p).login() }
        mutex.withLock { _playlists.value = _playlists.value + p; savePlaylists() }
        refreshPlaylists(listOf(p.id))
        val updated = _playlists.value.firstOrNull { it.id == p.id }
        return updated?.lastError?.let { "Servidor adicionado, mas deu erro: $it" }
            ?: "Servidor \"${p.name}\" adicionado: ${updated?.liveCount ?: 0} canais, ${updated?.movieCount ?: 0} filmes, ${updated?.seriesCount ?: 0} séries."
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
            // o mesmo arquivo pode aparecer no README e na lista do repositório com endereços diferentes
            val unique = links.distinctBy { LinkExtractor.sameFileKey(it) }
            val known = _playlists.value.map { LinkExtractor.sameFileKey(it.url) }.toSet()
            val fresh = unique.filter { LinkExtractor.sameFileKey(it) !in known }.map {
                Playlist(UUID.randomUUID().toString(), LinkExtractor.nameFromUrl(it), it, sourceId = src.id)
            }
            // a página não lista mais algum arquivo (o servidor juntou ou tirou partes): sai do app também.
            // Só quando a página respondeu e trouxe links, para uma falha de rede não apagar nada.
            val listed = unique.map { LinkExtractor.sameFileKey(it) }.toSet()
            val gone = if (pageError == null && unique.isNotEmpty())
                _playlists.value.filter { it.sourceId == src.id && LinkExtractor.sameFileKey(it.url) !in listed } else emptyList()
            mutex.withLock {
                if (gone.isNotEmpty()) {
                    android.util.Log.i("MinhaTV", "página ${src.url}: ${gone.size} listas saíram dela, removendo")
                    gone.forEach { db.deletePlaylist(it.id); file("epg_${it.id}.json").delete() }
                }
                _playlists.value = _playlists.value.filterNot { p -> gone.any { it.id == p.id } } + fresh
                _sources.value = _sources.value.map {
                    if (it.id == src.id) it.copy(lastChecked = System.currentTimeMillis(), lastError = null, found = unique.size) else it
                }
                savePlaylists(); saveSources()
            }
            if (gone.isNotEmpty()) runCatching { rebuild() }
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

    /** A lista sabe dizer se mudou sem ser baixada (servidor Xtream, ETag ou Last-Modified)? */
    private fun quickCheck(p: Playlist) =
        p.isXtream || prefs.getString("etag_${p.id}", null) != null || prefs.getString("lastmod_${p.id}", null) != null

    private fun due(p: Playlist, now: Long) = p.lastUpdated < now - (if (quickCheck(p)) CHECK_QUICK_MS else CHECK_FULL_MS)

    private fun due(s: Source, now: Long) = s.lastChecked < now - CHECK_FULL_MS

    /** Ao abrir (ou voltar para) o app: confere se as listas mudaram e baixa só o que mudou. */
    fun refreshIfChanged() {
        scope.launch {
            awaitLoaded()
            val now = System.currentTimeMillis()
            if (_playlists.value.any { due(it, now) } || _sources.value.any { due(it, now) }) refreshAll(onlyStale = true)
            else if (builtinEpgStale()) { refreshBuiltinEpg(); loadEpg() }
        }
    }

    private val refreshLock = Mutex()

    /**
     * Atualiza páginas e playlists. Com [onlyStale], só as que não foram conferidas há pouco
     * (ver [CHECK_QUICK_MS] e [CHECK_FULL_MS]).
     */
    suspend fun refreshAll(onlyStale: Boolean = false) {
        awaitLoaded()
        if (onlyStale && playbackActive) return
        if (refreshLock.isLocked) return
        refreshLock.withLock {
            try {
                val now = System.currentTimeMillis()
                runCatching { refreshRules() }
                _sources.value.filter { !onlyStale || due(it, now) }.forEach { scanSource(it) }
                val ids = _playlists.value.filter { !onlyStale || due(it, now) }.map { it.id }
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
        var anyChanged = false
        ids.forEachIndexed { i, id ->
            val p = _playlists.value.firstOrNull { it.id == id } ?: return@forEachIndexed
            _status.value = if (total > 1) "Conferindo ${i + 1}/$total: ${p.name}" else "Conferindo ${p.name}…"
            unchangedIds.remove(id)
            val updated = refreshOne(p)
            if (id !in unchangedIds) anyChanged = true
            mutex.withLock {
                _playlists.value = _playlists.value.map { if (it.id == id) updated else it }
                savePlaylists()
            }
            // na primeira importação, mostra os canais desta lista já, sem esperar as outras.
            // Depois disso reorganiza só no fim, para o menu não ficar pulando enquanto alguém navega.
            if (_library.value.isEmpty) runCatching { rebuild() }
        }
        // guias das listas que ficaram para depois (primeira importação: o conteúdo aparece antes)
        val epgs = synchronized(pendingEpg) { pendingEpg.toMap().also { pendingEpg.clear() } }
        // nada mudou nas listas: não reorganiza nada (só o guia, se estiver velho)
        if (!anyChanged && epgs.isEmpty()) {
            if (builtinEpgStale()) { _status.value = "Baixando guia de programação…"; refreshBuiltinEpg(); loadEpg() }
            _status.value = null
            return
        }
        if (epgs.isNotEmpty()) {
            _status.value = "Baixando guia de programação…"
            withContext(background) { epgs.forEach { (id, url) -> runCatching { downloadEpg(id, url) } } }
        }
        _status.value = "Baixando guia de programação…"
        refreshBuiltinEpg()
        _status.value = "Organizando…"
        rebuild()
        _status.value = null
        checkSourcesAsync()
    }

    private fun builtinEpgStale(): Boolean {
        val f = file(BUILTIN_EPG_FILE)
        return !f.exists() || f.lastModified() < System.currentTimeMillis() - 6 * 3_600_000L
    }

    /** Guia de programação dos canais brasileiros (EPG-Scrapper-BR), casado pelo nome do canal. */
    private suspend fun refreshBuiltinEpg() = withContext(background) {
        runCatching {
            val (ids, names) = db.liveKeys(null)
            if (names.isEmpty() && ids.isEmpty()) return@runCatching
            val now = System.currentTimeMillis()
            val data = open(BUILTIN_EPG_URL) { EpgParser.parse(it, ids, names, now - 2 * 3_600_000L, now + 36 * 3_600_000L) }
            android.util.Log.i("MinhaTV", "guia embutido: ${data.programmes.size} canais com programação (de ${names.size} nomes)")
            if (data.programmes.isEmpty()) return@runCatching
            val tmp = file("$BUILTIN_EPG_FILE.tmp")
            tmp.outputStream().buffered().use { json.encodeToStream(EpgData.serializer(), data, it) }
            tmp.renameTo(file(BUILTIN_EPG_FILE))
        }
    }

    /** Guias de programação para baixar depois de mostrar o conteúdo (lista id -> endereço do guia). */
    private val pendingEpg = LinkedHashMap<String, String>()

    private suspend fun refreshOne(p: Playlist): Playlist = withContext(background) {
        try {
            val hasData = p.lastError == null && p.liveCount + p.movieCount + p.seriesCount > 0
            // lista igual à da última vez: não baixa nem reorganiza nada
            if (!p.isXtream && hasData && unchanged(p)) {
                unchangedIds += p.id
                return@withContext p.copy(lastUpdated = System.currentTimeMillis())
            }
            var detectedEpg: String? = null
            var head = ""
            val counts: Db.Counts
            var listVersion: String? = null
            if (p.isXtream) {
                val x = xtream(p)
                val info = x.login()
                listVersion = info.version
                val stored = hasData && db.hasPlaylist(p.id)
                // o servidor diz que as listas não mudaram desde a última importação: não baixa nada
                if (listVersion != null && stored && prefs.getString("listver_${p.id}", null) == listVersion) {
                    android.util.Log.i("MinhaTV", "${p.name}: listas iguais às da última vez ($listVersion), nada para baixar")
                    unchangedIds += p.id
                    val epg = p.epgUrl ?: p.detectedEpgUrl
                    if (epg != null && p.liveCount > 0 && epgStale(p.id)) runCatching { downloadEpg(p.id, epg) }
                    return@withContext p.copy(lastUpdated = System.currentTimeMillis())
                }
                // o servidor diz a versão de cada categoria: baixa só as que mudaram
                if (stored && info.parts != null) {
                    val changed = runCatching { updateChangedParts(p, x, info.parts) }
                        .onFailure { android.util.Log.w("MinhaTV", "${p.name}: atualização por categoria falhou, baixando tudo", it) }
                        .getOrNull()
                    if (changed != null) {
                        prefs.edit().apply { if (listVersion != null) putString("listver_${p.id}", listVersion) else remove("listver_${p.id}") }.apply()
                        if (changed == 0) unchangedIds += p.id
                        val c = db.countTypes(p.id)
                        return@withContext p.copy(
                            lastUpdated = System.currentTimeMillis(), lastError = null,
                            liveCount = c.live, movieCount = c.movie, seriesCount = c.series,
                        )
                    }
                }
                val label = _status.value?.replace("Conferindo", "Atualizando") ?: "Atualizando ${p.name}"
                var n = 0
                val liveCats = x.categories("get_live_categories")
                val vodCats = x.categories("get_vod_categories")
                val seriesCats = x.categories("get_series_categories")
                counts = db.replacePlaylist(p.id) { add ->
                    val each: (Entry) -> Unit = { e -> add(e); if (++n % 2000 == 0) importStatus("$label · $n itens") }
                    x.live(cats = liveCats, emit = each)
                    x.movies(cats = vodCats, emit = each)
                    x.seriesCovers(p.id, cats = seriesCats, emit = each)
                }
                val names = HashMap<String, String>()
                liveCats.forEach { (id, nome) -> names["live:$id"] = nome }
                vodCats.forEach { (id, nome) -> names["vod:$id"] = nome }
                seriesCats.forEach { (id, nome) -> names["series:$id"] = nome }
                saveParts(p.id, info.parts, names)
                detectedEpg = "${p.url}/xmltv.php?username=${java.net.URLEncoder.encode(x.user, "UTF-8")}&password=${java.net.URLEncoder.encode(x.pass, "UTF-8")}"
            } else if (M3uParser.isHlsStream(fetchHead(p.url).also { head = it })) {
                // o link é um único canal (stream HLS), não uma lista
                counts = db.replacePlaylist(p.id) { add -> add(Entry(name = p.name, url = p.url, group = p.name, playlistId = p.id)) }
            } else {
                if (!M3uParser.isChannelPlaylist(head)) throw IOException("não parece uma playlist M3U")
                val label = _status.value?.replace("Conferindo", "Atualizando") ?: "Atualizando ${p.name}"
                counts = open(p.url, onResponse = { saveValidators(p.id, it) }) { input ->
                    input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        db.replacePlaylist(p.id) { add ->
                            var n = 0
                            detectedEpg = M3uParser.parseTo(lines, p.id) { e ->
                                add(e)
                                if (++n % 2000 == 0) importStatus("$label · $n itens")
                            }
                        }
                    }
                }
            }
            if (counts.live + counts.movie + counts.series == 0) throw IOException("lista vazia")
            android.util.Log.i("MinhaTV", "limpeza na importação de ${p.name}: ${counts.live + counts.movie + counts.series} itens guardados, ${counts.hidden} marcados para esconder, ${counts.dropped} jogados fora")

            if (p.isXtream) prefs.edit().apply { if (listVersion != null) putString("listver_${p.id}", listVersion) else remove("listver_${p.id}") }.apply()

            val epgUrl = p.epgUrl ?: detectedEpg
            if (epgUrl != null && counts.live > 0) {
                if (_library.value.isEmpty) {
                    // primeira importação: mostra canais e filmes antes, o guia vem logo depois
                    synchronized(pendingEpg) { pendingEpg[p.id] = epgUrl }
                } else {
                    _status.value = "Baixando guia de programação de ${p.name}…"
                    runCatching { downloadEpg(p.id, epgUrl) }
                }
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

    /** O guia desta lista tem mais de 12 horas (ou nem existe). */
    private fun epgStale(id: String): Boolean {
        val f = file("epg_$id.json")
        return !f.exists() || f.lastModified() < System.currentTimeMillis() - 12 * 3_600_000L
    }

    private fun downloadEpg(id: String, url: String) {
        val (ids, names) = db.liveKeys(id)
        val now = System.currentTimeMillis()
        val data = open(url) { EpgParser.parse(it, ids, names, now - 2 * 3_600_000L, now + 24 * 3_600_000L) }
        file("epg_$id.json").outputStream().buffered().use { json.encodeToStream(EpgData.serializer(), data, it) }
    }

    /** Resumo das séries e índice de busca em dia com o banco (refeitos só quando as listas mudam). */
    private fun refreshSummary() {
        runCatching {
            val t0 = System.currentTimeMillis()
            val redone = db.refreshShowSummary()
            android.util.Log.w("MinhaTV", "resumo de séries ${if (redone) "refeito" else "em dia"} em ${System.currentTimeMillis() - t0} ms")
            if (redone) rowCache.clear()
        }.onFailure { android.util.Log.w("MinhaTV", "resumo de séries falhou", it) }
    }

    private suspend fun rebuild() = withContext(Dispatchers.IO) {
        val hide = hideAdult && !listReady
        fun List<GroupInfo>.visible() = Library.sortGroups(if (hide) filterNot { Library.isAdult(it.name) } else this) { it.name }
        db.hideMask = currentMask()
        val t0 = System.currentTimeMillis()
        refreshSummary()
        val lib = Library(
            version = ++version,
            liveGroups = db.liveGroups().visible(),
            movieGroups = db.groups(ContentType.MOVIE).visible(),
            showGroups = db.showGroups().visible(),
        )
        rowCache.clear()
        _library.value = lib
        writeLibraryCache(lib)
        runCatching {
            val (total, shown) = db.cleanStats()
            android.util.Log.i("MinhaTV", "tempo: organizar em ${System.currentTimeMillis() - t0} ms ($total no banco, $shown canais/filmes únicos visíveis)")
        }
        loadEpg()
    }

    // ---------- Consultas para as telas (rodam fora da thread principal) ----------

    /** Tira os escondidos pelas marcas já guardadas na importação (sem testar nome por nome). */
    private fun List<Entry>.visible(): List<Entry> {
        val mask = currentMask()
        return filter { it.hidden and mask == 0 }
    }

    /**
     * Um item por título: várias fontes com o mesmo nome viram uma só na tela (o player tenta as
     * outras se uma falhar). Fica a fonte que respondeu mais rápido no teste; títulos mortos em
     * todas as fontes somem.
     */
    private fun List<Entry>.unique(hideDead: Boolean = true): List<Entry> {
        if (isEmpty()) return this
        val health = runCatching { db.health(map { it.url }) }.getOrDefault(emptyMap())
        val favs = _favorites.value
        val order = LinkedHashMap<String, MutableList<Entry>>()
        val out = ArrayList<Any>(size) // Entry (série) ou chave do grupo, na ordem original
        for (e in this) {
            if (e.type == ContentType.SERIES) { out += e; continue }
            // mesmo nome (com o ano, quando tem) = mesmo título na tela. Filme SEM ano também precisa da
            // mesma capa: "101 Dálmatas" desenho e live action, os dois sem ano, são dois cards
            var key = e.type.ordinal.toString() + ":" + Normalize.looseKey(e.name)
            if (e.type == ContentType.MOVIE && !Normalize.hasYear(e.name)) key += "|" + (Normalize.posterId(e.logo) ?: "")
            val list = order[key]
            if (list == null) { order[key] = mutableListOf(e); out += key } else list += e
        }
        // títulos com todas as fontes mortas aqui: confere se não há fonte boa em outra categoria
        val deadHere = order.filterValues { l -> l.all { health[it.url]?.dead == true } }
        val rescued = HashSet<String>()
        if (hideDead && deadHere.isNotEmpty()) {
            deadHere.entries.groupBy { it.key.substringBefore(':') }.forEach { (t, groups) ->
                val type = ContentType.entries[t.toInt()]
                val norms = groups.flatMap { g -> g.value.mapNotNull { it.norm } }
                val alive = runCatching { db.aliveNorms(type, norms) }.getOrNull() ?: return@forEach
                groups.forEach { g -> if (g.value.any { it.norm in alive }) rescued += g.key }
            }
        }
        return out.mapNotNull { o ->
            if (o is Entry) return@mapNotNull o
            val key = o as String
            val list = order.getValue(key)
            if (hideDead && key in deadHere && key !in rescued) return@mapNotNull null
            list.minWith(compareBy<Entry>({ if (it.url in favs && health[it.url]?.dead != true) 0 else 1 }, { rank(health[it.url]) }))
        }
    }

    /** Ordem das fontes: boas (mais rápidas primeiro), não testadas, falhou uma vez, mortas. */
    private fun rank(h: Health?): Long = when {
        h == null -> 100_000L
        h.ok -> (h.ms ?: 50_000L).coerceAtMost(99_999L)
        h.dead -> 300_000L
        else -> 200_000L
    }

    /** Fileiras já carregadas (voltar a elas ou rolar de volta não consulta o banco de novo). */
    private val rowCache = java.util.concurrent.ConcurrentHashMap<String, List<Any>>()

    suspend fun entries(type: ContentType, group: String?, limit: Int = 60_000): List<Entry> {
        val ck = "e|$type|$group|$limit"
        @Suppress("UNCHECKED_CAST")
        if (limit <= 50) (rowCache[ck] as? List<Entry>)?.let { return it }
        return withContext(kotlinx.coroutines.NonCancellable) { entriesUncached(type, group, limit) }.also { if (limit <= 50 && it.isNotEmpty()) rowCache[ck] = it }
    }

    private suspend fun entriesUncached(type: ContentType, group: String?, limit: Int): List<Entry> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val r = runCatching { db.entries(type, group, limit * 2).visible().unique().take(limit) }.getOrDefault(emptyList())
        val ms = System.currentTimeMillis() - t0
        android.util.Log.i("MinhaTV", "tempo: categoria $type \"${group ?: "Todos"}\" ${r.size} itens em $ms ms")
        r
    }

    /** Outras fontes do mesmo título, as melhores primeiro, no máximo [MAX_SOURCES] contando a atual. */
    suspend fun alternates(e: Entry): List<Entry> = withContext(Dispatchers.IO) {
        val ep = e.episode
        val series = e.seriesName
        val list = if (e.type == ContentType.SERIES) {
            // episódio: as outras cópias do mesmo número na mesma série (sem número, não há como saber)
            if (ep == null || series == null) emptyList()
            else runCatching { db.episodeAlternates(Normalize.showKey(series), e.season, ep, e.url) }
                .getOrDefault(emptyList()).distinctBy { it.url }
        } else {
            runCatching { db.alternates(e.type, e.norm ?: Normalize.key(e.name), e.url) }
                .getOrDefault(emptyList()).distinctBy { it.url }
                // filme sem ano no nome: "101 Dálmatas" pode ser o desenho ou o live action; só é o mesmo filme com o mesmo pôster
                .filter { e.type != ContentType.MOVIE || Normalize.sameMovie(e, it) }
        }
        val health = runCatching { db.health(list.map { it.url }) }.getOrDefault(emptyMap())
        val (dead, alive) = list.partition { health[it.url]?.dead == true }
        val ranked = alive.sortedBy { rank(health[it.url]) }
        var best = ranked.take(MAX_SOURCES - 1)
        // filme/série com as duas versões: a outra versão sempre entra nas fontes, mesmo com muitas cópias
        // mais rápidas da mesma versão (senão "Legendado" ficava de fora e tocava o dublado)
        if (e.type != ContentType.LIVE) {
            val have = (best + e).map { versionOrDub(it) }.toSet()
            ranked.firstOrNull { versionOrDub(it) !in have }?.let { other ->
                best = (if (best.size >= MAX_SOURCES - 1) best.dropLast(1) else best) + other
            }
        }
        // se não sobrou nenhuma boa, deixa uma morta como última tentativa (o teste pode ter errado)
        if (best.isEmpty()) dead.sortedByDescending { health[it.url]?.checked ?: 0 }.take(1) else best
    }

    /** Guarda que este link não abriu (link morto, 404, imagem congelada...). */
    fun markFailed(url: String) {
        scope.launch { runCatching { db.setHealth(url, ok = false, ms = null) } }
    }

    /** Guarda que este link tocou de verdade no player. */
    fun markWorking(url: String) {
        scope.launch { runCatching { db.setHealth(url, ok = true, ms = null) } }
    }

    // ---------- Teste das fontes em segundo plano ----------

    private val checkLock = Mutex()
    private val checker by lazy { SourceChecker(http) }

    /** Poucas linhas de baixa prioridade: testar links é quase só esperar a rede. */
    private val checkPool = java.util.concurrent.Executors.newFixedThreadPool(CHECK_PARALLEL) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
            r.run()
        }, "minhatv-check").apply { isDaemon = true }
    }

    fun checkSourcesAsync(budget: Int = 3000) { scope.launch { checkSources(budget) } }

    /**
     * Testa até [budget] links (canais ao vivo primeiro). Para assim que o player abre,
     * para não roubar internet do vídeo. O que faltar continua na próxima rodada.
     */
    suspend fun checkSources(budget: Int = 3000) = withContext(Dispatchers.IO) {
        if (checkLock.isLocked || !checkSourcesEnabled || listReady) return@withContext
        checkLock.withLock {
            awaitLoaded()
            var done = 0
            var alive = 0
            var deadCount = 0
            runCatching { db.pruneHealth() }
            // uma consulta só por rodada (a lista inteira de filmes pode ter centenas de milhares de itens)
            val queue = runCatching { db.toProbe(budget, CHECK_OK_AGE, CHECK_BAD_AGE) }.getOrDefault(emptyList())
            for (batch in queue.chunked(CHECK_BATCH)) {
                if (playbackActive || !checkSourcesEnabled || !networkUp()) break
                if (done > 0) kotlinx.coroutines.delay(1_000) // folga entre um lote e outro
                val results = batch.map { p ->
                    checkPool.submit<Pair<SourceChecker.Result, Db.Probe>> {
                        if (playbackActive) SourceChecker.Result.Unknown to p
                        else checker.check(p.url, p.userAgent, p.referrer) to p
                    }
                }.map { it.get() }
                // tudo falhou de uma vez e a rede caiu: não marca nada
                if (results.none { it.first is SourceChecker.Result.Ok } && !networkUp()) break
                for ((r, p) in results) {
                    when (r) {
                        is SourceChecker.Result.Ok -> { db.setHealth(p.url, true, r.ms); alive++ }
                        SourceChecker.Result.Dead -> { db.setHealth(p.url, false, null); deadCount++ }
                        SourceChecker.Result.Unknown -> Unit
                    }
                }
                done += batch.size
            }
            if (done > 0) android.util.Log.i("MinhaTV", "teste de fontes: $done links, $alive ok, $deadCount com falha")
        }
    }

    private fun networkUp(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        cm.activeNetworkInfo?.isConnected == true
    }.getOrDefault(true)

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

    /** Página da grade: uma letra (null = os mais novos). */
    suspend fun moviesPage(group: String?, letter: Char?, limit: Int = 1500): List<Entry> = withContext(Dispatchers.IO) {
        runCatching {
            (if (letter == null) db.moviesNewest(group, limit) else db.moviesByLetter(group, letter, limit)).visible().unique()
        }.getOrDefault(emptyList())
    }

    suspend fun showsPage(group: String?, letter: Char?, limit: Int = 1500): List<Show> = withContext(Dispatchers.IO) {
        runCatching { db.showsPage(group, letter, limit).let { l -> if (hideAdult && !listReady) l.filterNot { Library.isAdult(it.group) } else l } }
            .getOrDefault(emptyList())
    }

    suspend fun recentMovies(limit: Int = 30): List<Entry> = withContext(Dispatchers.IO) {
        // títulos em outro alfabeto (hindi, chinês...) vão para o fim da fileira; a ordem do banco fica no resto
        runCatching { db.recent(limit * 3).visible().unique().sortedBy { nonLatin(it.name) }.take(limit) }.getOrDefault(emptyList())
    }

    /** O título tem letras de outro alfabeto (devanágari, chinês, árabe...). */
    private fun nonLatin(s: String): Boolean = s.any { c ->
        c.isLetter() && Character.UnicodeScript.of(c.code) != Character.UnicodeScript.LATIN
    }

    @Volatile private var popularCache: Pair<Long, List<Entry>>? = null

    /**
     * "Em alta no Brasil": filmes populares agora no Brasil (TMDB, região BR) que existem nas listas.
     * Vazio sem a chave do TMDB. Guardado por 6 horas.
     */
    suspend fun popularMovies(limit: Int = 30): List<Entry> = withContext(Dispatchers.IO) {
        if (!Tmdb.enabled) return@withContext emptyList()
        popularCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 6 * 3_600_000L && list.isNotEmpty()) return@withContext list }
        runCatching {
            val titles = Tmdb.popularBr()
            val keys = titles.flatMap { t ->
                listOfNotNull(
                    t.year?.let { Normalize.key("${t.title} ($it)") }, Normalize.key(t.title),
                    t.original?.let { o -> t.year?.let { Normalize.key("$o ($it)") } }, t.original?.let { Normalize.key(it) },
                ).filter { it.length >= 2 }
            }
            val found = db.moviesByKeys(keys).visible().unique()
            // um por título do TMDB, na ordem de popularidade
            val seen = HashSet<String>()
            found.filter { seen.add(Normalize.name(Normalize.displayCached(it.name).title).replace(Regex("(19|20)\\d{2}$"), "")) }.take(limit)
        }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) popularCache = System.currentTimeMillis() to it }
    }

    /** "Mais como este": os parecidos do TMDB que existem na lista; completa com os mais novos da mesma categoria. */
    suspend fun similarMovies(e: Entry, related: List<String>): List<Entry> = withContext(Dispatchers.IO) {
        runCatching {
            val found = related.mapNotNull { t -> db.movieByNorm(Normalize.name(t)) }
            val fill = if (found.size < 12) db.moviesNewest(e.group, 40) else emptyList()
            (found + fill).filter { it.url != e.url }.visible().unique().filter { Normalize.name(Normalize.displayCached(it.name).title) != Normalize.name(Normalize.displayCached(e.name).title) }.take(24)
        }.getOrDefault(emptyList())
    }

    suspend fun recentShows(limit: Int = 30): List<Show> = withContext(Dispatchers.IO) {
        runCatching { db.recentShows(limit).let { l -> if (hideAdult && !listReady) l.filterNot { Library.isAdult(it.group) } else l } }.getOrDefault(emptyList())
    }

    // ---- Buscas recentes ----
    fun recentSearches(): List<String> = prefs.getString("recent_searches", "")!!.split('\n').filter { it.isNotBlank() }
    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val list = (listOf(t) + recentSearches().filterNot { it.equals(t, ignoreCase = true) }).take(8)
        prefs.edit().putString("recent_searches", list.joinToString("\n")).apply()
    }

    suspend fun shows(group: String?, limit: Int = 20_000): List<Show> {
        val ck = "s|$group|$limit"
        @Suppress("UNCHECKED_CAST")
        if (limit <= 50) (rowCache[ck] as? List<Show>)?.let { return it }
        return withContext(kotlinx.coroutines.NonCancellable) { showsUncached(group, limit) }.also { if (limit <= 50 && it.isNotEmpty()) rowCache[ck] = it }
    }

    private suspend fun showsUncached(group: String?, limit: Int): List<Show> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val r = runCatching { db.shows(group, limit).let { l -> if (hideAdult && !listReady) l.filterNot { Library.isAdult(it.group) } else l } }
            .getOrDefault(emptyList())
        val ms = System.currentTimeMillis() - t0
        if (ms > 300) android.util.Log.w("MinhaTV", "lento: séries \"$group\" ${r.size} em $ms ms")
        r
    }

    /** Resultado de abrir uma série: episódios, ou o motivo de não terem vindo. */
    class Episodes(val list: List<Entry>, val error: String? = null, val versions: Set<String> = emptySet())

    suspend fun episodes(show: Show): Episodes = withContext(Dispatchers.IO) {
        var error: String? = null
        var versions = emptySet<String>()
        val list = runCatching {
            // episódios guardados com a leitura antiga (sem a versão Legendada) são buscados de novo
            db.dropFetchedEpisodes(Xtream.EPISODES_VERSION, _playlists.value.filter { it.isXtream }.map { it.id })
            val rows = db.episodes(show.key)
            val covers = rows.filter { Xtream.isStub(it.url) }
            var list = rows.filterNot { Xtream.isStub(it.url) }
            // série de servidor Xtream: os episódios são pedidos agora, na primeira vez que ela é aberta
            var fetched = false
            for (c in covers) {
                if (list.any { it.playlistId == c.playlistId }) continue
                val p = _playlists.value.firstOrNull { it.id == c.playlistId && it.isXtream } ?: continue
                val series = c.seriesName ?: show.name
                val t0 = System.currentTimeMillis()
                val api = xtreamQuick(p)
                val eps = runCatching { api.episodes(Xtream.stubSeriesId(c.url), series, c.group, c.logo) }
                    .onFailure {
                        android.util.Log.w("MinhaTV", "episódios de \"$series\" não vieram em ${System.currentTimeMillis() - t0} ms", it)
                        error = if (it is java.io.InterruptedIOException) "o servidor não respondeu" else (it.message ?: "sem conexão")
                    }
                    .getOrDefault(emptyList())
                val t1 = System.currentTimeMillis()
                if (eps.isNotEmpty()) { db.addEpisodes(p.id, show.key, series, c.group, eps); fetched = true }
                android.util.Log.i("MinhaTV", "tempo: ${eps.size} episódios de \"$series\": rede+leitura ${t1 - t0} ms, gravação ${System.currentTimeMillis() - t1} ms")
                val leg = eps.count { versionOf(it) == LEG }
                val dub = eps.count { versionOf(it) == DUB }
                android.util.Log.i("MinhaTV", "versões de \"$series\": $leg legendados, $dub dublados, ${eps.size - leg - dub} sem marca · ex.: ${eps.take(2).joinToString(" | ") { it.name }} · servidor: ${api.rawSample.joinToString(" | ")}")
            }
            if (fetched) list = db.episodes(show.key).filterNot { Xtream.isStub(it.url) }
            // versões de todas as cópias (a lista abaixo mostra só uma por episódio)
            versions = list.map { versionOrDub(it) }.toSet()
            list.uniqueEpisodes()
        }.getOrDefault(emptyList())
        Episodes(list, if (list.isEmpty()) error else null, versions)
    }

    /**
     * Um item por episódio: várias listas trazem o mesmo "T1 E1"; fica a cópia que respondeu
     * melhor no teste, e as outras viram fontes alternativas no player.
     */
    private fun List<Entry>.uniqueEpisodes(): List<Entry> {
        if (size < 2) return this
        val health = runCatching { db.health(map { it.url }) }.getOrDefault(emptyMap())
        val groups = LinkedHashMap<Any, MutableList<Entry>>()
        for (e in this) {
            val ep = e.episode
            val key: Any = if (ep == null) e.url else (e.season ?: 0) to ep
            groups.getOrPut(key) { mutableListOf() } += e
        }
        // a versão escolhida para a série (Dublado/Legendado) vem primeiro; depois a fonte que respondeu melhor
        val want = versionFor(first())
        fun vrank(e: Entry) = if (versionOrDub(e) == want) 0 else 1
        return groups.values.map { list -> list.minWith(compareBy<Entry>({ vrank(it) }, { rank(health[it.url]) })) }
    }

    /** [quick] = só nomes que começam com o texto (instantâneo); sem ele, também os que contêm. */
    suspend fun search(q: String, quick: Boolean = false): Pair<List<Entry>, List<Show>> = withContext(Dispatchers.IO) {
        runCatching {
            val t0 = System.currentTimeMillis()
            val (e, s) = db.search(q, quick = quick)
            android.util.Log.w("MinhaTV", "busca${if (quick) " rápida" else ""} \"$q\": ${e.size + s.size} em ${System.currentTimeMillis() - t0} ms")
            e.visible().unique() to (if (hideAdult && !listReady) s.filterNot { Library.isAdult(it.group) } else s)
        }.getOrDefault(emptyList<Entry>() to emptyList())
    }

    @Volatile private var favCache: Pair<Set<String>, List<Entry>>? = null

    /** Itens da Minha lista. Guardado em memória: abrir a tela de novo é instantâneo. */
    suspend fun favoriteEntries(): List<Entry> {
        val favs = _favorites.value
        favCache?.let { (k, v) -> if (k == favs) return v }
        return withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val list = runCatching { db.byUrls(favs).unique(hideDead = false) }.getOrDefault(emptyList())
            android.util.Log.w("MinhaTV", "minha lista: ${list.size} itens em ${System.currentTimeMillis() - t0} ms")
            favCache = favs to list
            list
        }
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

    private fun <T> open(url: String, onResponse: (okhttp3.Response) -> Unit = {}, block: (InputStream) -> T): T {
        val req = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()
        execute(req).use { resp ->
            if (!resp.isSuccessful) throw IOException("servidor respondeu ${resp.code}")
            onResponse(resp)
            val raw = BufferedInputStream(resp.body!!.byteStream(), 64 * 1024)
            raw.mark(4)
            val b1 = raw.read()
            val b2 = raw.read()
            raw.reset()
            val stream: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
            return block(stream)
        }
    }

    /**
     * Conecta com até 3 tentativas (espera 5 s, depois 20 s). Ajuda quando a TV acabou de sair
     * do modo de espera e a rede ainda não voltou, ou o servidor demorou a responder.
     */
    private fun execute(req: Request): okhttp3.Response {
        var last: IOException? = null
        for ((i, wait) in listOf(0L, 5_000L, 20_000L).withIndex()) {
            if (wait > 0) Thread.sleep(wait)
            try {
                val resp = http.newCall(req).execute()
                if (resp.code >= 500 && i < 2) { resp.close(); last = IOException("servidor respondeu ${resp.code}"); continue }
                return resp
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("sem conexão")
    }

    /** Listas que a última conferência achou iguais (não precisa reorganizar nada por causa delas). */
    private val unchangedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Versão de cada categoria da última importação de um servidor: chave -> (versão, nome da categoria). */
    private fun loadParts(id: String): Map<String, Pair<String, String>>? = runCatching {
        val o = org.json.JSONObject(prefs.getString("listparts_$id", null) ?: return null)
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { it.getString(0) to it.optString(1) } }
    }.getOrNull()

    private fun saveParts(id: String, parts: Map<String, String>?, names: Map<String, String>) {
        if (parts == null) { prefs.edit().remove("listparts_$id").apply(); return }
        val o = org.json.JSONObject()
        for ((k, v) in parts) o.put(k, org.json.JSONArray().put(v).put(names[k] ?: ""))
        prefs.edit().putString("listparts_$id", o.toString()).apply()
    }

    /**
     * Baixa do servidor só as categorias cuja versão mudou e troca só elas no banco.
     * Devolve quantos itens mudaram, ou null se é melhor baixar tudo (primeira vez, ou mudou quase tudo).
     */
    private fun updateChangedParts(p: Playlist, x: Xtream, now: Map<String, String>): Int? {
        val old = loadParts(p.id) ?: return null
        val changed = (now.keys + old.keys).filter { now[it] != old[it]?.first }
        val names = HashMap<String, String>()
        old.forEach { (k, v) -> names[k] = v.second }
        if (changed.isEmpty()) { saveParts(p.id, now, names); return 0 }
        // mudou mais da metade: baixar tudo de uma vez sai mais rápido que categoria por categoria
        if (changed.size * 2 > now.size) return null
        android.util.Log.i("MinhaTV", "${p.name}: ${changed.size} de ${now.size} categorias mudaram: $changed")
        var total = 0
        for ((kind, action, type) in listOf(
            Triple("live", "get_live_categories", ContentType.LIVE),
            Triple("vod", "get_vod_categories", ContentType.MOVIE),
            Triple("series", "get_series_categories", ContentType.SERIES),
        )) {
            val mine = changed.filter { it.startsWith("$kind:") }
            if (mine.isEmpty()) continue
            val cats = x.categories(action)
            cats.forEach { (id, nome) -> names["$kind:$id"] = nome }
            // categorias juntadas pelas regras viram uma só no banco: se uma mudou, recarrega todas as que caem nela
            val groups = mine.mapNotNull { k -> (cats[k.substringAfter(':')] ?: old[k]?.second)?.let { db.groupLabel(it) } }.toSet()
            val ids = cats.filterValues { db.groupLabel(it) in groups }.keys
            _status.value = "Atualizando ${p.name}: ${groups.size} categoria(s)…"
            total += db.updateGroups(p.id, type, groups) { add ->
                for (id in ids) when (type) {
                    ContentType.LIVE -> x.live(id, cats, add)
                    ContentType.MOVIE -> x.movies(id, cats, add)
                    ContentType.SERIES -> x.seriesCovers(p.id, id, cats, add)
                }
            }
        }
        saveParts(p.id, now, names)
        android.util.Log.i("MinhaTV", "${p.name}: $total itens mudaram")
        return total
    }

    /** Pergunta ao servidor se a lista mudou desde a última vez (ETag / Last-Modified). */
    private fun unchanged(p: Playlist): Boolean {
        val etag = prefs.getString("etag_${p.id}", null)
        val modified = prefs.getString("lastmod_${p.id}", null)
        if (etag == null && modified == null) return false
        return runCatching {
            val req = Request.Builder().url(p.url).header("User-Agent", DEFAULT_UA).apply {
                etag?.let { header("If-None-Match", it) }
                modified?.let { header("If-Modified-Since", it) }
            }.build()
            http.newCall(req).execute().use { it.code == 304 }
        }.getOrDefault(false)
    }

    private fun saveValidators(id: String, resp: okhttp3.Response) {
        prefs.edit()
            .putString("etag_$id", resp.header("ETag"))
            .putString("lastmod_$id", resp.header("Last-Modified"))
            .apply()
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
