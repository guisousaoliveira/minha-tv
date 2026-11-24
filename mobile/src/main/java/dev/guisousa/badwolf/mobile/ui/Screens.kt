package dev.guisousa.badwolf.mobile.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.badwolf.mobile.R
import dev.guisousa.badwolf.mobile.data.Conta
import dev.guisousa.badwolf.mobile.data.ContentType
import dev.guisousa.badwolf.mobile.data.Entry
import dev.guisousa.badwolf.mobile.data.LocalStore
import dev.guisousa.badwolf.mobile.data.Nuvem
import dev.guisousa.badwolf.mobile.data.Repository
import dev.guisousa.badwolf.mobile.data.Show
import dev.guisousa.badwolf.mobile.data.Updater
import dev.guisousa.badwolf.mobile.player.PlaybackQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Aviso do catálogo (baixando, sem internet). */
@Composable
private fun StatusBanner() {
    val status by Repository.status.collectAsState()
    status?.let {
        Text(
            it,
            fontSize = 13.sp,
            color = Palette.Text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(CardShape)
                .background(Palette.SurfaceHigh)
                .border(1.dp, Palette.Hairline, CardShape)
                .padding(12.dp),
        )
    }
}

@Composable
private fun EmptyCatalog() {
    val loading by Repository.isLoading.collectAsState()
    val status by Repository.status.collectAsState()
    if (loading || status != null && status!!.startsWith("Baixando")) {
        Loading()
    } else {
        Message("Nada por aqui ainda.", detail = "Confira a internet e toque em Ajustes > Atualizar o catálogo.")
    }
}

// ======================= Início =======================

@Composable
fun HomeScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lib by Repository.library.collectAsState()
    val favs by Repository.favorites.collectAsState()
    val recents by LocalStore.recentsVersion.collectAsState()
    val watched by Repository.watched.collectAsState()
    val rows = remember(lib.version) { Repository.homeRows() }
    val cont = remember(recents, lib.version) { Repository.continueWatching() }
    val myList = load(favs, lib.version) { Repository.favoriteItems() }
    val recentLive = remember(recents, lib.version) { Repository.recentLive().take(15) }
    // destaque no topo: os primeiros "Em alta" que têm imagem de fundo
    val destaques = remember(lib.version) {
        rows.flatMap { it.items }.distinctBy { if (it is Entry) it.url else (it as Show).key }
            .mapNotNull { item -> Repository.destaque(refOf(item))?.takeIf { it.fundo != null }?.let { item to it } }.take(6)
    }

    if (rows.isEmpty() && cont.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Bad Wolf", onSearch = { nav.openSearch() }, onAccount = { nav.openAuth() }, showBrandBadge = true)
            StatusBanner()
            EmptyCatalog()
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item("hero") {
            if (destaques.isNotEmpty()) {
                HeroPager(destaques, nav, favs)
            } else {
                TopBar("Bad Wolf", onSearch = { nav.openSearch() }, onAccount = { nav.openAuth() }, showBrandBadge = true)
            }
            StatusBanner()
        }
        if (cont.isNotEmpty()) item("cont") {
            SectionTitle("Continuar assistindo")
            MediaRow(cont, 232.dp) { _, e ->
                WideCard(e, Repository.progress(e.url)) {
                    if (e.type == ContentType.MOVIE) PlaybackQueue.start(context, listOf(e), 0)
                    else scope.launch { playEpisode(context, e) }
                }
            }
        }
        if (recentLive.isNotEmpty()) item("live") {
            SectionTitle("Canais recentes")
            MediaRow(recentLive, 134.dp) { i, e -> ChannelTile(e) { PlaybackQueue.start(context, recentLive, i) } }
        }
        if (!myList.isNullOrEmpty()) item("fav") {
            SectionTitle("Minha lista")
            MediaRow(myList, 124.dp) { _, it -> AnyPoster(it, nav, watched) }
        }
        rows.forEachIndexed { i, r ->
            item("row$i") {
                SectionTitle(r.title)
                MediaRow(r.items, 124.dp) { _, it -> AnyPoster(it, nav, watched) }
            }
        }
    }
}

private fun refOf(item: Any): String? = when (item) {
    is Entry -> item.url
    is Show -> Repository.showRef(item)
    else -> null
}

