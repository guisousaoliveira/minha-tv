// Copiado de app/src/main/java/dev/guisousa/minhatv/data/Repository.kt (vai para o módulo core depois)
// Adaptado: o que é do usuário foi para LocalStore.kt; sem limpeza de versões antigas.
package dev.guisousa.badwolf.mobile.data

import android.content.Context
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
import java.io.File

/**
 * Tudo o que as telas e o player precisam. Os dados vêm prontos do servidor ([Catalogo]): canais com as
 * fontes em ordem, filmes e séries já juntos, capas, sinopses e guia. O que é do usuário (Minha lista,
 * onde parou, recentes, versão) fica guardado em [LocalStore]; aqui só repassa.
 */
object Repository {

    const val DEFAULT_UA = Catalogo.UA
    private const val SYNC_EVERY_MS = 10 * 60_000L

    private lateinit var app: Context
    private lateinit var catalogo: Catalogo
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    @Volatile private var lastSync = 0L

    /** true enquanto um vídeo toca (a atualização em segundo plano espera). */
    @Volatile var playbackActive = false

    private val _library = MutableStateFlow(Library.EMPTY)
    val library: StateFlow<Library> = _library
    private val _epg = MutableStateFlow(EpgData.EMPTY)
    val epg: StateFlow<EpgData> = _epg
    val favorites: StateFlow<Set<String>> get() = LocalStore.favorites
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
        LocalStore.init(app)
        SourceHealth.init(app)
        catalogo = Catalogo(File(app.filesDir, "v2"), Catalogo.defaultHttp())
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

    /** Dados do destaque (tela no estilo cinema): fundo largo, gêneros e versões, tudo do cartão. */
    class Destaque(
        val fundo: String?, val generos: List<String>, val ano: Int?, val versoes: String,
        val serie: Boolean, val temporadas: Int = 0, val episodios: Int = 0, val k4: Boolean = false,
    )

    /** Referência usada no destaque para uma série (mesmo prefixo dos episódios). */
    fun showRef(s: Show) = E + s.key

    private fun fundoUrl(b: String?) = when {
        b.isNullOrBlank() -> null
        b.startsWith("http") -> b
        else -> "https://image.tmdb.org/t/p/w1280/$b"
    }

    fun destaque(ref: String?): Destaque? {
        val d = dados ?: return null
        ref ?: return null
        return when {
            ref.startsWith(F) -> d.filmePorId[idOf(ref, F) ?: return null]?.first?.let { f ->
                Destaque(fundoUrl(f.b), f.g.orEmpty(), f.a, f.v, serie = false, k4 = f.k == "4K")
            }
            ref.startsWith(E) -> d.seriePorId[idOf(ref, E) ?: return null]?.first?.let { s ->
                Destaque(fundoUrl(s.b), s.g.orEmpty(), s.a, s.v, serie = true, temporadas = s.nt, episodios = s.ne)
            }
            else -> null
        }
    }

    /** Sinopse e fundo da ficha (baixa a ficha se ainda não estiver no aparelho). */
    suspend fun fichaDe(ref: String?): Ficha? {
        ref ?: return null
        return runCatching {
            when {
                ref.startsWith(F) -> catalogo.ficha(false, idOf(ref, F) ?: return null)
                ref.startsWith(E) -> catalogo.ficha(true, idOf(ref, E) ?: return null)
                else -> null
            }
        }.getOrNull()
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
        val dub = SourceHealth.order(v.dub.map { fonteEntry(e, it, DUB) }) { it.url }
        val leg = SourceHealth.order(v.leg.map { fonteEntry(e, it, LEG) }) { it.url }
        return if (versionFor(e) == LEG) leg + dub else dub + leg
    }

