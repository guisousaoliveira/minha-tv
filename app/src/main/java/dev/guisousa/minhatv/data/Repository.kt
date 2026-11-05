package dev.guisousa.minhatv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tudo o que as telas e o player precisam. Os dados vêm prontos do servidor ([Catalogo]): canais com as
 * fontes em ordem, filmes e séries já juntos, capas, sinopses e guia. Aqui só fica o que é da TV:
 * Minha lista, onde parou, versão preferida e recentes.
 */
object Repository {

    const val DEFAULT_UA = Catalogo.UA
    private const val MIN_RESUME_MS = 5_000L
    private const val SYNC_EVERY_MS = 10 * 60_000L

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var catalogo: Catalogo
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val syncLock = Mutex()
    @Volatile private var lastSync = 0L

    /** true enquanto um vídeo toca (a atualização em segundo plano espera). */
    @Volatile var playbackActive = false

    private val _library = MutableStateFlow(Library.EMPTY)
    val library: StateFlow<Library> = _library
    private val _epg = MutableStateFlow(EpgData.EMPTY)
    val epg: StateFlow<EpgData> = _epg
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites
    private val _status = MutableStateFlow<String?>(null)
    /** Mensagem de progresso (ex.: "Baixando o catálogo…"), null quando não há nada acontecendo. */
    val status: StateFlow<String?> = _status
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    // ---------- Dados em memória ----------

    private class Dados(
        val canais: List<Canal>,
        val canalPorId: Map<Long, Canal>,
        val canaisPorCat: Map<String, List<Canal>>,
        val filmesPorCat: Map<String, List<CardFilme>>,
        val filmePorId: Map<Long, Pair<CardFilme, String>>,
        val seriesPorCat: Map<String, List<CardSerie>>,
        val seriePorId: Map<Long, Pair<CardSerie, String>>,
        val inicio: List<Linha>,
        /** id da categoria -> nome para mostrar */
        val nomeCat: Map<String, String>,
        val catLive: Map<String, String>,
        val catFilme: Map<String, String>,
        val catSerie: Map<String, String>,
        val todosFilmes: List<CardFilme>,
        val todasSeries: List<CardSerie>,
    )

    @Volatile private var dados: Dados? = null
    private var libVersion = 0L

    /** "Filmes · Ação" -> "Ação" (a aba já diz que é filme). "24h · Novelas" fica como está. */
    private fun nomeCurto(nome: String) =
        if (nome.startsWith("Filmes · ") || nome.startsWith("Séries · ")) nome.substringAfter(" · ") else nome