/** Destaque do Início: espelho nativo do Hero mobile do W4LLE TV com paleta Obsidian Cinema. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroPager(itens: List<Pair<Any, Repository.Destaque>>, nav: Nav, favs: Set<String>) {
    val context = LocalContext.current
    val pager = rememberPagerState { itens.size }
    LaunchedEffect(itens.size) {
        while (itens.size > 1) {
            delay(6_000)
            pager.animateScrollToPage((pager.currentPage + 1) % itens.size)
        }
    }
    Box(Modifier.fillMaxWidth().height(520.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val (item, d) = itens[page]
            val nome = if (item is Entry) item.name else (item as Show).name
            Box(Modifier.fillMaxSize().clickable { nav.open(item) }) {
                Backdrop(d.fundo, Modifier.fillMaxSize())
                // Scrim vertical multi-paradas suave fundindo direto no #06070A (sem corte seco)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color(0x8806070A),
                            0.22f to Color.Transparent,
                            0.46f to Color(0x4D06070A),
                            0.74f to Color(0xCC06070A),
                            0.92f to Color(0xF506070A),
                            1f to Palette.Background,
                        ),
                    ),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Kicker badge + selo 4K
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .clip(PillShape)
                                .background(Palette.BrandSoft)
                                .border(1.dp, Palette.Brand.copy(alpha = 0.35f), PillShape)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "EM ALTA · #${page + 1} · ${if (d.serie) "SÉRIE" else "FILME"}",
                                fontSize = 10.5.sp,
                                color = Palette.Brand,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.4.sp,
                            )
                        }
                        if (d.k4) {
                            Box(
                                Modifier
                                    .clip(PillShape)
                                    .background(Palette.SurfaceGlass)
                                    .border(1.dp, Palette.Hairline, PillShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text("4K", fontSize = 10.5.sp, color = Palette.Text, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        nome,
                        fontSize = 30.sp,
                        lineHeight = 33.sp,
                        letterSpacing = (-0.5).sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Palette.Text,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(8.dp))

                    val metaParts = listOfNotNull(
                        d.ano?.toString(),
                        d.generos.take(2).joinToString(" · ").ifBlank { null },
                        if (d.serie && d.temporadas > 0) "${d.temporadas} temporada${if (d.temporadas > 1) "s" else ""}" else null,
                    )
                    if (metaParts.isNotEmpty()) {
                        Text(
                            metaParts.joinToString("  ·  "),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Palette.TextDim,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillButton("Assistir", Icons.Filled.PlayArrow, primary = true) {
                            if (item is Entry) PlaybackQueue.start(context, listOf(item), 0) else nav.open(item)
                        }
                        val naLista = when (item) {
                            is Entry -> item.url in favs
                            is Show -> Repository.isShowFavorite(item, favs)
                            else -> false
                        }
                        PillButton(if (naLista) "Na lista" else "Minha lista", if (naLista) Icons.Filled.Check else Icons.Filled.Add) {
                            when (item) {
                                is Entry -> toggleFav(context, item)
                                is Show -> toggleFav(context, item)
                            }
                        }
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Palette.SurfaceGlass)
                                .border(1.dp, Palette.Hairline, CircleShape)
                                .clickable { nav.open(item) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Info, contentDescription = "Detalhes", tint = Palette.Text, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // TopBar flutuante sobre o hero: logo, marca, busca e pílula de conta
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 18.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Palette.SurfaceGlass)
                    .border(1.dp, Palette.Hairline, CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.logo_lobo), null, Modifier.fillMaxSize())
            }
            Text(
                "BAD WOLF",
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                color = Palette.Text,
                letterSpacing = 1.6.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Palette.SurfaceGlass)
                    .border(1.dp, Palette.Hairline, CircleShape)
                    .clickable { nav.openSearch() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Search, "Buscar", tint = Palette.Text, modifier = Modifier.size(20.dp))
            }
            AccountPill(onClick = { nav.openAuth() })
        }

        // Indicadores de página do destaque
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(itens.size) { i ->
                Box(
                    Modifier
                        .size(if (i == pager.currentPage) 20.dp else 6.dp, 5.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) Palette.Brand else Color(0x4DFFFFFF)),
                )
            }
        }
    }
}

/** Botão em pílula (destaque e detalhes): primário em Ciano Elétrico ou secundário vidro obsidiana. */
@Composable
fun PillButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(PillShape)
            .background(if (primary) Palette.Brand else Palette.SurfaceGlass)
            .then(if (!primary) Modifier.border(1.dp, Palette.Hairline, PillShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = if (primary) 22.dp else 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (primary) Palette.BrandText else Palette.Text, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (primary) Palette.BrandText else Palette.Text,
            maxLines = 1,
        )
    }
}

