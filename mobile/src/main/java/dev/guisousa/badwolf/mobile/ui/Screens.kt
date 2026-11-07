package dev.guisousa.badwolf.mobile.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.badwolf.mobile.data.ContentType
import dev.guisousa.badwolf.mobile.data.Entry
import dev.guisousa.badwolf.mobile.data.LocalStore
import dev.guisousa.badwolf.mobile.data.Repository
import dev.guisousa.badwolf.mobile.data.Show
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
            it, fontSize = 13.sp, color = Palette.Text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHigh).padding(10.dp),
        )
    }
}

@Composable
private fun EmptyCatalog() {
    val loading by Repository.isLoading.collectAsState()
    val status by Repository.status.collectAsState()
    if (loading || status != null && status!!.startsWith("Baixando")) Loading() else Message("Nada por aqui ainda.")
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

    Column(Modifier.fillMaxSize()) {
        TopTitle("Bad Wolf TV", nav)
        StatusBanner()
        if (rows.isEmpty() && cont.isEmpty()) { EmptyCatalog(); return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (cont.isNotEmpty()) item("cont") {
                SectionTitle("Continuar assistindo")
                MediaRow(cont, 200.dp) { _, e ->
                    WideCard(e, Repository.progress(e.url)) {
                        if (e.type == ContentType.MOVIE) PlaybackQueue.start(context, listOf(e), 0)
                        else scope.launch { playEpisode(context, e) }
                    }
                }
            }
            if (recentLive.isNotEmpty()) item("live") {
                SectionTitle("Canais recentes")
                MediaRow(recentLive, 120.dp) { i, e -> ChannelTile(e) { PlaybackQueue.start(context, recentLive, i) } }
            }
            if (!myList.isNullOrEmpty()) item("fav") {
                SectionTitle("Minha lista")
                MediaRow(myList, 112.dp) { _, it -> AnyPoster(it, nav, watched) }
            }
            rows.forEachIndexed { i, r ->
                item("row$i") {
                    SectionTitle(r.title)
                    MediaRow(r.items, 112.dp) { _, it -> AnyPoster(it, nav, watched) }
                }
            }
        }
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
            if (item.type == ContentType.LIVE) ChannelTile(item, modifier) { PlaybackQueue.start(context, listOf(item), 0) }
            else PosterCard(
                item.name, item.logo, modifier, progress = Repository.progress(item.url), seen = item.url.hashCode().toString() in watched,
                onLongClick = { toggleFav(context, item) },
            ) { if (item.type == ContentType.MOVIE) nav.openMovie(item) else Repository.showOf(item)?.let(nav::openShow) }
        }
        is Show -> PosterCard(item.name, item.logo, modifier, onLongClick = { toggleFav(context, item) }) { nav.openShow(item) }
    }
}