    private fun carregar(): Boolean {
        val cats = catalogo.categorias() ?: return false
        val canais = catalogo.canais().orEmpty()
        val nomeCat = HashMap<String, String>()
        (cats.canais + cats.filmes + cats.series).forEach { nomeCat[it.id] = nomeCurto(it.nome) }
        val filmesPorCat = LinkedHashMap<String, List<CardFilme>>()
        cats.filmes.forEach { c -> filmesPorCat[c.id] = catalogo.filmes(c.id).orEmpty() }
        val seriesPorCat = LinkedHashMap<String, List<CardSerie>>()
        cats.series.forEach { c -> seriesPorCat[c.id] = catalogo.series(c.id).orEmpty() }
        // categoria de cada título: a primeira de gênero/serviço (Lançamentos e Em Alta por último)
        val filmePorId = HashMap<Long, Pair<CardFilme, String>>()
        (cats.filmes.drop(1) + cats.filmes.take(1)).forEach { c -> filmesPorCat[c.id]?.forEach { filmePorId.putIfAbsent(it.id, it to c.id) } }
        val seriePorId = HashMap<Long, Pair<CardSerie, String>>()
        (cats.series.drop(1) + cats.series.take(1)).forEach { c -> seriesPorCat[c.id]?.forEach { seriePorId.putIfAbsent(it.id, it to c.id) } }
        val vistosF = HashSet<Long>()
        val todosFilmes = filmesPorCat.values.flatten().filter { vistosF.add(it.id) }
        val vistosS = HashSet<Long>()
        val todasSeries = seriesPorCat.values.flatten().filter { vistosS.add(it.id) }
        val d = Dados(
            canais = canais,
            canalPorId = canais.associateBy { it.id },
            canaisPorCat = canais.groupBy { it.c },
            filmesPorCat = filmesPorCat,
            filmePorId = filmePorId,
            seriesPorCat = seriesPorCat,
            seriePorId = seriePorId,
            inicio = catalogo.inicio().orEmpty(),
            nomeCat = nomeCat,
            catLive = cats.canais.associate { nomeCurto(it.nome) to it.id },
            catFilme = cats.filmes.associate { nomeCurto(it.nome) to it.id },
            catSerie = cats.series.associate { nomeCurto(it.nome) to it.id },
            todosFilmes = todosFilmes,
            todasSeries = todasSeries,
        )
        dados = d
        searchIndex = null
        favCache = null
        catalogo.clearFichaCache()
        _epg.value = EpgData(catalogo.guia())
        _library.value = Library(
            ++libVersion,
            cats.canais.map { GroupInfo(nomeCurto(it.nome), d.canaisPorCat[it.id]?.size ?: 0) }.filter { it.count > 0 },
            cats.filmes.map { GroupInfo(nomeCurto(it.nome), filmesPorCat[it.id]?.size ?: 0) }.filter { it.count > 0 },
            cats.series.map { GroupInfo(nomeCurto(it.nome), seriesPorCat[it.id]?.size ?: 0) }.filter { it.count > 0 },
        )
        android.util.Log.i("MinhaTV", "catálogo ${catalogo.manifesto.versao}: ${canais.size} canais, ${todosFilmes.size} filmes, ${todasSeries.size} séries")
        return true
    }

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("minhatv", Context.MODE_PRIVATE)
        catalogo = Catalogo(File(app.filesDir, "v2"), Catalogo.defaultHttp())
        _favorites.value = prefs.getStringSet(FAV_KEY, emptySet())!!.toSet()
        cleanupOld()
        scope.launch {
            val temLocal = try { runCatching { carregar() }.getOrDefault(false) } finally { _isLoading.value = false }
            if (!temLocal) _status.value = "Baixando o catálogo…"
            sync(force = true)
            // confere de novo de tempos em tempos enquanto o app está aberto
            while (true) {
                delay(30 * 60_000L)
                while (playbackActive) delay(60_000)
                sync()
            }
        }
    }

    /** Arquivos das versões antigas (listas, banco, guia): não são mais usados. */
    private fun cleanupOld() = scope.launch {
        runCatching {
            if (prefs.getBoolean("v2_cleaned", false)) return@runCatching
            app.deleteDatabase("minhatv.db")
            app.filesDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".json") || f.name.startsWith("epg") || f.name.startsWith("parts_"))) f.delete()
            }
            prefs.edit().remove("favorites").remove("recent_live").remove("recent_vod").putBoolean("v2_cleaned", true).apply()
        }
    }

    private suspend fun sync(force: Boolean = false) = syncLock.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < SYNC_EVERY_MS) return@withLock
        try {
            val mudou = catalogo.sync()
            lastSync = now
            if (mudou || dados == null) carregar()
            _status.value = null
        } catch (e: Exception) {
            android.util.Log.w("MinhaTV", "catálogo: não atualizou", e)
            _status.value = if (dados == null) "Não consegui baixar o catálogo. Confira a internet; tento de novo sozinho." else null
        }
    }

    /** Ao voltar para o app: confere se o catálogo mudou (no máximo a cada 10 min). */
    fun refreshIfChanged() { if (::app.isInitialized) scope.launch { sync() } }

    /** Botão "Atualizar agora". */
    fun refreshAllAsync() { scope.launch { sync(force = true) } }

    // ---------- Entradas ----------

    private const val C = "v2:c:"
    private const val F = "v2:f:"
    private const val E = "v2:e:"

    private fun canalEntry(d: Dados, c: Canal) = Entry(
        name = c.n, url = C + c.id, group = d.nomeCat[c.c] ?: "", logo = c.l, tvgId = c.id.toString(),
        type = ContentType.LIVE, quality = c.f.firstOrNull()?.q,
    )

    private fun filmeEntry(d: Dados, f: CardFilme, cat: String? = null) = Entry(
        name = f.t, url = F + f.id, group = d.nomeCat[cat ?: d.filmePorId[f.id]?.second] ?: "", logo = f.p,
        type = ContentType.MOVIE, added = f.ad, year = f.a,
    )

    private fun serieShow(d: Dados, s: CardSerie, cat: String? = null) = Show(
        key = s.id.toString(), name = s.t, group = d.nomeCat[cat ?: d.seriePorId[s.id]?.second] ?: "", logo = s.p, episodeCount = s.ne,
    )

    private fun episodioEntry(showId: Long, titulo: String, group: String, logo: String?, temporada: Int, numero: Int) = Entry(
        name = "$titulo · T$temporada E$numero", url = "$E$showId:$temporada:$numero", group = group, logo = logo,
        type = ContentType.SERIES, seriesName = titulo, season = temporada, episode = numero,
    )

    private fun versoesDoCard(v: String) = buildSet { if ('d' in v) add(DUB); if ('l' in v) add(LEG) }.ifEmpty { setOf(DUB) }

    /** Entrada a partir da chave guardada (Minha lista, recentes). */
    private fun entryOf(d: Dados, url: String): Entry? = when {
        url.startsWith(C) -> url.removePrefix(C).toLongOrNull()?.let { d.canalPorId[it] }?.let { canalEntry(d, it) }
        url.startsWith(F) -> url.removePrefix(F).toLongOrNull()?.let { d.filmePorId[it] }?.let { (f, c) -> filmeEntry(d, f, c) }
        url.startsWith(E) -> {
            val p = url.removePrefix(E).split(':')
            val sid = p.getOrNull(0)?.toLongOrNull()
            sid?.let { d.seriePorId[it] }?.let { (s, c) ->
                episodioEntry(s.id, s.t, d.nomeCat[c] ?: "", s.p, p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2)?.toIntOrNull() ?: 0)
            }
        }
        else -> null
    }

    // ---------- Telas ----------

    suspend fun entries(type: ContentType, group: String?, limit: Int = Int.MAX_VALUE): List<Entry> = withContext(Dispatchers.Default) {
        val d = dados ?: return@withContext emptyList()
        when (type) {
            ContentType.LIVE -> (if (group == null) d.canais else d.catLive[group]?.let { d.canaisPorCat[it] }.orEmpty())
                .asSequence().take(limit).map { canalEntry(d, it) }.toList()
            ContentType.MOVIE -> {
                val cat = if (group == null) null else d.catFilme[group] ?: return@withContext emptyList()
                (if (cat == null) d.todosFilmes else d.filmesPorCat[cat].orEmpty()).asSequence().take(limit).map { filmeEntry(d, it, cat) }.toList()
            }
            ContentType.SERIES -> emptyList()
        }
    }

    suspend fun shows(group: String?, limit: Int = Int.MAX_VALUE): List<Show> = withContext(Dispatchers.Default) {
        val d = dados ?: return@withContext emptyList()
        val cat = if (group == null) null else d.catSerie[group] ?: return@withContext emptyList()
        (if (cat == null) d.todasSeries else d.seriesPorCat[cat].orEmpty()).asSequence().take(limit).map { serieShow(d, it, cat) }.toList()
    }

    private val marks = Regex("\\p{M}+")

    private fun initialOf(s: String): Char {
        val c = marks.replace(java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD), "")
            .trimStart { !it.isLetterOrDigit() }.firstOrNull() ?: '#'
        return if (c in 'a'..'z') c.uppercaseChar() else '#'
    }

    /** Página da grade: uma letra (null = os mais novos). */
    suspend fun moviesPage(group: String?, letter: Char?, limit: Int = 1500): List<Entry> {
        val all = entries(ContentType.MOVIE, group)
        return withContext(Dispatchers.Default) {
            (if (letter == null) all.sortedByDescending { it.added ?: 0 } else all.filter { initialOf(it.name) == letter }).take(limit)
        }
    }

    suspend fun showsPage(group: String?, letter: Char?, limit: Int = 1500): List<Show> = withContext(Dispatchers.Default) {
        val d = dados ?: return@withContext emptyList()
        val cat = if (group == null) null else d.catSerie[group] ?: return@withContext emptyList()
        val cards = if (cat == null) d.todasSeries else d.seriesPorCat[cat].orEmpty()
        (if (letter == null) cards.sortedByDescending { it.m ?: 0 } else cards.filter { initialOf(it.t) == letter })
            .take(limit).map { serieShow(d, it, cat) }
    }

    /** Fileira da tela inicial já resolvida: cada item é um [Entry] (filme) ou um [Show] (série). */
    class HomeRow(val title: String, val items: List<Any>)

    fun homeRows(): List<HomeRow> {
        val d = dados ?: return emptyList()
        return d.inicio.mapNotNull { l ->
            val items: List<Any> = when (l.tipo) {
                "f" -> l.ids.orEmpty().mapNotNull { id -> d.filmePorId[id]?.let { (f, c) -> filmeEntry(d, f, c) } }
                "s" -> l.ids.orEmpty().mapNotNull { id -> d.seriePorId[id]?.let { (s, c) -> serieShow(d, s, c) } }
                else -> l.itens.orEmpty().mapNotNull { i ->
                    if (i.tipo == "s") d.seriePorId[i.id]?.let { (s, c) -> serieShow(d, s, c) }
                    else d.filmePorId[i.id]?.let { (f, c) -> filmeEntry(d, f, c) }
                }
            }
            if (items.isEmpty()) null else HomeRow(l.t, items)
        }
    }

    /** Fileira "Lançamentos" das páginas de Filmes e Séries. */
    fun recentMovies(): List<Entry> =
        homeRows().firstOrNull { r -> r.items.isNotEmpty() && r.items.all { it is Entry } }?.items?.filterIsInstance<Entry>().orEmpty()

    fun recentShows(): List<Show> =
        homeRows().firstOrNull { r -> r.items.isNotEmpty() && r.items.all { it is Show } }?.items?.filterIsInstance<Show>().orEmpty()

    /** "Em alta no Brasil" (só os filmes). */
    fun popularMovies(limit: Int = 30): List<Entry> =
        homeRows().filter { it.title.contains("alta", ignoreCase = true) }.flatMap { it.items.filterIsInstance<Entry>() }.take(limit)

    /** Filmes parecidos: os da mesma categoria. */
    suspend fun similarMovies(e: Entry): List<Entry> {
        val g = e.group.ifBlank { null } ?: return emptyList()
        return entries(ContentType.MOVIE, g, 40).filter { it.url != e.url }.take(24)
    }

    // ---------- Fichas ----------

    private fun idOf(url: String, prefix: String) = url.removePrefix(prefix).substringBefore(':').toLongOrNull()

    /** Capa, fundo, sinopse e gêneros do filme. */
    suspend fun movieInfo(e: Entry): Ficha? {
        if (!e.url.startsWith(F)) return null
        return catalogo.ficha(false, idOf(e.url, F) ?: return null)
    }

    /** Versões que o filme tem (sem baixar a ficha: vem no cartão). */
    fun movieVersions(e: Entry): Set<String> {
        val d = dados ?: return setOf(DUB)
        val id = idOf(e.url, F) ?: return setOf(DUB)
        return d.filmePorId[id]?.first?.v?.let { versoesDoCard(it) } ?: setOf(DUB)
    }

    class Episodes(val list: List<Entry>, val error: String? = null, val versions: Set<String> = emptySet(), val info: Ficha? = null)

    suspend fun episodes(show: Show): Episodes = withContext(Dispatchers.IO) {
        val id = show.key.toLongOrNull() ?: return@withContext Episodes(emptyList(), "série desconhecida")
        val f = catalogo.ficha(true, id) ?: return@withContext Episodes(emptyList(), "não consegui baixar a ficha (sem internet?)")
        val eps = Catalogo.episodios(f).sortedWith(compareBy({ if (it.temporada == 0) Int.MAX_VALUE else it.temporada }, { it.numero }))
        val logo = show.logo ?: f.p
        val list = eps.map { episodioEntry(id, show.name, show.group, logo, it.temporada, it.numero) }
        val versions = buildSet {
            eps.forEach { if (it.versoes.dub.isNotEmpty()) add(DUB); if (it.versoes.leg.isNotEmpty()) add(LEG) }
        }
        Episodes(list, null, versions, f)
    }

    private fun fonteEntry(e: Entry, f: Fonte, version: String?) = Entry(
        name = e.name, url = f.u, group = e.group, logo = e.logo, tvgId = e.tvgId, type = e.type,
        userAgent = f.h?.get("User-Agent"), referrer = f.h?.get("Referer"),
        seriesName = e.seriesName, season = e.season, episode = e.episode,
        quality = listOfNotNull(f.q, version?.let { versionName(it) }).joinToString(" · ").ifBlank { null },
        version = version,
    )

    private fun porVersao(e: Entry, v: Versoes): List<Entry> {
        val dub = v.dub.map { fonteEntry(e, it, DUB) }
        val leg = v.leg.map { fonteEntry(e, it, LEG) }
        return if (versionFor(e) == LEG) leg + dub else dub + leg
    }

    /**
     * Links para tocar o item, já em ordem: canal na ordem do servidor; filme/episódio com a versão
     * escolhida (Dublado/Legendado) primeiro e a outra depois.
     */
    suspend fun sources(e: Entry): List<Entry> {
        val d = dados
        return when {
            e.url.startsWith(C) -> {
                val c = idOf(e.url, C)?.let { d?.canalPorId?.get(it) } ?: return emptyList()
                c.f.map { fonteEntry(e, it, null) }
            }
            e.url.startsWith(F) -> {
                val f = catalogo.ficha(false, idOf(e.url, F) ?: return emptyList()) ?: return emptyList()
                porVersao(e, f.v ?: return emptyList())
            }
            e.url.startsWith(E) -> {
                val p = e.url.removePrefix(E).split(':')
                val f = catalogo.ficha(true, p.getOrNull(0)?.toLongOrNull() ?: return emptyList()) ?: return emptyList()
                val t = p.getOrNull(1)?.toIntOrNull()
                val n = p.getOrNull(2)?.toIntOrNull()
                val ep = Catalogo.episodios(f).firstOrNull { it.temporada == t && it.numero == n } ?: return emptyList()
                porVersao(e, ep.versoes)
            }
            else -> listOf(e)
        }
    }

    /** Quantas fontes o canal tem (para mostrar na tela, sem baixar nada). */
    fun sourceCount(e: Entry): Int =
        if (e.url.startsWith(C)) idOf(e.url, C)?.let { dados?.canalPorId?.get(it) }?.f?.size ?: 0 else 0

    // ---------- Busca ----------

    private class Hit(val key: String, val item: Any)
    @Volatile private var searchIndex: List<Hit>? = null

    private fun index(d: Dados): List<Hit> = searchIndex ?: buildList {
        d.canais.forEach { add(Hit(Normalize.search(it.n), canalEntry(d, it))) }
        d.todosFilmes.forEach { add(Hit(Normalize.search(it.t), filmeEntry(d, it))) }
        d.todasSeries.forEach { add(Hit(Normalize.search(it.t), serieShow(d, it))) }
    }.also { searchIndex = it }

    suspend fun search(q: String, quick: Boolean = false): Pair<List<Entry>, List<Show>> = withContext(Dispatchers.Default) {
        val d = dados ?: return@withContext emptyList<Entry>() to emptyList()
        val k = Normalize.search(q.trim())
        if (k.isEmpty()) return@withContext emptyList<Entry>() to emptyList()
        val idx = index(d)
        val starts = idx.filter { it.key.startsWith(k) || it.key.contains(" $k") }
        val hits = if (quick) starts else starts + idx.filter { k in it.key && !(it.key.startsWith(k) || it.key.contains(" $k")) }
        hits.mapNotNull { it.item as? Entry }.take(300) to hits.mapNotNull { it.item as? Show }.take(150)
    }

    private const val SEARCH_KEY = "recent_searches"
    fun recentSearches(): List<String> = prefs.getString(SEARCH_KEY, "")!!.split('\n').filter { it.isNotBlank() }
    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val list = (listOf(t) + recentSearches().filterNot { it.equals(t, ignoreCase = true) }).take(8)
        prefs.edit().putString(SEARCH_KEY, list.joinToString("\n")).apply()
    }

    // ---------- Minha lista ----------

    private const val FAV_KEY = "v2_favorites"

    fun toggleFavorite(url: String): Boolean {
        val now = _favorites.value.toMutableSet()
        val added = if (url in now) { now.remove(url); false } else { now.add(url); true }
        _favorites.value = now
        prefs.edit().putStringSet(FAV_KEY, now).apply()
        return added
    }

    @Volatile private var favCache: Pair<Set<String>, List<Entry>>? = null

    suspend fun favoriteEntries(): List<Entry> = withContext(Dispatchers.Default) {
        val favs = _favorites.value
        favCache?.let { (k, v) -> if (k == favs) return@withContext v }
        val d = dados ?: return@withContext emptyList()
        favs.mapNotNull { entryOf(d, it) }.sortedBy { it.type }.also { favCache = favs to it }
    }

    // ---------- Onde parou ----------

    fun getResume(url: String): Long = prefs.getLong("resume_" + url.hashCode(), 0)

    /** Guarda a posição; faltando menos de 2 min para o fim, conta como visto. */
    fun saveResume(url: String, position: Long, duration: Long) {
        val key = "resume_" + url.hashCode()
        val nearEnd = duration > 0 && position >= duration - 120_000
        if (nearEnd && position > 60_000) markWatched(url)
        if (position > MIN_RESUME_MS && !nearEnd) {
            prefs.edit().putLong(key, position).putLong("dur_" + url.hashCode(), duration).apply()
        } else if (nearEnd && position > 60_000) {
            prefs.edit().remove(key).remove("dur_" + url.hashCode()).apply()
        }
    }

    private val _watched by lazy { MutableStateFlow(prefs.getStringSet("watched", emptySet())!!.toSet()) }
    /** Hashes dos itens já vistos até o fim. */
    val watched: StateFlow<Set<String>> get() = _watched
    fun isWatched(url: String) = url.hashCode().toString() in _watched.value
    fun markWatched(url: String) {
        val h = url.hashCode().toString()
        if (h in _watched.value) return
        _watched.value = _watched.value + h
        prefs.edit().putStringSet("watched", _watched.value).apply()
    }

    /** Duração conhecida (ms). 0 = não sei. */
    fun duration(url: String): Long = prefs.getLong("dur_" + url.hashCode(), 0)

    /** Quanto já foi visto (0..1), ou null se não começou. */
    fun progress(url: String): Float? {
        val pos = getResume(url)
        val dur = prefs.getLong("dur_" + url.hashCode(), 0)
        return if (pos <= 0 || dur <= 0) null else pos.toFloat() / dur
    }

    // ---------- Recentes ----------

    private val recentLock = Any()

    fun addRecent(e: Entry) {
        if (!e.url.startsWith("v2:")) return
        val key = if (e.type == ContentType.LIVE) "v2_recent_live" else "v2_recent_vod"
        synchronized(recentLock) {
            val list = (listOf(e) + readRecent(key).filterNot { it.url == e.url }).take(30)
            prefs.edit().putString(key, json.encodeToString(ListSerializer(Entry.serializer()), list)).apply()
        }
    }

    private fun readRecent(key: String): List<Entry> = runCatching {
        json.decodeFromString(ListSerializer(Entry.serializer()), prefs.getString(key, null) ?: return emptyList())
    }.getOrDefault(emptyList())

    fun recentLive(): List<Entry> = readRecent("v2_recent_live")

    /** Filmes/episódios começados e não terminados. */
    fun continueWatching(): List<Entry> = readRecent("v2_recent_vod").filter { getResume(it.url) > 0 }

    /** Último canal assistido + a categoria dele (para trocar de canal com ▲▼). */
    suspend fun lastChannelQueue(): Pair<List<Entry>, Int>? {
        val url = lastChannelUrl ?: return null
        val d = dados ?: return null
        val c = idOf(url, C)?.let { d.canalPorId[it] } ?: return null
        val list = entries(ContentType.LIVE, d.nomeCat[c.c])
        val i = list.indexOfFirst { it.url == url }
        return if (i < 0) null else list to i
    }

    // ---------- Configurações ----------

    /** Ao abrir o app, já começa a tocar o último canal assistido. */
    var autoplayLast: Boolean
        get() = prefs.getBoolean("autoplay_last", false)
        set(v) { prefs.edit().putBoolean("autoplay_last", v).apply() }

    const val DUB = "dub"
    const val LEG = "leg"

    /** Versão preferida em todo o app (padrão: Dublado). */
    var preferredVersion: String
        get() = prefs.getString("pref_version", DUB) ?: DUB
        set(v) { prefs.edit().putString("pref_version", v).apply() }

    private fun titleKey(e: Entry) = "ver_" + (if (e.type == ContentType.SERIES) e.seriesName ?: e.name else e.name).hashCode()

    /** Versão escolhida para este título (botão "Versão"), ou a preferida do app. */
    fun versionFor(e: Entry): String = prefs.getString(titleKey(e), null) ?: preferredVersion
    fun setVersionFor(e: Entry, v: String) { prefs.edit().putString(titleKey(e), v).apply() }

    /** Versão de uma fonte (o catálogo diz). null = não diz. */
    fun versionOf(e: Entry): String? = e.version

    fun versionOrDub(e: Entry): String = versionOf(e) ?: DUB

    fun versionName(v: String) = if (v == LEG) "Legendado" else "Dublado"

    /** Formato da tela do player (AspectRatioFrameLayout.RESIZE_MODE_*). */
    var resizeMode: Int
        get() = prefs.getInt("resize_mode", 0)
        set(v) { prefs.edit().putInt("resize_mode", v).apply() }

    var lastChannelUrl: String?
        get() = prefs.getString("last_channel", null)
        set(v) { prefs.edit().putString("last_channel", v).apply() }
}
