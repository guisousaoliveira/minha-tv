// Adaptado de app/src/main/java/dev/guisousa/minhatv/data/Repository.kt (app da TV).
// Diferenças: sem Context/SharedPreferences; o que é do usuário (lista, progresso, vistos, recentes)
// fica na UserLibrary e os ajustes em Settings. A parte do catálogo é a mesma.
package dev.guisousa.minhatv.data

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
 * fontes em ordem, filmes e séries já juntos, capas, sinopses e guia.
 */
object Repository {

    const val DEFAULT_UA = Catalogo.UA
    private const val SYNC_EVERY_MS = 10 * 60_000L

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
        catalogo.clearFichaCache()
        _epg.value = EpgData(catalogo.guia())
        _library.value = Library(
            ++libVersion,
            cats.canais.map { GroupInfo(nomeCurto(it.nome), d.canaisPorCat[it.id]?.size ?: 0) }.filter { it.count > 0 },
            cats.filmes.map { GroupInfo(nomeCurto(it.nome), filmesPorCat[it.id]?.size ?: 0) }.filter { it.count > 0 },
            cats.series.map { GroupInfo(nomeCurto(it.nome), seriesPorCat[it.id]?.size ?: 0) }.filter { it.count > 0 },
        )
        Log.i("catálogo ${catalogo.manifesto.versao}: ${canais.size} canais, ${todosFilmes.size} filmes, ${todasSeries.size} séries")
        return true
    }

    @Synchronized
    fun init() {
        if (::catalogo.isInitialized) return
        catalogo = Catalogo(File(AppDirs.cache, "catalogo"), Catalogo.defaultHttp())
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

    private suspend fun sync(force: Boolean = false): Unit = syncLock.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < SYNC_EVERY_MS) return@withLock
        try {
            val mudou = catalogo.sync()
            lastSync = now
            if (mudou || dados == null) carregar()
            _status.value = null
        } catch (e: Exception) {
            Log.w("catálogo: não atualizou", e)
            _status.value = if (dados == null) "Não consegui baixar o catálogo. Confira a internet; tento de novo sozinho." else null
            if (dados == null) scope.launch { delay(20_000); sync(force = true) }
        }
    }

    /** Botão "Atualizar agora". */
    fun refreshAllAsync() { scope.launch { _status.value = "Atualizando o catálogo…"; sync(force = true) } }

    val versaoCatalogo: String get() = if (::catalogo.isInitialized) catalogo.manifesto.versao else ""

    // ---------- Entradas ----------

    private const val C = "v2:c:"
    private const val F = "v2:f:"
    private const val E = "v2:e:"

    fun isLive(e: Entry) = e.url.startsWith(C)

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

    /** Item a partir da chave guardada (Minha lista, Já assisti): [Entry] (filme/canal/episódio) ou [Show]. */
    fun itemOf(key: String): Any? {
        val d = dados ?: return null
        return when {
            key.startsWith(C) -> key.removePrefix(C).toLongOrNull()?.let { d.canalPorId[it] }?.let { canalEntry(d, it) }
            key.startsWith(F) -> key.removePrefix(F).toLongOrNull()?.let { d.filmePorId[it] }?.let { (f, c) -> filmeEntry(d, f, c) }
            key.startsWith(E) -> {
                val p = key.removePrefix(E).split(':')
                val sid = p.getOrNull(0)?.toLongOrNull()
                val par = sid?.let { d.seriePorId[it] } ?: return null
                val (s, c) = par
                if (p.size < 3) serieShow(d, s, c)
                else episodioEntry(s.id, s.t, d.nomeCat[c] ?: "", s.p, p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0)
            }
            else -> null
        }
    }

    /** Série a que o episódio pertence. */
    fun showOf(e: Entry): Show? {
        if (!e.url.startsWith(E)) return null
        return itemOf(E + e.url.removePrefix(E).substringBefore(':')) as? Show
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

    fun initialOf(s: String): Char {
        val c = marks.replace(java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD), "")
            .trimStart { !it.isLetterOrDigit() }.firstOrNull() ?: '#'
        return if (c in 'a'..'z') c.uppercaseChar() else '#'
    }

    /** Página da grade: uma letra (null = os mais novos). */
    suspend fun moviesPage(group: String?, letter: Char?, limit: Int = 3000): List<Entry> {
        val all = entries(ContentType.MOVIE, group)
        return withContext(Dispatchers.Default) {
            (if (letter == null) all.sortedByDescending { it.added ?: 0 } else all.filter { initialOf(it.name) == letter }.sortedBy { it.name.lowercase() }).take(limit)
        }
    }

    suspend fun showsPage(group: String?, letter: Char?, limit: Int = 3000): List<Show> = withContext(Dispatchers.Default) {
        val d = dados ?: return@withContext emptyList()
        val cat = if (group == null) null else d.catSerie[group] ?: return@withContext emptyList()
        val cards = if (cat == null) d.todasSeries else d.seriesPorCat[cat].orEmpty()
        (if (letter == null) cards.sortedByDescending { it.m ?: 0 } else cards.filter { initialOf(it.t) == letter }.sortedBy { it.t.lowercase() })
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

    /** Filmes parecidos: os da mesma categoria. */
    /** Parecidos: filmes com pôster que mais dividem gêneros com este (mesma categoria vale um pouco mais). */
    suspend fun similarMovies(e: Entry): List<Entry> = withContext(Dispatchers.Default) {
        val d = dados ?: return@withContext emptyList()
        val id = idOf(e.url, F) ?: return@withContext emptyList()
        val (me, cat) = d.filmePorId[id] ?: return@withContext emptyList()
        val gens = me.g.orEmpty().toSet()
        if (gens.isEmpty()) return@withContext d.filmesPorCat[cat].orEmpty().asSequence()
            .filter { it.id != id && !it.p.isNullOrBlank() }.take(24).map { filmeEntry(d, it) }.toList()
        val vistos = HashSet<String>()
        d.todosFilmes.asSequence()
            .filter { it.id != id && !it.p.isNullOrBlank() && it.t != me.t }
            .map { f ->
                val comum = f.g.orEmpty().count { it in gens }
                val pts = comum * 10 + (if (d.filmePorId[f.id]?.second == cat) 3 else 0) +
                    (if (me.a != null && f.a != null) (5 - kotlin.math.abs(me.a - f.a)).coerceAtLeast(0) else 0)
                f to (if (comum == 0) 0 else pts)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .filter { vistos.add(it.t.lowercase()) }
            .take(24).map { filmeEntry(d, it) }.toList()
    }

    // ---------- Fichas ----------

    private fun idOf(url: String, prefix: String) = url.removePrefix(prefix).substringBefore(':').toLongOrNull()

    /** Dados do destaque (tela no estilo cinema): fundo largo, gêneros e versões, tudo do cartão. */
    class Destaque(
        val fundo: String?, val generos: List<String>, val ano: Int?, val versoes: String,
        val serie: Boolean, val temporadas: Int = 0, val episodios: Int = 0, val k4: Boolean = false,
    )

    /** Referência usada no destaque para uma série (mesmo prefixo dos episódios). */
    fun showRef(s: Show) = E + s.key

    fun fundoUrl(b: String?) = when {
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

    /** Sinopse e fundo da ficha (baixa a ficha se ainda não estiver no computador). */
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
        val f = catalogo.ficha(true, id) ?: return@withContext Episodes(emptyList(), "Não consegui baixar a ficha (sem internet?)")
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
     * Links para tocar o item, já em ordem: canal na ordem do servidor (com a que já tocou aqui primeiro e as que
     * falharam no fim); filme/episódio com a versão escolhida (Dublado/Legendado) primeiro e a outra depois.
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

    class SearchResult(val movies: List<Entry>, val shows: List<Show>, val channels: List<Entry>)

    suspend fun search(q: String): SearchResult = withContext(Dispatchers.Default) {
        val d = dados ?: return@withContext SearchResult(emptyList(), emptyList(), emptyList())
        val k = Normalize.search(q.trim())
        if (k.isEmpty()) return@withContext SearchResult(emptyList(), emptyList(), emptyList())
        val idx = index(d)
        val starts = idx.filter { it.key.startsWith(k) || it.key.contains(" $k") }
        val hits = starts + idx.filter { k in it.key && !(it.key.startsWith(k) || it.key.contains(" $k")) }
        val entries = hits.mapNotNull { it.item as? Entry }
        SearchResult(
            movies = entries.filter { it.type == ContentType.MOVIE }.take(200),
            shows = hits.mapNotNull { it.item as? Show }.take(120),
            channels = entries.filter { it.type == ContentType.LIVE }.take(120),
        )
    }

    // ---------- Ao vivo ----------

    /** Canal e a categoria dele (para trocar de canal no player). */
    suspend fun channelQueue(url: String): Pair<List<Entry>, Int>? {
        val d = dados ?: return null
        val c = idOf(url, C)?.let { d.canalPorId[it] } ?: return null
        val list = entries(ContentType.LIVE, d.nomeCat[c.c])
        val i = list.indexOfFirst { it.url == url }
        return if (i < 0) null else list to i
    }

    // ---------- Versão ----------

    const val DUB = "dub"
    const val LEG = "leg"

    private fun titleKey(e: Entry) = if (e.type == ContentType.SERIES) e.seriesName ?: e.name else e.name

    /** Versão escolhida para este título (botão "Versão"), ou a preferida do app. */
    fun versionFor(e: Entry): String = UserLibrary.versao(titleKey(e)) ?: Settings.value.versaoPreferida
    fun setVersionFor(e: Entry, v: String) = UserLibrary.definirVersao(titleKey(e), v)

    fun versionName(v: String) = if (v == LEG) "Legendado" else "Dublado"
}