    /**
     * Links para tocar o item, já em ordem: canal na ordem do servidor (com a que já tocou aqui primeiro e as que falharam no fim); filme/episódio com a versão
     * escolhida (Dublado/Legendado) primeiro e a outra depois.
     */
    suspend fun sources(e: Entry): List<Entry> {
        val d = dados
        return when {
            e.url.startsWith(C) -> {
                val c = idOf(e.url, C)?.let { d?.canalPorId?.get(it) } ?: return emptyList()
                SourceHealth.order(c.f.map { fonteEntry(e, it, null) }) { it.url }
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

    fun recentSearches(): List<String> = LocalStore.recentSearches()
    fun addRecentSearch(q: String) = LocalStore.addRecentSearch(q)

    // ---------- Minha lista ----------

    fun toggleFavorite(url: String): Boolean = LocalStore.toggleFavorite(url)

    /** Na TV a Minha lista guarda o episódio da série; aqui a série conta como na lista se algum episódio dela estiver. */
    fun isShowFavorite(s: Show, favs: Set<String> = favorites.value): Boolean {
        val p = E + s.key + ":"
        return favs.any { it.startsWith(p) }
    }

    /** Liga/desliga a série na Minha lista (guarda o episódio [target], como a TV). */
    fun toggleShowFavorite(s: Show, target: String?): Boolean {
        val p = E + s.key + ":"
        val now = favorites.value
        return if (now.any { it.startsWith(p) }) { LocalStore.setFavorites(now.filterNot { it.startsWith(p) }.toSet()); false }
        else { LocalStore.setFavorites(now + (target ?: "$p" + "1:1")); true }
    }

    /** Minha lista para mostrar: canais e filmes como [Entry], séries como [Show] (uma vez cada). */
    suspend fun favoriteItems(): List<Any> = withContext(Dispatchers.Default) {
        val d = dados ?: return@withContext emptyList()
        val seen = HashSet<Long>()
        favorites.value.toList().mapNotNull { url ->
            if (url.startsWith(E)) {
                val sid = idOf(url, E) ?: return@mapNotNull null
                if (!seen.add(sid)) null else d.seriePorId[sid]?.let { (s, c) -> serieShow(d, s, c) }
            } else entryOf(d, url)
        }.sortedBy { if (it is Entry) it.type.ordinal else ContentType.SERIES.ordinal }
    }

    @Volatile private var favCache: Pair<Set<String>, List<Entry>>? = null

    suspend fun favoriteEntries(): List<Entry> = withContext(Dispatchers.Default) {
        val favs = favorites.value
        favCache?.let { (k, v) -> if (k == favs) return@withContext v }
        val d = dados ?: return@withContext emptyList()
        favs.mapNotNull { entryOf(d, it) }.sortedBy { it.type }.also { favCache = favs to it }
    }

    // ---------- Onde parou ----------

    fun getResume(url: String): Long = LocalStore.getResume(url)
    fun saveResume(url: String, position: Long, duration: Long) = LocalStore.saveResume(url, position, duration)
    val watched: StateFlow<Set<String>> get() = LocalStore.watched
    fun isWatched(url: String) = LocalStore.isWatched(url)
    fun markWatched(url: String) = LocalStore.markWatched(url)
    fun unmarkWatched(url: String) = LocalStore.unmarkWatched(url)
    fun clearResume(url: String) = LocalStore.clearResume(url)
    fun duration(url: String): Long = LocalStore.duration(url)
    fun progress(url: String): Float? = LocalStore.progress(url)

    // ---------- Recentes ----------

    fun addRecent(e: Entry) = LocalStore.addRecent(e)
    fun recentLive(): List<Entry> = LocalStore.recentLive()

    /** Filmes/episódios começados e não terminados. */
    fun continueWatching(): List<Entry> = LocalStore.recentVod().filter { getResume(it.url) > 0 }

    /** Série de um episódio (para abrir a tela da série a partir de "Continuar assistindo"). */
    fun showOf(e: Entry): Show? {
        val d = dados ?: return null
        if (!e.url.startsWith(E)) return null
        return d.seriePorId[idOf(e.url, E) ?: return null]?.let { (s, c) -> serieShow(d, s, c) }
    }

    /** Filme a partir da chave (v2:f:id). */
    fun movieOf(url: String): Entry? = dados?.let { entryOf(it, url) }?.takeIf { it.type == ContentType.MOVIE }

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

    const val DUB = LocalStore.DUB
    const val LEG = LocalStore.LEG

    var preferredVersion: String
        get() = LocalStore.preferredVersion
        set(v) { LocalStore.preferredVersion = v }

    fun versionFor(e: Entry): String = LocalStore.versionFor(e)
    fun setVersionFor(e: Entry, v: String) = LocalStore.setVersionFor(e, v)

    /** Versão de uma fonte (o catálogo diz). null = não diz. */
    fun versionOf(e: Entry): String? = e.version

    fun versionOrDub(e: Entry): String = versionOf(e) ?: DUB

    fun versionName(v: String) = if (v == LEG) "Legendado" else "Dublado"

    var resizeMode: Int
        get() = LocalStore.resizeMode
        set(v) { LocalStore.resizeMode = v }

    var lastChannelUrl: String?
        get() = LocalStore.lastChannelUrl
        set(v) { LocalStore.lastChannelUrl = v }

    /** Nome da categoria de canais com esse id (tela Ao vivo). */
    fun liveGroupOf(e: Entry): String = e.group
}