/** Abre um episódio com a série inteira na fila (para o "próximo episódio" funcionar). */
suspend fun playEpisode(context: android.content.Context, e: Entry) {
    val show = Repository.showOf(e)
    val all = show?.let { runCatching { Repository.episodes(it) }.getOrNull()?.list }.orEmpty()
    val queue = seriesQueue(all)
    val i = queue.indexOfFirst { it.url == e.url }
    if (i >= 0) PlaybackQueue.start(context, queue, i) else PlaybackQueue.start(context, listOf(e), 0)
}

/** Capa de filme ([Entry]) ou série ([Show]). */
@Composable
fun AnyPoster(item: Any, nav: Nav, watched: Set<String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    when (item) {
        is Entry -> {
            if (item.type == ContentType.LIVE) {
                ChannelTile(item, modifier) { PlaybackQueue.start(context, listOf(item), 0) }
            } else {
                PosterCard(
                    item.name,
                    item.logo,
                    modifier,
                    progress = Repository.progress(item.url),
                    seen = item.url.hashCode().toString() in watched,
                    onLongClick = { toggleFav(context, item) },
                ) {
                    if (item.type == ContentType.MOVIE) nav.openMovie(item)
                    else Repository.showOf(item)?.let(nav::openShow)
                }
            }
        }
        is Show -> PosterCard(item.name, item.logo, modifier, onLongClick = { toggleFav(context, item) }) { nav.openShow(item) }
    }
}

/** Cartão largo do "Continuar assistindo": imagem, botão de play, barra ciano e título. */
@Composable
private fun WideCard(e: Entry, progress: Float?, onClick: () -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(CardShape)
                .border(1.dp, Palette.Hairline, CardShape)
                .clickable(onClick = onClick),
        ) {
            Logo(e.logo, Modifier.fillMaxSize(), fallback = e.name, crop = true)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xD906070A))))
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Palette.SurfaceGlass)
                    .border(1.dp, Palette.Brand.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Palette.Brand, modifier = Modifier.size(26.dp))
            }
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
        }
        val sub = if (e.season != null && e.episode != null) "T${e.season} · E${e.episode}" else null
        Text(
            e.seriesName ?: e.name,
            fontSize = 13.sp,
            color = Palette.Text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (sub != null) Text(sub, fontSize = 12.sp, color = Palette.TextFaint, maxLines = 1)
    }
}

