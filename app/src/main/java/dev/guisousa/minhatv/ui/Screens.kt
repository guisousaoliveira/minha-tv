package dev.guisousa.minhatv.ui

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Library
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Show
import dev.guisousa.minhatv.player.PlaybackQueue
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val POSTER_W = 136.dp
private val CHANNEL_W = 210.dp
private const val ROW_ITEMS = 25

@Composable
private fun nowTicker(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(60_000); value = System.currentTimeMillis() }
    }
    return now
}

/** Carrega dados do banco sem travar a tela. null = carregando. */
@Composable
internal fun <T> load(vararg keys: Any?, loader: suspend () -> T): T? {
    // mantém o conteúdo antigo na tela enquanto recarrega: a lista não some, não perde a rolagem nem o foco
    val v by produceState<T?>(null, *keys) { value = loader() }
    return v
}

@Composable
internal fun Loading() {
    // esqueleto das fileiras enquanto carrega, em vez de só um texto
    Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 24.dp)) {
        repeat(2) {
            Skeleton(Modifier.width(180.dp).height(18.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(8) { Skeleton(Modifier.width(140.dp).height(210.dp)) }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

internal fun toggleFav(context: android.content.Context, e: Entry) {
    val added = Repository.toggleFavorite(e.url)
    Toast.makeText(context, if (added) "Adicionado à Minha lista" else "Removido da Minha lista", Toast.LENGTH_SHORT).show()
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun kindOf(type: ContentType) = when (type) {
    ContentType.LIVE -> "Canal"
    ContentType.MOVIE -> "Filme"
    ContentType.SERIES -> "Série"
}

/** Estado do cabeçalho que mostra o item focado. */
internal class HeaderState {
    var title by mutableStateOf<String?>(null)
    var subtitle by mutableStateOf<String?>(null)
    var image by mutableStateOf<String?>(null)
    var detail by mutableStateOf<String?>(null)
    var isLogo by mutableStateOf(false)
    fun show(t: String, s: String?, img: String? = null) { title = t; subtitle = s; image = img; detail = null; isLogo = false }

    /** Canal em foco: mostra a logo inteira e o que está passando agora/depois. */
    fun channel(e: Entry, s: String) {
        val now = System.currentTimeMillis()
        val epg = Repository.epg.value
        title = e.name; subtitle = s; image = e.logo; isLogo = true
        val cur = epg.current(e, now)
        val next = epg.next(e, now)
        detail = listOfNotNull(
            cur?.let { "Agora  ${timeFmt.format(java.util.Date(it.start))}–${timeFmt.format(java.util.Date(it.stop))}  ${it.title}" },
            next?.let { "Depois  ${timeFmt.format(java.util.Date(it.start))}  ${it.title}" },
        ).joinToString("\n").ifBlank { null }
    }
}

// ======================= Início =======================

@Composable
fun HomeScreen(lib: Library, nav: Nav) {
    val context = LocalContext.current
    val header = remember { HeaderState() }
    val favorites by Repository.favorites.collectAsState()
    val recentLive = remember(lib.version) { Repository.recentLive() }
    val cont = remember(lib.version) { Repository.continueWatching() }
    val favEntries = load(lib.version, favorites) { Repository.favoriteEntries() }.orEmpty()
    val favLive = favEntries.filter { it.type == ContentType.LIVE }
    val favVod = favEntries.filter { it.type != ContentType.LIVE }

    if (lib.isEmpty) {
        Message("Carregando as listas", "Os canais, filmes e séries aparecem aqui assim que a primeira lista terminar de carregar.")
        return
    }
    Column(Modifier.fillMaxSize()) {
        FocusHeader(header.title ?: "O que vamos assistir?", header.subtitle ?: "Início", image = header.image, detail = header.detail, logo = header.isLogo)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            if (cont.isNotEmpty()) item("cont") {
                SectionTitle("Continuar assistindo")
                MediaRow(cont, POSTER_W) { i, e ->
                    PosterCard(
                        e.name, e.logo, key = "cont:${e.url}", progress = Repository.progress(e.url) ?: 0f,
                        tmdbTitle = e.seriesName ?: e.name, tv = e.type == ContentType.SERIES,
                        onFocused = { header.show(e.name, "Continuar · ${e.group}", e.logo) },
                        onClick = { PlaybackQueue.start(context, cont, i) },
                    )
                }
            }
            if (recentLive.isNotEmpty()) item("recent") {
                SectionTitle("Assistidos recentemente")
                MediaRow(recentLive, CHANNEL_W) { i, e ->
                    ChannelCard(e.name, e.logo, e.group, onFocused = { header.channel(e, "Canal · ${e.group}") }, key = "rl:${e.url}") {
                        PlaybackQueue.start(context, recentLive, i)
                    }
                }
            }
            if (favLive.isNotEmpty()) item("favlive") {
                SectionTitle("Meus canais")
                MediaRow(favLive, CHANNEL_W) { i, e ->
                    ChannelCard(e.name, e.logo, e.group, onFocused = { header.channel(e, "Canal · ${e.group}") }, key = "fl:${e.url}") {
                        PlaybackQueue.start(context, favLive, i)
                    }
                }
            }
            if (favVod.isNotEmpty()) item("favvod") {
                SectionTitle("Minha lista")
                MediaRow(favVod, POSTER_W) { i, e ->
                    PosterCard(e.name, e.logo, key = "fav:${e.url}", onFocused = { header.show(e.name, "${kindOf(e.type)} · ${e.group}", e.logo) }, onClick = {
                        PlaybackQueue.start(context, favVod, i)
                    }, onLongClick = { toggleFav(context, e) })
                }
            }
            lib.liveGroups.take(2).forEach { g ->
                item("live-${g.name}") { EntryRow(lib, ContentType.LIVE, g.name, g.count, nav, header) }
            }
            lib.movieGroups.take(6).forEach { g ->
                item("movie-${g.name}") { EntryRow(lib, ContentType.MOVIE, g.name, g.count, nav, header) }
            }
            lib.showGroups.take(4).forEach { g ->
                item("show-${g.name}") { ShowRow(lib, g.name, g.count, nav, header) }
            }
        }
    }
}

/** Fileira de uma categoria (canais ou filmes) com "Ver tudo" no fim. */
@Composable
private fun EntryRow(lib: Library, type: ContentType, group: String, count: Int, nav: Nav, header: HeaderState) {
    val context = LocalContext.current
    val items = load(lib.version, type, group) { Repository.entries(type, group, ROW_ITEMS) }
    SectionTitle(group, trailing = "$count")
    if (items == null) { LoadingRow(if (type == ContentType.LIVE) 150.dp else 250.dp, key = group); return }
    FocusMemory.handoff(group, items.firstOrNull()?.let { "$group:${it.url}" })
    val live = type == ContentType.LIVE
    MediaRow(
        items, if (live) CHANNEL_W else POSTER_W,
        seeAll = if (count > items.size) ({ nav.openCategory(type, group) }) else null,
        seeAllCount = count,
        seeAllAspect = if (live) 16f / 9f else 2f / 3f,
    ) { i, e ->
        if (live) {
            ChannelCard(e.name, e.logo, null, onFocused = { header.channel(e, "Canal · $group") }, key = "$group:${e.url}") {
                openLiveGroup(context, type, group, e, items, i)
            }
        } else {
            PosterCard(
                e.name, e.logo, key = "$group:${e.url}", progress = Repository.progress(e.url),
                onFocused = { header.show(e.name, "Filme · $group", e.logo) },
                onClick = { nav.openMovie(e) },
                onLongClick = { toggleFav(context, e) },
            )
        }
    }
}

/** Ao abrir um canal de uma fileira, carrega a categoria inteira para trocar com ▲▼. */
private fun openLiveGroup(context: android.content.Context, type: ContentType, group: String, e: Entry, partial: List<Entry>, i: Int) {
    PlaybackQueue.startLoadingGroup(context, partial, i) { Repository.entries(type, group) }
}

@Composable
private fun ShowRow(lib: Library, group: String, count: Int, nav: Nav, header: HeaderState) {
    val shows = load(lib.version, group) { Repository.shows(group, ROW_ITEMS) }
    SectionTitle(group, trailing = "$count")
    if (shows == null) { LoadingRow(250.dp, key = group); return }
    FocusMemory.handoff(group, shows.firstOrNull()?.let { "$group:${it.key}" })
    MediaRow(
        shows, POSTER_W,
        seeAll = if (count > shows.size) ({ nav.openCategory(ContentType.SERIES, group) }) else null,
        seeAllCount = count,
    ) { _, s ->
        PosterCard(s.name, s.logo, key = "$group:${s.key}", tmdbTitle = s.name, tv = true, onFocused = { header.show(s.name, seriesLabel(s.episodeCount), s.logo) }, onClick = { nav.openShow(s) })
    }
}

// ======================= Filmes / Séries (fileiras) =======================

@Composable
fun RowsScreen(lib: Library, type: ContentType, nav: Nav) {
    val header = remember(type) { HeaderState() }
    val groups = if (type == ContentType.SERIES) lib.showGroups else lib.movieGroups
    if (groups.isEmpty()) {
        Message(if (type == ContentType.SERIES) "Nenhuma série" else "Nenhum filme", "As listas adicionadas não têm esse tipo de conteúdo (ou ainda estão carregando).")
        return
    }
    Column(Modifier.fillMaxSize()) {
        FocusHeader(header.title ?: (if (type == ContentType.SERIES) "Séries" else "Filmes"), header.subtitle ?: "${groups.size} categorias", image = header.image, detail = header.detail, logo = header.isLogo)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            // igual ao Saimo: Lançamentos, uma fileira por gênero e no fim "Todos de A a Z"
            // atalho logo no topo para a grade com tudo de A a Z
            item("all-top") {
                Row(Modifier.padding(start = 48.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Todos de A a Z  ·  ${groups.sumOf { it.count }}", onClick = { nav.openCategory(type, ALL_GROUP) }, focusKey = "all:$type")
                }
            }
            item("releases") { ReleasesRow(lib, type, nav, header) }
            if (type == ContentType.MOVIE) item("popular") { PopularRow(lib, nav, header) }
            groups.forEach { g ->
                item(g.name) {
                    if (type == ContentType.SERIES) ShowRow(lib, g.name, g.count, nav, header)
                    else EntryRow(lib, type, g.name, g.count, nav, header)
                }
            }
            item("all-az") { AllAzRow(type, groups.sumOf { it.count }, nav) }
        }
    }
}

/** Grade com tudo de uma categoria ("Ver tudo"). */
@Composable
fun CategoryScreen(lib: Library, type: ContentType, group: String, nav: Nav) {
    if (type != ContentType.LIVE) { VodGridScreen(lib, type, group, nav); return }
    val context = LocalContext.current
    val header = remember(group) { HeaderState() }
    Column(Modifier.fillMaxSize()) {
        FocusHeader(header.title ?: group, header.subtitle ?: group, image = header.image, detail = header.detail, logo = header.isLogo, tall = type != ContentType.LIVE)
        when (type) {
            ContentType.LIVE -> {
                val items = load(lib.version, group) { Repository.entries(type, group) }
                if (items == null) Loading() else ChannelList(items, Modifier.padding(start = 36.dp))
            }
            ContentType.MOVIE -> {
                val items = load(lib.version, group) { Repository.entries(type, group) }
                if (items == null) Loading() else PosterGrid(
                    items.size, { i -> items[i].name }, { i -> items[i].logo },
                    onFocused = { i -> header.show(items[i].name, "Filme · $group", items[i].logo) },
                ) { i -> nav.openMovie(items[i]) }
            }
            ContentType.SERIES -> {
                val shows = load(lib.version, group) { Repository.shows(group) }
                if (shows == null) Loading() else PosterGrid(
                    shows.size, { i -> shows[i].name }, { i -> shows[i].logo },
                    onFocused = { i -> header.show(shows[i].name, seriesLabel(shows[i].episodeCount), shows[i].logo) },
                ) { i -> nav.openShow(shows[i]) }
            }
        }
    }
}

@Composable
private fun PosterGrid(count: Int, title: (Int) -> String, image: (Int) -> String?, onFocused: (Int) -> Unit, onClick: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(POSTER_W),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(List(count) { it }) { _, i -> PosterCard(title(i), image(i), onFocused = { onFocused(i) }, onClick = { onClick(i) }) }
    }
}

// ======================= Ao vivo =======================

@Composable
fun LiveScreen(lib: Library) {
    if (lib.liveTotal == 0) { Message("Nenhum canal ao vivo", "As listas adicionadas não têm canais ao vivo (ou ainda estão carregando)."); return }
    var group by rememberSaveable(lib.version) { mutableStateOf(defaultGroup(lib.liveGroups, lib.liveTotal)) }
    val channels = load(lib.version, group) { Repository.entries(ContentType.LIVE, group) }
    var focused by remember(group) { mutableStateOf<Entry?>(null) }
    Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
        GroupColumn(lib.liveGroups, lib.liveTotal, group) { group = it }
        Box(Modifier.weight(1f)) {
            if (channels == null) Loading() else ChannelList(channels, onFocused = { focused = it })
        }
        ChannelPreview(focused)
    }
}

/** Painel à direita com o canal em foco: logo grande, o que passa agora e as fontes. */
@Composable
private fun ChannelPreview(e: Entry?) {
    val epg by Repository.epg.collectAsState()
    val now = nowTicker()
    val sourceCount = load(e?.url) { e?.let { Repository.alternates(it).size + 1 } ?: 0 }
    Column(Modifier.width(300.dp).padding(end = 48.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (e == null) return@Column
        Logo(e.logo, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)), fallback = e.name)
        Text(e.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        e.quality?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.TextDim) }
        val cur = epg.current(e, now)
        val next = epg.next(e, now)
        if (cur != null) Text("Agora · ${cur.title}", fontSize = 14.sp, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (next != null) Text("${timeFmt.format(Date(next.start))} · ${next.title}", fontSize = 14.sp, color = Palette.TextFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (cur == null && next == null) Text(e.group, fontSize = 14.sp, color = Palette.TextDim, maxLines = 2)
        if ((sourceCount ?: 0) > 1) Text("$sourceCount fontes · troca sozinho se uma falhar", fontSize = 13.sp, color = Palette.TextFaint)
    }
}

@Composable
fun ChannelList(channels: List<Entry>, modifier: Modifier = Modifier, onFocused: ((Entry) -> Unit)? = null) {
    val context = LocalContext.current
    val epg by Repository.epg.collectAsState()
    val favorites by Repository.favorites.collectAsState()
    val now = nowTicker()
    if (channels.isEmpty()) { Message("Nada aqui"); return }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 24.dp, top = 4.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(channels) { i, e ->
            FocusBox(
                onClick = { PlaybackQueue.start(context, channels, i) },
                onLongClick = { toggleFav(context, e) },
                onFocused = onFocused?.let { cb -> { cb(e) } },
                modifier = Modifier.fillMaxWidth(),
                focusKey = "l:${e.url}",
                focusScale = 1.01f,
                background = Palette.Surface,
                shape = RoundedCornerShape(4.dp),
            ) { focused ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(44.dp), fontSize = 14.sp, color = if (focused) Palette.OnFocus else Palette.TextFaint)
                    Logo(e.logo, Modifier.size(width = 80.dp, height = 46.dp).clip(RoundedCornerShape(4.dp)), fallback = e.name)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                e.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = textOn(focused),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            )
                            e.quality?.let { q ->
                                Spacer(Modifier.width(8.dp))
                                Text(q, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (focused) Palette.OnFocus else Palette.TextFaint)
                            }
                            if (e.url in favorites) {
                                Spacer(Modifier.width(8.dp))
                                Text("Minha lista", fontSize = 11.sp, color = if (focused) Palette.OnFocus else Palette.TextFaint)
                            }
                        }
                        val cur = epg.current(e, now)
                        if (cur != null) {
                            Text(
                                "${timeFmt.format(Date(cur.start))} – ${timeFmt.format(Date(cur.stop))}   ${cur.title}",
                                fontSize = 13.sp, color = textOn(focused, dim = true),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            val frac = (now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1)
                            Box(Modifier.width(160.dp)) { ProgressLine(frac) }
                        } else {
                            Text(e.group, fontSize = 13.sp, color = textOn(focused, dim = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ======================= Série =======================

// ======================= Minha lista =======================

@Composable
fun MyListScreen(lib: Library, nav: Nav) {
    val context = LocalContext.current
    val header = remember { HeaderState() }
    val favorites by Repository.favorites.collectAsState()
    val items = load(lib.version, favorites) { Repository.favoriteEntries() }
    when {
        items == null -> Loading()
        items.isEmpty() -> Message(
            "Sua lista está vazia",
            "Segure o botão OK em um canal, filme ou episódio para adicionar.\nDurante o vídeo, use o menu de opções.",
        )
        else -> {
            val live = items.filter { it.type == ContentType.LIVE }
            val vod = items.filter { it.type != ContentType.LIVE }
            Column(Modifier.fillMaxSize()) {
            FocusHeader(header.title ?: "Minha lista", header.subtitle ?: "${items.size} itens", image = header.image, detail = header.detail, logo = header.isLogo)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)) {
                if (live.isNotEmpty()) item("live") {
                    SectionTitle("Canais", trailing = "${live.size}")
                    MediaRow(live, CHANNEL_W) { i, e ->
                        ChannelCard(e.name, e.logo, e.group, onFocused = { header.channel(e, "Canal · ${e.group}") }) { PlaybackQueue.start(context, live, i) }
                    }
                }
                if (vod.isNotEmpty()) item("vod") {
                    SectionTitle("Filmes e episódios", trailing = "${vod.size}")
                    MediaRow(vod, POSTER_W) { i, e ->
                        PosterCard(
                            e.name, e.logo, key = "my:${e.url}", progress = Repository.progress(e.url),
                            onFocused = { header.show(e.name, "${kindOf(e.type)} · ${e.group}", e.logo) },
                            onClick = { if (e.type == ContentType.MOVIE) nav.openMovie(e) else PlaybackQueue.start(context, vod, i) }, onLongClick = { toggleFav(context, e) },
                        )
                    }
                }
            }
            }
        }
    }
}

// ======================= Buscar =======================

@Composable
fun SearchScreen(lib: Library, nav: Nav) {
    val context = LocalContext.current
    val header = remember { HeaderState() }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    // aqui o resultado antigo some na hora (mostra "Carregando resultados…" enquanto busca)
    val results by produceState<Pair<List<Entry>, List<Show>>?>(null, lib.version, query) {
        value = null
        val q = query.trim()
        if (q.isEmpty()) { value = emptyList<Entry>() to emptyList(); searching = false; return@produceState }
        searching = true
        // primeiro o que começa com o texto (sai na hora), depois completa com o que contém
        val quick = Repository.search(q, quick = true)
        if (quick.first.isNotEmpty() || quick.second.isNotEmpty()) value = quick
        value = Repository.search(q)
        searching = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 48.dp, end = 48.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusBox(
                onClick = { showSearchDialog(context, query) { query = it; Repository.addRecentSearch(it) } },
                modifier = Modifier.width(520.dp), focusScale = 1.01f, shape = RoundedCornerShape(4.dp),
            ) { focused ->
                Text(
                    query.ifBlank { "Buscar canais, filmes e séries" },
                    Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    fontSize = 17.sp,
                    color = if (focused) Palette.OnFocus else if (query.isBlank()) Palette.TextFaint else Palette.Text,
                )
            }
            if (searching) { Spacer(Modifier.width(16.dp)); Spinner(22.dp); Spacer(Modifier.width(10.dp)); Text(if (results == null) "Buscando…" else "Procurando mais resultados…", fontSize = 14.sp, color = Palette.TextDim) }
        }
        if (query.isBlank()) {
            val recent = remember { Repository.recentSearches() }
            if (recent.isNotEmpty()) {
                SectionTitle("Buscas recentes")
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent) { r -> TvButton(r, onClick = { query = r; Repository.addRecentSearch(r) }) }
                }
            } else {
                Message("O que você quer assistir?", "Aperte OK no campo acima e digite parte do nome.")
            }
            return@Column
        }
        if (results == null) { Message("Carregando resultados para \"$query\"…"); return@Column }
        val (entries, shows) = results!!
        if (entries.isEmpty() && shows.isEmpty()) { Message("Nada encontrado para \"$query\""); return@Column }
        val live = entries.filter { it.type == ContentType.LIVE }
        // o mesmo filme/série de várias listas aparece uma vez só
        val movies = entries.filter { it.type == ContentType.MOVIE }.distinctBy { dupKey(it.name).ifEmpty { it.url } }
        FocusHeader(header.title ?: "Resultados", header.subtitle ?: "\"$query\"", image = header.image, detail = header.detail, logo = header.isLogo)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)) {
            if (live.isNotEmpty()) item("live") {
                SectionTitle("Canais", trailing = "${live.size}")
                MediaRow(live, CHANNEL_W) { i, e ->
                    ChannelCard(e.name, e.logo, e.group, onFocused = { header.channel(e, "Canal · ${e.group}") }) { PlaybackQueue.start(context, live, i) }
                }
            }
            if (movies.isNotEmpty()) item("movies") {
                SectionTitle("Filmes", trailing = "${movies.size}")
                MediaRow(movies, POSTER_W) { _, e ->
                    PosterCard(
                        e.name, e.logo, key = "s:${e.url}", onFocused = { header.show(e.name, "Filme · ${e.group}", e.logo) },
                        onClick = { nav.openMovie(e) }, onLongClick = { toggleFav(context, e) },
                    )
                }
            }
            if (shows.isNotEmpty()) item("shows") {
                val uniqueShows = shows.distinctBy { dupKey(it.name).ifEmpty { it.key } }
                SectionTitle("Séries", trailing = "${uniqueShows.size}")
                MediaRow(uniqueShows, POSTER_W) { _, s ->
                    PosterCard(s.name, s.logo, key = "s:${s.key}", tmdbTitle = s.name, tv = true, onFocused = { header.show(s.name, seriesLabel(s.episodeCount), s.logo) }, onClick = { nav.openShow(s) })
                }
            }
        }
    }
}

