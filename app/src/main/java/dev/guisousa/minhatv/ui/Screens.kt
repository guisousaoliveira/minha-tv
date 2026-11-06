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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.animation.core.tween
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
    /** Filme ("v2:f:id") ou série (Repository.showRef) em foco: o destaque busca fundo, gêneros e sinopse. */
    var ref by mutableStateOf<String?>(null)
    fun show(t: String, s: String?, img: String? = null, ref: String? = null) { title = t; subtitle = s; image = img; detail = null; isLogo = false; this.ref = ref }
    fun show(e: Entry, s: String?) = show(e.name, s, e.logo, e.url)
    fun show(sh: Show, s: String?) = show(sh.name, s, sh.logo, Repository.showRef(sh))

    /** Canal em foco: mostra a logo inteira e o que está passando agora/depois. */
    fun channel(e: Entry, s: String) {
        val now = System.currentTimeMillis()
        val epg = Repository.epg.value
        title = e.name; subtitle = s; image = e.logo; isLogo = true; ref = null
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
    CinemaLayout(header, lib.version, "O que vamos assistir?", "Bad Wolf TV") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            if (cont.isNotEmpty()) item("cont") {
                SectionTitle("Continuar assistindo")
                MediaRow(cont, POSTER_W) { i, e ->
                    PosterCard(
                        e.name, e.logo, key = "cont:${e.url}", progress = Repository.progress(e.url) ?: 0f,
                        tmdbTitle = e.seriesName ?: e.name, tv = e.type == ContentType.SERIES,
                        onFocused = { header.show(e, "Continuar · ${e.group}") },
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
                    PosterCard(e.name, e.logo, key = "fav:${e.url}", onFocused = { header.show(e, "${kindOf(e.type)} · ${e.group}") }, onClick = {
                        PlaybackQueue.start(context, favVod, i)
                    }, onLongClick = { toggleFav(context, e) })
                }
            }
            Repository.homeRows().forEachIndexed { ri, row ->
                item("home-$ri") {
                    SectionTitle(row.title, trailing = "${row.items.size}")
                    MediaRow(row.items, POSTER_SAIMO_W) { _, it ->
                        when (it) {
                            is dev.guisousa.minhatv.data.Entry -> PosterCard(
                                it.name, it.logo, key = "h$ri:${it.url}", progress = Repository.progress(it.url),
                                onFocused = { header.show(it, "Filme · ${it.group}") },
                                onClick = { nav.openMovie(it) }, onLongClick = { toggleFav(context, it) },
                            )
                            is dev.guisousa.minhatv.data.Show -> PosterCard(
                                it.name, it.logo, key = "h$ri:s${it.key}",
                                onFocused = { header.show(it, "Série · ${it.group}") },
                                onClick = { nav.openShow(it) },
                            )
                        }
                    }
                }
            }
            lib.liveGroups.take(2).forEach { g ->
                item("live-${g.name}") { EntryRow(lib, ContentType.LIVE, g.name, g.count, nav, header) }
            }
            lib.movieGroups.drop(1).take(5).forEach { g ->
                item("movie-${g.name}") { EntryRow(lib, ContentType.MOVIE, g.name, g.count, nav, header) }
            }
            lib.showGroups.drop(1).take(3).forEach { g ->
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
                onFocused = { header.show(e, "Filme · $group") },
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
        PosterCard(s.name, s.logo, key = "$group:${s.key}", tmdbTitle = s.name, tv = true, onFocused = { header.show(s, seriesLabel(s.episodeCount)) }, onClick = { nav.openShow(s) })
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
    CinemaLayout(header, lib.version, if (type == ContentType.SERIES) "Séries" else "Filmes", "${groups.size} categorias") {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // o atalho do topo some quando a lista rola (senão sobra só a beiradinha dele em cima de Lançamentos)
        val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
            // igual ao Saimo: Lançamentos, uma fileira por gênero e no fim "Todos de A a Z"
            // atalho logo no topo para a grade com tudo de A a Z
            item("all-top") {
                Row(Modifier.graphicsLayer { alpha = if (atTop) 1f else 0f }.padding(start = 48.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    onFocused = { i -> header.show(items[i], "Filme · $group") },
                ) { i -> nav.openMovie(items[i]) }
            }
            ContentType.SERIES -> {
                val shows = load(lib.version, group) { Repository.shows(group) }
                if (shows == null) Loading() else PosterGrid(
                    shows.size, { i -> shows[i].name }, { i -> shows[i].logo },
                    onFocused = { i -> header.show(shows[i], seriesLabel(shows[i].episodeCount)) },
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
    if (lib.liveTotal == 0) {
        Box(Modifier.padding(top = 64.dp)) { Message("Nenhum canal ao vivo", "As listas adicionadas não têm canais ao vivo (ou ainda estão carregando).") }
        return
    }
    var group by rememberSaveable(lib.version) { mutableStateOf(startLiveGroup(lib)) }
    val channels = load(lib.version, group) { Repository.entries(ContentType.LIVE, group) }
    var focused by remember(group) { mutableStateOf<Entry?>(null) }
    // brilho de fundo na cor da categoria (Globo azul, Record vermelho...), sumindo no preto
    val tint by androidx.compose.animation.animateColorAsState(groupColor(focused?.group ?: group), tween(500), label = "tint")
    Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.radialGradient(
                0f to tint, 1f to Palette.Background,
                center = androidx.compose.ui.geometry.Offset(1100f * 1.5f, 220f * 1.5f), radius = 1100f,
            ),
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(top = 72.dp)) {
            GroupColumn(lib.liveGroups, lib.liveTotal, group) { group = it; Repository.lastLiveGroup = it ?: "" }
            Box(Modifier.weight(1f)) {
                if (channels == null) Loading() else ChannelList(channels, groupName = group ?: "Todos", onFocused = { focused = it })
            }
            ChannelPreview(focused)
        }
    }
}

/** Categoria em que o Ao vivo abre: a última usada; na primeira vez, "Principais" (se houver). */
private fun startLiveGroup(lib: Library): String? {
    val saved = Repository.lastLiveGroup
    if (saved == "" && lib.liveTotal <= Library.ALL_LIMIT) return null
    if (saved != null && lib.liveGroups.any { it.name == saved }) return saved
    Repository.principaisGroup()?.let { p -> if (lib.liveGroups.any { it.name == p }) return p }
    return defaultGroup(lib.liveGroups, lib.liveTotal)
}

/** Cor de fundo de cada categoria de canais (escura: só um brilho atrás da tela). */
private fun groupColor(g: String?): Color {
    val n = (g ?: "").lowercase()
    return when {
        "globo" in n -> Color(0xFF0D3B66)
        "record" in n -> Color(0xFF5A1414)
        "sbt" in n -> Color(0xFF3D2A66)
        "band" in n -> Color(0xFF5A3A10)
        "regiona" in n || "aberto" in n -> Color(0xFF1D4A3A)
        "document" in n || "discovery" in n -> Color(0xFF14425A)
        "esporte" in n || "espn" in n || "sport" in n || "premiere" in n || "futebol" in n -> Color(0xFF5A1010)
        "filme" in n || "hbo" in n || "telecine" in n -> Color(0xFF2A2A2A)
        "infant" in n || "kids" in n -> Color(0xFF4A3A10)
        "not" in n -> Color(0xFF16324F)
        n.isBlank() -> Color(0xFF0A2A33)
        else -> Color.hsv(((n.hashCode() and 0x7fffffff) % 360).toFloat(), 0.55f, 0.32f)
    }
}

/** Painel à direita com o canal em foco: logo num quadro "ao vivo", o programa de agora e os próximos. */
@Composable
private fun ChannelPreview(e: Entry?) {
    val epg by Repository.epg.collectAsState()
    val now = nowTicker()
    Column(Modifier.width(424.dp).fillMaxHeight().padding(end = 56.dp, top = 4.dp)) {
        if (e == null) return@Column
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF1A1A1F), Color(0xFF0B0B0E)))),
        ) {
            Logo(e.logo, Modifier.align(Alignment.Center).size(width = 200.dp, height = 112.dp), fallback = e.name)
            // o canal tocando de verdade (sem tirar a logo até a imagem chegar)
            dev.guisousa.minhatv.player.LivePreviewVideo(e, active = LocalFocusEnabled.current, modifier = Modifier.matchParentSize())
            Row(
                Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Live).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Color.White))
                Spacer(Modifier.width(6.dp))
                Text("AO VIVO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 1.sp)
            }
            e.quality?.let { q ->
                Text(
                    q, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.TextDim,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        val progs = epg.forEntry(e).orEmpty()
        val cur = progs.firstOrNull { it.start <= now && now < it.stop }
        val upcoming = progs.filter { it.start > now }.take(3)
        Text(cur?.title ?: e.name, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        if (cur != null) {
            val left = ((cur.stop - now) / 60_000).coerceAtLeast(0)
            Text(
                "${e.name}  ·  ${timeFmt.format(Date(cur.start))}–${timeFmt.format(Date(cur.stop))}  ·  " + (if (left >= 60) "falta ${left / 60} h ${left % 60} min" else "falta $left min"),
                fontSize = 13.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            ProgressLine((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1))
            cur.desc?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFCFCFD4), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(e.group.ifBlank { "Sem guia de programação" }, fontSize = 13.sp, color = Palette.TextDim)
        }
        if (upcoming.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("A SEGUIR", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = Palette.Brand)
            Spacer(Modifier.height(6.dp))
            upcoming.forEach { p ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(timeFmt.format(Date(p.start)), Modifier.width(52.dp), fontSize = 14.sp, color = Palette.TextFaint)
                    Text(p.title, fontSize = 14.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun ChannelList(channels: List<Entry>, modifier: Modifier = Modifier, groupName: String? = null, onFocused: ((Entry) -> Unit)? = null) {
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
                // a fila do player é esta lista: ▲▼ no player andam nela
                onClick = { PlaybackQueue.start(context, channels, i, groupName) },
                onLongClick = { toggleFav(context, e) },
                onFocused = onFocused?.let { cb -> { cb(e) } },
                modifier = Modifier.fillMaxWidth(),
                focusKey = "l:${e.url}",
                focusScale = 1.03f,
                background = Color(0x0FFFFFFF),
                shape = RoundedCornerShape(8.dp),
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
        items == null -> Box(Modifier.padding(top = 64.dp)) { Loading() }
        items.isEmpty() -> Box(Modifier.padding(top = 64.dp)) { Message(
            "Sua lista está vazia",
            "Segure o botão OK em um canal, filme ou episódio para adicionar.\nDurante o vídeo, use o menu de opções.",
        ) }
        else -> {
            val live = items.filter { it.type == ContentType.LIVE }
            val vod = items.filter { it.type != ContentType.LIVE }
            CinemaLayout(header, lib.version, "Minha lista", "${items.size} itens") {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
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
                            onFocused = { header.show(e, "${kindOf(e.type)} · ${e.group}") },
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
    // busca enquanto você digita (espera uma pausa curta entre as letras)
    val results by produceState<Pair<List<Entry>, List<Show>>?>(null, lib.version, query) {
        val q = query.trim()
        if (q.isEmpty()) { value = emptyList<Entry>() to emptyList(); searching = false; return@produceState }
        searching = true
        delay(350)
        // primeiro o que começa com o texto (sai na hora), depois completa com o que contém
        value = Repository.search(q, quick = true)
        value = Repository.search(q)
        searching = false
    }
    val saveQuery = { if (query.isNotBlank()) Repository.addRecentSearch(query) }
    Row(Modifier.fillMaxSize()) {
        SearchKeyboard(query, searching, onQuery = { query = it }, onSystem = { showSearchDialog(context, query) { query = it; Repository.addRecentSearch(it) } })
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val r = results
            when {
                query.isBlank() -> Box(Modifier.padding(top = 64.dp)) {
                    Message("O que você quer assistir?", "Digite com o teclado ao lado: os resultados aparecem enquanto você digita.\nCanais, filmes e séries.")
                }
                r == null -> Box(Modifier.padding(top = 64.dp)) { Loading() }
                r.first.isEmpty() && r.second.isEmpty() -> Box(Modifier.padding(top = 64.dp)) {
                    if (searching) Loading()
                    else Message("Nada encontrado para \"$query\"", "Tente só uma parte do nome (ex.: \"vingad\" em vez do título inteiro).")
                }
                else -> {
                    val (entries, shows) = r
                    val live = entries.filter { it.type == ContentType.LIVE }
                    // o mesmo filme/série de várias listas aparece uma vez só (com o ano: Aladdin 1992 e 2019 são dois filmes)
                    val movies = entries.filter { it.type == ContentType.MOVIE }.distinctBy { dupKey(it.name).ifEmpty { it.url } + "|" + it.year }
                    val uniqueShows = shows.distinctBy { dupKey(it.name).ifEmpty { it.key } }
                    CinemaLayout(header, lib.version, "Resultados", "\"$query\"", infoStart = 40.dp) {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                            if (movies.isNotEmpty()) item("movies") {
                                SectionTitle("Filmes", trailing = "${movies.size}")
                                MediaRow(movies, POSTER_W) { _, e ->
                                    PosterCard(
                                        e.name, e.logo, key = "s:${e.url}", onFocused = { header.show(e, "Filme · ${e.group}") },
                                        onClick = { saveQuery(); nav.openMovie(e) }, onLongClick = { toggleFav(context, e) },
                                    )
                                }
                            }
                            if (uniqueShows.isNotEmpty()) item("shows") {
                                SectionTitle("Séries", trailing = "${uniqueShows.size}")
                                MediaRow(uniqueShows, POSTER_W) { _, s ->
                                    PosterCard(s.name, s.logo, key = "s:${s.key}", tmdbTitle = s.name, tv = true, onFocused = { header.show(s, seriesLabel(s.episodeCount)) }, onClick = { saveQuery(); nav.openShow(s) })
                                }
                            }
                            if (live.isNotEmpty()) item("live") {
                                SectionTitle("Canais", trailing = "${live.size}")
                                MediaRow(live, CHANNEL_W) { i, e ->
                                    ChannelCard(e.name, e.logo, e.group, onFocused = { header.channel(e, "Canal · ${e.group}") }) { saveQuery(); PlaybackQueue.start(context, live, i) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val KEYS = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890")

/** Teclado na tela (estilo YouTube): letras e números, espaço, apagar e as buscas recentes embaixo. */
@Composable
private fun SearchKeyboard(query: String, searching: Boolean, onQuery: (String) -> Unit, onSystem: () -> Unit) {
    val recent = remember(query.isBlank()) { Repository.recentSearches() }
    Column(
        Modifier.width(352.dp).fillMaxHeight().background(Color(0xFF0B0B0E)).padding(start = 40.dp, end = 24.dp, top = 84.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, null, tint = Palette.TextDim, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                query.ifEmpty { "Buscar" }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = if (query.isEmpty()) Palette.TextFaint else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (searching) Spinner(18.dp)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(Palette.Brand))
        Spacer(Modifier.height(16.dp))
        KEYS.forEach { row ->
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c -> Key(c.toString(), Modifier.size(width = 42.dp, height = 40.dp)) { onQuery(query + c) } }
            }
        }
        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key("espaço", Modifier.size(width = 90.dp, height = 40.dp), small = true) { if (query.isNotEmpty() && !query.endsWith(' ')) onQuery("$query ") }
            Key("apagar", Modifier.size(width = 90.dp, height = 40.dp), small = true) { onQuery(query.dropLast(1)) }
            Key("limpar", Modifier.size(width = 90.dp, height = 40.dp), small = true) { onQuery("") }
        }
        Spacer(Modifier.height(4.dp))
        Key("Teclado do sistema / voz", Modifier.size(width = 282.dp, height = 40.dp), small = true) { onSystem() }
        if (recent.isNotEmpty() && query.isBlank()) {
            Spacer(Modifier.height(18.dp))
            Text("BUSCAS RECENTES", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = Palette.Brand)
            Spacer(Modifier.height(6.dp))
            recent.take(4).forEach { r ->
                Key(r, Modifier.padding(bottom = 4.dp).size(width = 282.dp, height = 34.dp), small = true, start = true) { onQuery(r) }
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, small: Boolean = false, start: Boolean = false, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier, focusScale = 1.06f, background = Color(0x14FFFFFF), shape = RoundedCornerShape(6.dp)) { focused ->
        Text(
            label, fontSize = if (small) 14.sp else 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) Palette.OnFocus else Palette.Text,
            modifier = Modifier.align(if (start) Alignment.CenterStart else Alignment.Center).padding(horizontal = 12.dp),
        )
    }
}

// ======================= Ajustes =======================

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val status by Repository.status.collectAsState()
    val lib by Repository.library.collectAsState()
    var autoplay by remember { mutableStateOf(Repository.autoplayLast) }
    var arrowsZap by remember { mutableStateOf(Repository.arrowsZap) }
    val prefsFocus = remember { FocusRequester() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Column {
                Text("Catálogo", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${lib.liveTotal} canais · ${lib.movieTotal} filmes · ${lib.showTotal} séries\n" +
                        "Os canais, filmes e séries chegam prontos do servidor e se atualizam sozinhos.",
                    fontSize = 14.sp, color = Palette.TextDim, lineHeight = 21.sp,
                )
                Spacer(Modifier.height(12.dp))
                TvButton("Atualizar agora", modifier = Modifier.focusProperties { down = prefsFocus }, onClick = {
                    Toast.makeText(context, "Atualizando…", Toast.LENGTH_SHORT).show()
                    Repository.refreshAllAsync()
                })
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
            ToggleRow("Setas ▲▼ trocam de canal", "No canal ao vivo, ▲ volta um canal e ▼ passa para o próximo", arrowsZap) {
                arrowsZap = !arrowsZap; Repository.arrowsZap = arrowsZap
            }
        }

        item {
            ListRow(title = "Versão ${dev.guisousa.minhatv.data.Updater.currentName}", subtitle = "O app confere sozinho se há versão nova ao abrir", error = false) {
                TvButton("Procurar atualização", onClick = { (context as? MainActivity)?.checkUpdate(force = true) })
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