/** Canal em miniatura com spotlight radial (espelhando os cards de TV do W4LLE). */
@Composable
private fun ChannelTile(e: Entry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(CardShape)
                .background(Palette.Surface)
                .border(1.dp, Palette.Hairline, CardShape)
                .clickable(onClick = onClick),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x16FFFFFF), Color(0x66000000)),
                            center = Offset(160f, 60f),
                            radius = 240f,
                        ),
                    ),
            )
            Logo(e.logo, Modifier.fillMaxSize().padding(10.dp), fallback = e.name)
            LiveDot(Modifier.align(Alignment.TopEnd).padding(8.dp), pulse = true)
        }
        Text(
            e.name,
            fontSize = 12.sp,
            color = Palette.TextDim,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ======================= Filmes / Séries =======================

private const val ALL = "Todos"

@Composable
fun MoviesScreen(nav: Nav) {
    val lib by Repository.library.collectAsState()
    val watched by Repository.watched.collectAsState()
    var group by rememberSaveable { mutableStateOf(ALL) }
    val options = remember(lib.version) { listOf(ALL) + lib.movieGroups.map { it.name.removePrefix("Filmes · ") } }
    val real = remember(lib.version, group) { lib.movieGroups.firstOrNull { it.name.removePrefix("Filmes · ") == group }?.name }
    val items = load(group, lib.version) { Repository.moviesPage(real, null) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Filmes", onSearch = { nav.openSearch() }, onAccount = { nav.openAuth() })
        StatusBanner()
        if (lib.movieGroups.isEmpty()) { EmptyCatalog(); return@Column }
        Chips(options, group) { group = it }
        Spacer(Modifier.height(10.dp))
        when {
            items == null -> Loading()
            items.isEmpty() -> Message("Nenhum filme nesta categoria.")
            else -> PosterGrid(items, nav, watched)
        }
    }
}

@Composable
fun ShowsScreen(nav: Nav) {
    val lib by Repository.library.collectAsState()
    var group by rememberSaveable { mutableStateOf(ALL) }
    val options = remember(lib.version) { listOf(ALL) + lib.showGroups.map { it.name.removePrefix("Séries · ") } }
    val real = remember(lib.version, group) { lib.showGroups.firstOrNull { it.name.removePrefix("Séries · ") == group }?.name }
    val items = load(group, lib.version) { Repository.showsPage(real, null) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Séries", onSearch = { nav.openSearch() }, onAccount = { nav.openAuth() })
        StatusBanner()
        if (lib.showGroups.isEmpty()) { EmptyCatalog(); return@Column }
        Chips(options, group) { group = it }
        Spacer(Modifier.height(10.dp))
        when {
            items == null -> Loading()
            items.isEmpty() -> Message("Nenhuma série nesta categoria.")
            else -> PosterGrid(items, nav, emptySet())
        }
    }
}

@Composable
private fun PosterGrid(items: List<Any>, nav: Nav, watched: Set<String>) {
    LazyVerticalGrid(
        GridCells.Adaptive(112.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(items, key = { i, it -> (if (it is Entry) it.url else (it as Show).key) + "#" + i }) { _, it ->
            AnyPoster(it, nav, watched)
        }
    }
}

// ======================= Ao vivo =======================

private const val RECENT = "Recentes"
private const val FAVS = "Minha lista"

@Composable
fun LiveScreen(nav: Nav) {
    val context = LocalContext.current
    val lib by Repository.library.collectAsState()
    val favs by Repository.favorites.collectAsState()
    val recents by LocalStore.recentsVersion.collectAsState()
    val epg by Repository.epg.collectAsState()
    val recentLive = remember(recents, lib.version) { Repository.recentLive() }
    val favLive = load(favs, lib.version) { Repository.favoriteEntries().filter { it.type == ContentType.LIVE } }.orEmpty()
    val options = remember(lib.version, recentLive.isNotEmpty(), favLive.isNotEmpty()) {
        listOfNotNull(RECENT.takeIf { recentLive.isNotEmpty() }, FAVS.takeIf { favLive.isNotEmpty() }) + lib.liveGroups.map { it.name }
    }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    val current = group?.takeIf { it in options } ?: options.firstOrNull()
    val channels = load(current, lib.version, recents, favLive) {
        when (current) {
            null -> emptyList()
            RECENT -> recentLive
            FAVS -> favLive
            else -> Repository.entries(ContentType.LIVE, current)
        }
    }
    // o "agora passando" e o relógio mudam com o tempo
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }

    Column(Modifier.fillMaxSize()) {
        TopBar("Ao vivo", onSearch = { nav.openSearch() }, onAccount = { nav.openAuth() })
        // Faixa de status Ao vivo + relógio (espelho do cabeçalho do W4LLE TvSectionBeta)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier
                    .clip(PillShape)
                    .background(Palette.Live.copy(alpha = 0.90f))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                Text("AO VIVO", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, color = Color.White)
            }
            Text(
                timeFmt.format(Date(now)),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.TextDim,
            )
            if (channels != null && channels.isNotEmpty()) {
                Text("·  ${channels.size} canais", fontSize = 12.sp, color = Palette.TextFaint)
            }
        }
        Spacer(Modifier.height(6.dp))
        StatusBanner()
        if (lib.liveGroups.isEmpty()) { EmptyCatalog(); return@Column }
        Chips(options, current) { group = it }
        Spacer(Modifier.height(10.dp))
        when {
            channels == null -> Loading()
            channels.isEmpty() -> Message("Nenhum canal aqui.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(channels, key = { i, e -> e.url + "#" + i }) { i, e ->
                    ChannelRow(e, i + 1, epg.current(e, now), epg.next(e, now), e.url in favs, onLongClick = { toggleFav(context, e) }) {
                        if (current == RECENT || current == FAVS) PlaybackQueue.start(context, channels, i)
                        else PlaybackQueue.startLoadingGroup(context, channels, i) { Repository.entries(ContentType.LIVE, current) }
                    }
                }
            }
        }
    }
}