// ======================= Ajustes =======================

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val playlists by Repository.playlists.collectAsState()
    val sources by Repository.sources.collectAsState()
    val status by Repository.status.collectAsState()
    var autoplay by remember { mutableStateOf(Repository.autoplayLast) }
    val prefsFocus = remember { FocusRequester() }
    val address = rememberPhoneSetupServer()
    val dateFmt = remember { DateFormat.getDateFormat(context) }
    val clockFmt = remember { DateFormat.getTimeFormat(context) }
    fun fmt(t: Long) = if (t <= 0) "nunca" else "${dateFmt.format(Date(t))} ${clockFmt.format(Date(t))}"

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (address != null) {
                    QrCode(address, Modifier.size(140.dp))
                    Spacer(Modifier.width(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Adicionar listas", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Aponte a câmera do celular para o código (mesmo Wi-Fi da TV) e cole o link de uma playlist, " +
                            "de uma página ou de um repositório do GitHub." + (address?.let { "\nOu abra $it no navegador." } ?: ""),
                        fontSize = 14.sp, color = Palette.TextDim, lineHeight = 21.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // ▼ daqui vai para a primeira preferência (sem isso o foco pulava para os botões da direita lá embaixo)
                        val toPrefs = Modifier.focusProperties { down = prefsFocus }
                        TvButton("Digitar link", modifier = toPrefs, onClick = { showAddLinkDialog(context) { u, n, e -> addLink(context, u, n, e) } })
                        TvButton("Servidor (usuário e senha)", modifier = toPrefs, onClick = { showServerDialog(context) { sv, u, pw -> addServer(context, sv, u, pw) } })
                        TvButton("Atualizar agora", modifier = toPrefs, onClick = {
                            Toast.makeText(context, "Atualizando…", Toast.LENGTH_SHORT).show()
                            Repository.refreshAllAsync()
                        })
                    }
                }
            }
            status?.let { Text(it, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
        }

        item { SettingsHeader("Preferências") }
        item {
            var version by remember { mutableStateOf(Repository.preferredVersion) }
            Text("Filmes e séries: versão preferida", fontSize = 15.sp, color = Palette.TextDim, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
            Row(Modifier.padding(start = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Repository.DUB, Repository.LEG).forEach { v ->
                    TvButton(
                        Repository.versionName(v), selected = v == version,
                        modifier = if (v == Repository.DUB) Modifier.focusRequester(prefsFocus) else Modifier,
                        onClick = { version = v; Repository.preferredVersion = v },
                    )
                }
            }
        }
        item {
            ToggleRow("Abrir no último canal", "Ao abrir o app, já começa a tocar o último canal assistido", autoplay) {
                autoplay = !autoplay; Repository.autoplayLast = autoplay
            }
        }

        item {
            ListRow(title = "Versão ${dev.guisousa.minhatv.data.Updater.currentName}", subtitle = "O app confere sozinho se há versão nova ao abrir", error = false) {
                TvButton("Procurar atualização", onClick = { (context as? MainActivity)?.checkUpdate(force = true) })
            }
        }
        if (sources.isNotEmpty()) {
            item { SettingsHeader("Páginas e repositórios") }
            items(sources) { s ->
                ListRow(
                    title = s.url.removePrefix("https://").removePrefix("raw.githubusercontent.com/"),
                    subtitle = "${s.found} listas encontradas · verificado ${fmt(s.lastChecked)}" + (s.lastError?.let { " · erro: $it" } ?: ""),
                    error = s.lastError != null,
                ) {
                    TvButton("Remover", onClick = {
                        confirm(context, "Remover?", "Remove o link e todas as listas que vieram dele.", "Remover") { Repository.removeSource(s.id) }
                    })
                }
            }
        }
        item { SettingsHeader("Listas (${playlists.size})") }
        items(playlists, key = { it.id }) { p ->
            ListRow(
                title = p.name,
                subtitle = "${p.liveCount} canais · ${p.movieCount} filmes · ${p.seriesCount} ${if (p.isXtream) "séries" else "episódios"} · atualizada ${fmt(p.lastUpdated)}" +
                    (p.lastError?.let { " · erro: $it" } ?: ""),
                error = p.lastError != null,
            ) {
                TvButton("Atualizar", onClick = { Repository.refreshOneAsync(p.id) })
                Spacer(Modifier.width(8.dp))
                TvButton("Remover", onClick = { confirm(context, "Remover lista?", p.name, "Remover") { Repository.removePlaylist(p.id) } })
            }
        }
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Column {
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
        Spacer(Modifier.height(14.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.padding(start = 16.dp))
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ListRow(title: String, subtitle: String, error: Boolean, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = if (error) Palette.Error else Palette.TextFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        actions()
    }
}

@Suppress("unused")
private val keepColorImport = Color.Transparent