/** Cartão largo do "Continuar assistindo". */
@Composable
private fun WideCard(e: Entry, progress: Float?, onClick: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)) {
            Logo(e.logo, Modifier.fillMaxSize(), fallback = e.name, crop = true)
            Box(Modifier.fillMaxSize().background(Color(0x55000000)))
            Text("▶", color = Color.White, fontSize = 28.sp, modifier = Modifier.align(Alignment.Center))
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
        }
        Text(e.name, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Canal em quadradinho (logo + nome). */
@Composable
private fun ChannelTile(e: Entry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(6.dp)).background(Palette.Surface).clickable(onClick = onClick)) {
            Logo(e.logo, Modifier.fillMaxSize().padding(8.dp), fallback = e.name)
        }
        Text(e.name, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

// ======================= Filmes / Séries =======================

private const val ALL = "Todos"

@Composable
fun MoviesScreen(nav: Nav) {
    val lib by Repository.library.collectAsState()
    val watched by Repository.watched.collectAsState()
    var group by rememberSaveable { mutableStateOf(ALL) }
    val options = remember(lib.version) { listOf(ALL) + lib.movieGroups.map { it.name } }
    val items = load(group, lib.version) { Repository.moviesPage(group.takeIf { it != ALL }, null) }
    Column(Modifier.fillMaxSize()) {
        TopTitle("Filmes", nav)
        StatusBanner()
        if (lib.movieGroups.isEmpty()) { EmptyCatalog(); return@Column }
        Chips(options, group) { group = it }
        Spacer(Modifier.height(8.dp))
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
    val options = remember(lib.version) { listOf(ALL) + lib.showGroups.map { it.name } }
    val items = load(group, lib.version) { Repository.showsPage(group.takeIf { it != ALL }, null) }
    Column(Modifier.fillMaxSize()) {
        TopTitle("Séries", nav)
        StatusBanner()
        if (lib.showGroups.isEmpty()) { EmptyCatalog(); return@Column }
        Chips(options, group) { group = it }
        Spacer(Modifier.height(8.dp))
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
        GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items, key = { i, it -> (if (it is Entry) it.url else (it as Show).key) + "#" + i }) { _, it -> AnyPoster(it, nav, watched) }
    }
}

// ======================= Ao vivo =======================

private const val RECENT = "Recentes"
private const val FAVS = "Minha lista"

@Composable
fun LiveScreen() {
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
    // o "agora passando" muda com o tempo
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }

    Column(Modifier.fillMaxSize()) {
        TopTitle("Ao vivo", null)
        StatusBanner()
        if (lib.liveGroups.isEmpty()) { EmptyCatalog(); return@Column }
        Chips(options, current) { group = it }
        Spacer(Modifier.height(4.dp))
        when {
            channels == null -> Loading()
            channels.isEmpty() -> Message("Nenhum canal aqui.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(channels, key = { i, e -> e.url + "#" + i }) { i, e ->
                    ChannelRow(e, i + 1, epg.current(e, now), e.url in favs, onLongClick = { toggleFav(context, e) }) {
                        if (current == RECENT || current == FAVS) PlaybackQueue.start(context, channels, i)
                        else PlaybackQueue.startLoadingGroup(context, channels, i) { Repository.entries(ContentType.LIVE, current) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(e: Entry, number: Int, now: dev.guisousa.badwolf.mobile.data.Programme?, fav: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(number.toString(), fontSize = 12.sp, color = Palette.TextFaint, modifier = Modifier.width(30.dp))
        Box(Modifier.size(72.dp, 44.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Surface)) {
            Logo(e.logo, Modifier.fillMaxSize().padding(4.dp), fallback = e.name)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, fontSize = 15.sp, color = Palette.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (fav) Text("  ★", fontSize = 12.sp, color = Palette.Brand)
            }
            if (now != null) {
                Text(now.title, fontSize = 13.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val span = (now.stop - now.start).coerceAtLeast(1)
                Box(Modifier.padding(top = 4.dp).width(120.dp)) { ProgressLine((System.currentTimeMillis() - now.start).toFloat() / span) }
            } else if (!e.quality.isNullOrBlank()) {
                Text(e.quality, fontSize = 12.sp, color = Palette.TextFaint, maxLines = 1)
            }
        }
        if (now != null) Text("até ${timeFmt.format(Date(now.stop))}", fontSize = 11.sp, color = Palette.TextFaint, modifier = Modifier.padding(start = 8.dp))
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
        Spacer(Modifier.statusBarsPadding())
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).focusRequester(focus),
            placeholder = { Text("Filmes, séries e canais") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Limpar") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { save(); keyboard?.hide() }),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Palette.Surface, unfocusedContainerColor = Palette.Surface,
                focusedBorderColor = Palette.Brand, unfocusedBorderColor = Color.Transparent, cursorColor = Palette.Brand,
            ),
        )
        if (query.trim().length < 2) {
            if (recentSearches.isEmpty()) { Message("Digite pelo menos 2 letras."); return@Column }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Buscas recentes", fontSize = 15.sp, color = Palette.TextDim, modifier = Modifier.weight(1f))
                TextButton(onClick = { LocalStore.clearRecentSearches(); recentVersion++ }) { Text("Limpar", color = Palette.TextDim) }
            }
            LazyColumn {
                items(recentSearches) { s ->
                    Text(s, fontSize = 16.sp, color = Palette.Text, modifier = Modifier.fillMaxWidth().clickable { query = s }.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            return@Column
        }
        if (result == null) { Loading(); return@Column }
        val (entries, shows) = result
        val live = entries.filter { it.type == ContentType.LIVE }
        val posters: List<Any> = entries.filter { it.type != ContentType.LIVE } + shows
        if (live.isEmpty() && posters.isEmpty()) { Message("Nada encontrado para \"$query\"."); return@Column }
        LazyVerticalGrid(
            GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (live.isNotEmpty()) {
                item("lh", span = { GridItemSpan(maxLineSpan) }) { Text("Canais", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text) }
                itemsIndexed(live.take(30), key = { i, e -> "l" + e.url + i }) { i, e ->
                    ChannelTile(e) { save(); PlaybackQueue.start(context, live, i) }
                }
            }
            if (posters.isNotEmpty()) {
                item("ph", span = { GridItemSpan(maxLineSpan) }) { Text("Filmes e séries", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.padding(top = 8.dp)) }
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
fun SettingsScreen(nav: Nav) {
    val context = LocalContext.current
    val lib by Repository.library.collectAsState()
    var pref by remember { mutableStateOf(Repository.preferredVersion) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackTitle("Ajustes", nav)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                SettingHeader("Versão preferida")
                listOf(Repository.DUB, Repository.LEG).forEach { v ->
                    SettingRow(Repository.versionName(v), if (pref == v) "✓" else null) { pref = v; Repository.preferredVersion = v }
                }
                Text(
                    "Vale para títulos que você ainda não escolheu. Em cada filme ou série dá para trocar no botão Versão.",
                    fontSize = 12.sp, color = Palette.TextFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SettingHeader("Catálogo")
                SettingRow("Atualizar agora", "${lib.liveTotal} canais · ${lib.movieTotal} filmes · ${lib.showTotal} séries") {
                    Repository.refreshAllAsync()
                    Toast.makeText(context, "Conferindo o catálogo…", Toast.LENGTH_SHORT).show()
                }
                SettingHeader("Histórico")
                SettingRow("Limpar buscas recentes", null) {
                    LocalStore.clearRecentSearches()
                    Toast.makeText(context, "Buscas apagadas", Toast.LENGTH_SHORT).show()
                }
                SettingRow("Apagar o que já vi", "Continuar assistindo, recentes e marcas de visto (a Minha lista fica)") { confirmClear = true }
                SettingHeader("Sobre")
                SettingRow("Bad Wolf TV para celular", "Versão ${dev.guisousa.badwolf.mobile.BuildConfig.VERSION_NAME}") {}
            }
        }
    }
    if (confirmClear) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Apagar o histórico?") },
        text = { Text("Some o Continuar assistindo, os canais recentes e as marcas de visto. A Minha lista fica.") },
        confirmButton = { TextButton(onClick = { LocalStore.clearHistory(); confirmClear = false }) { Text("Apagar", color = Palette.Error) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        containerColor = Palette.SurfaceHigh,
    )
}

@Composable
private fun SettingHeader(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, color = Palette.Brand, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 6.dp))
}

@Composable
private fun SettingRow(title: String, detail: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Palette.Text)
            if (detail != null && detail != "✓") Text(detail, fontSize = 13.sp, color = Palette.TextDim)
        }
        if (detail == "✓") Text("✓", fontSize = 18.sp, color = Palette.Brand)
    }
}

/** Título com seta de voltar (telas por cima das abas). */
@Composable
fun BackTitle(title: String, nav: Nav) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.back() }) { Text("←", fontSize = 22.sp, color = Palette.Text) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