/** Canal em cartão Obsidian Cinema espelhando os cards de TV do W4LLE: spotlight no logo, selo AO VIVO, barra ciano e próximo programa. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    e: Entry,
    number: Int,
    now: dev.guisousa.badwolf.mobile.data.Programme?,
    next: dev.guisousa.badwolf.mobile.data.Programme?,
    fav: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Palette.Surface)
            .border(1.dp, Palette.Hairline, CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Container do logo com spotlight radial sutil (W4LLE TV)
        Box(
            Modifier
                .size(88.dp, 58.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x1CFFFFFF), Color(0x88000000)),
                        center = Offset(120f, 35f),
                        radius = 180f,
                    ),
                )
                .border(1.dp, Palette.Hairline, RoundedCornerShape(10.dp)),
        ) {
            Logo(e.logo, Modifier.fillMaxSize().padding(6.dp), fallback = e.name)
            if (now != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(PillShape)
                        .background(Palette.Live)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text("AO VIVO", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 0.5.sp)
                }
            }
        }

        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$number", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextFaint, modifier = Modifier.padding(end = 6.dp))
                Text(
                    e.name,
                    fontSize = 15.sp,
                    color = Palette.Text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (fav) Text("  ★", fontSize = 12.sp, color = Palette.Brand)
            }
            if (now != null) {
                Row(
                    Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LiveDot(Modifier.size(6.dp), pulse = true)
                    Text(
                        now.title,
                        fontSize = 13.sp,
                        color = Palette.TextDim,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val span = (now.stop - now.start).coerceAtLeast(1)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(116.dp).clip(PillShape)) {
                        ProgressLine((System.currentTimeMillis() - now.start).toFloat() / span)
                    }
                    Text("  até ${timeFmt.format(Date(now.stop))}", fontSize = 11.sp, color = Palette.TextFaint)
                }
                if (next != null) {
                    Text(
                        "A seguir: ${timeFmt.format(Date(next.start))} · ${next.title}",
                        fontSize = 11.sp,
                        color = Palette.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            } else if (!e.quality.isNullOrBlank()) {
                Text(e.quality, fontSize = 12.sp, color = Palette.TextFaint, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Palette.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, null, tint = Palette.TextDim, modifier = Modifier.size(18.dp))
        }
    }
}

// ======================= Busca =======================

@Composable
fun SearchScreen(nav: Nav) {
    val context = LocalContext.current
    val lib by Repository.library.collectAsState()
    val watched by Repository.watched.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var recentVersion by remember { mutableStateOf(0) }
    val recentSearches = remember(recentVersion) { Repository.recentSearches() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val result = load(query, lib.version) {
        if (query.trim().length < 2) null else { delay(250); Repository.search(query) }
    }
    val save = { if (query.isNotBlank()) { Repository.addRecentSearch(query); recentVersion++ } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { keyboard?.hide(); nav.back() }) {
                Icon(Icons.Filled.ArrowBack, "Voltar", tint = Palette.Text)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("Filmes, séries e canais", color = Palette.TextFaint) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Palette.TextFaint) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Limpar", tint = Palette.TextDim) }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { save(); keyboard?.hide() }),
                shape = PillShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Palette.Surface,
                    unfocusedContainerColor = Palette.Surface,
                    focusedBorderColor = Palette.Brand,
                    unfocusedBorderColor = Palette.Hairline,
                    cursorColor = Palette.Brand,
                    focusedTextColor = Palette.Text,
                    unfocusedTextColor = Palette.Text,
                ),
            )
        }
        if (query.trim().length < 2) {
            if (recentSearches.isEmpty()) {
                Message("O que você quer assistir?", detail = "Digite pelo menos 2 letras do filme, série ou canal.")
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Buscas recentes", fontSize = 16.sp, color = Palette.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { LocalStore.clearRecentSearches(); recentVersion++ }) { Text("Limpar", color = Palette.Brand) }
            }
            LazyColumn {
                items(recentSearches) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { query = s }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, null, tint = Palette.TextFaint, modifier = Modifier.size(18.dp))
                        Text(s, fontSize = 15.sp, color = Palette.Text, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            return@Column
        }
        if (result == null) { Loading(); return@Column }
        val (entries, shows) = result
        val live = entries.filter { it.type == ContentType.LIVE }
        val posters: List<Any> = entries.filter { it.type != ContentType.LIVE } + shows
        if (live.isEmpty() && posters.isEmpty()) {
            Message("Nada encontrado", detail = "Nenhum resultado para \"$query\".")
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Adaptive(112.dp),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (live.isNotEmpty()) {
                item("lh", span = { GridItemSpan(maxLineSpan) }) {
                    Text("Canais", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(top = 8.dp))
                }
                itemsIndexed(live.take(30), key = { i, e -> "l" + e.url + i }) { i, e ->
                    ChannelTile(e) { save(); PlaybackQueue.start(context, live, i) }
                }
            }
            if (posters.isNotEmpty()) {
                item("ph", span = { GridItemSpan(maxLineSpan) }) {
                    Text("Filmes e séries", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(top = 12.dp))
                }
                itemsIndexed(posters.take(300), key = { i, it -> "p" + (if (it is Entry) it.url else (it as Show).key) + i }) { _, it ->
                    AnyPoster(it, nav, watched)
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
}

// ======================= Ajustes =======================

@Composable
fun SettingsScreen(update: UpdateState, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lib by Repository.library.collectAsState()
    var pref by remember { mutableStateOf(Repository.preferredVersion) }
    var auto by remember { mutableStateOf(Repository.autoUpdate) }
    var formato by remember { mutableStateOf(Repository.resizeMode) }
    var conferindo by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val formatos = listOf(0 to "Original", 4 to "Preencher", 3 to "Esticar")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { TopBar("Ajustes", onSearch = null, onAccount = { nav.openAuth() }) }
        item { ContaCard(nav) }
        item {
            SettingsCard("Reprodução") {
                SettingsRow(
                    "Versão preferida",
                    "Para títulos que você ainda não escolheu. Em cada filme ou série dá para trocar no botão Versão.",
                    value = Repository.versionName(pref),
                ) {
                    pref = if (pref == Repository.DUB) Repository.LEG else Repository.DUB
                    Repository.preferredVersion = pref
                }
                SettingsDivider()
                SettingsRow(
                    "Formato da imagem",
                    "Como o vídeo ocupa a tela no player",
                    value = formatos.firstOrNull { it.first == formato }?.second ?: "Original",
                ) {
                    val i = formatos.indexOfFirst { it.first == formato }
                    formato = formatos[(i + 1) % formatos.size].first
                    Repository.resizeMode = formato
                }
            }
        }
        item {
            SettingsCard("Versão e atualização") {
                SettingsRow(
                    "Atualização automática",
                    "Ao abrir, confere se saiu versão nova e oferece para instalar",
                    toggle = auto,
                ) {
                    auto = !auto; Repository.autoUpdate = auto
                }
                SettingsDivider()
                SettingsRow(
                    "Procurar atualização agora",
                    "Versão instalada: ${Updater.currentName}",
                    icon = Icons.Filled.Refresh,
                    value = if (conferindo) "Conferindo…" else "Atualizado",
                ) {
                    if (conferindo) return@SettingsRow
                    conferindo = true
                    scope.launch {
                        val r = Updater.check()
                        conferindo = false
                        if (r != null) update.release = r
                        else Toast.makeText(context, "Você já está na versão mais nova (${Updater.currentName}).", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        item {
            SettingsCard("Catálogo") {
                SettingsRow(
                    "Atualizar o catálogo",
                    "${lib.liveTotal} canais · ${lib.movieTotal} filmes · ${lib.showTotal} séries",
                    icon = Icons.Filled.Refresh,
                ) {
                    Repository.refreshAllAsync()
                    Toast.makeText(context, "Conferindo o catálogo…", Toast.LENGTH_SHORT).show()
                }
            }
        }
        item {
            SettingsCard("Histórico") {
                SettingsRow("Limpar buscas recentes", icon = Icons.Filled.Clear) {
                    LocalStore.clearRecentSearches()
                    Toast.makeText(context, "Buscas apagadas", Toast.LENGTH_SHORT).show()
                }
                SettingsDivider()
                SettingsRow(
                    "Apagar o que já vi",
                    "Continuar assistindo, recentes e marcas de visto (a Minha lista fica)",
                    icon = Icons.Filled.Delete,
                    danger = true,
                ) { confirmClear = true }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Palette.Surface)
                        .border(1.dp, Palette.Hairline, CircleShape)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(R.drawable.logo_lobo), null, Modifier.fillMaxSize())
                }
                Text("Bad Wolf TV", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(top = 10.dp))
                Text("Versão ${Updater.currentName} · Obsidian Cinema", fontSize = 12.sp, color = Palette.TextFaint, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Apagar o histórico?", color = Palette.Text) },
            text = { Text("Some o Continuar assistindo, os canais recentes e as marcas de visto. A Minha lista fica.", color = Palette.TextDim) },
            confirmButton = {
                TextButton(onClick = { LocalStore.clearHistory(); confirmClear = false }) {
                    Text("Apagar", color = Palette.Error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar", color = Palette.TextDim) } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

/** Cartão dedicado de status da conta em Ajustes (abre AuthScreen para entrar/criar conta). */
@Composable
private fun ContaCard(nav: Nav) {
    val context = LocalContext.current
    val usuario by Conta.usuario.collectAsState()
    val ultima by Nuvem.ultima.collectAsState()
    val sincronizando by Nuvem.sincronizando.collectAsState()
    var confirmarSair by remember { mutableStateOf(false) }
    val u = usuario

    SettingsCard("Conta Bad Wolf / W4LLE") {
        if (u == null) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Palette.BrandSoft)
                            .border(1.dp, Palette.Brand.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = Palette.Brand, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Usando sem conta", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                        Text(
                            "Entre ou crie uma conta grátis para sincronizar Minha lista e histórico entre TV, celular, PC e web.",
                            fontSize = 12.sp,
                            color = Palette.TextDim,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { nav.openAuth() },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = InputShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.Brand,
                        contentColor = Palette.BrandText,
                    ),
                ) {
                    Text("Entrar ou criar conta", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val quando = when {
                sincronizando -> "Sincronizando agora…"
                ultima == 0L -> "Sincronização ativa"
                else -> "Sincronizado às " + timeFmt.format(Date(ultima))
            }
            val inicial = u.nome.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "W"
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Palette.BrandSoft)
                        .border(1.5.dp, Palette.Brand, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(inicial, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Brand)
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Success))
                        Text(u.nome.ifBlank { "Sua conta" }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                    }
                    Text(quando, fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 2.dp))
                }
            }
            SettingsDivider()
            SettingsRow("Sincronizar agora", icon = Icons.Filled.Refresh) {
                Nuvem.sincronizar(forcar = true)
                Toast.makeText(context, "Sincronizando…", Toast.LENGTH_SHORT).show()
            }
            SettingsDivider()
            SettingsRow("Sair da conta", "O que está no celular continua; só para de sincronizar.", danger = true) {
                confirmarSair = true
            }
        }
    }

    if (confirmarSair) {
        AlertDialog(
            onDismissRequest = { confirmarSair = false },
            title = { Text("Sair da conta?", color = Palette.Text) },
            text = { Text("O app continua funcionando neste celular, só para de sincronizar.", color = Palette.TextDim) },
            confirmButton = {
                TextButton(onClick = { Conta.sair(); confirmarSair = false }) {
                    Text("Sair", color = Palette.Error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmarSair = false }) { Text("Cancelar", color = Palette.TextDim) } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

/** Título com seta de voltar (telas por cima das abas). */
@Composable
fun BackTitle(title: String, nav: Nav) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.back() }) { Icon(Icons.Filled.ArrowBack, "Voltar", tint = Palette.Text) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
