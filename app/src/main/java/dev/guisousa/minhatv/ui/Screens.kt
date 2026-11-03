package dev.guisousa.minhatv.ui

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import dev.guisousa.minhatv.work.RefreshWorker
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun nowTicker(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(60_000); value = System.currentTimeMillis() }
    }
    return now
}

private fun toggleFav(context: android.content.Context, e: Entry) {
    val added = Repository.toggleFavorite(e.url)
    Toast.makeText(context, if (added) "★ ${e.name} nos favoritos" else "Removido dos favoritos", Toast.LENGTH_SHORT).show()
}

// ---------------- Ao vivo ----------------

/** Carrega uma lista do banco sem travar a tela. null = carregando. */
@Composable
private fun <T> load(vararg keys: Any?, loader: suspend () -> T): T? {
    val v by produceState<T?>(null, *keys) { value = null; value = loader() }
    return v
}

@Composable
private fun Loading() = Message("Carregando…")

@Composable
fun LiveScreen(lib: Library) {
    if (lib.liveTotal == 0) { Message("Nenhum canal ao vivo", "As playlists adicionadas não têm canais ao vivo (ou ainda estão carregando)."); return }
    var group by rememberSaveable(lib.version) { mutableStateOf(defaultGroup(lib.liveGroups, lib.liveTotal)) }
    val channels = load(lib.version, group) { Repository.entries(ContentType.LIVE, group) }
    Row(Modifier.fillMaxSize()) {
        GroupColumn(lib.liveGroups, lib.liveTotal, group) { group = it }
        if (channels == null) Loading() else ChannelList(channels)
    }
}

@Composable
fun ChannelList(channels: List<Entry>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val epg by Repository.epg.collectAsState()
    val favorites by Repository.favorites.collectAsState()
    val now = nowTicker()
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(channels) { i, e ->
            FocusBox(
                onClick = { PlaybackQueue.start(context, channels, i) },
                onLongClick = { toggleFav(context, e) },
                modifier = Modifier.fillMaxWidth(),
                focusScale = 1.02f,
            ) { focused ->
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(48.dp), fontSize = 14.sp, color = if (focused) Palette.Text else Palette.TextDim)
                    Logo(e.logo, Modifier.size(width = 84.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)), fallback = e.name)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (e.url in favorites) "★ " else "") + e.name,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val cur = epg.current(e, now)
                        if (cur != null) {
                            Text(
                                "${time.format(Date(cur.start))}–${time.format(Date(cur.stop))}  ${cur.title}",
                                fontSize = 14.sp, color = if (focused) Palette.Text else Palette.TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        } else if (e.group.isNotBlank()) {
                            Text(e.group, fontSize = 13.sp, color = if (focused) Palette.Text else Palette.TextDim, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Filmes ----------------

@Composable
fun MoviesScreen(lib: Library) {
    if (lib.movieTotal == 0) { Message("Nenhum filme", "As playlists adicionadas não têm filmes (ou ainda estão carregando)."); return }
    var group by rememberSaveable(lib.version) { mutableStateOf(defaultGroup(lib.movieGroups, lib.movieTotal)) }
    val movies = load(lib.version, group) { Repository.entries(ContentType.MOVIE, group) }
    Row(Modifier.fillMaxSize()) {
        GroupColumn(lib.movieGroups, lib.movieTotal, group) { group = it }
        if (movies == null) Loading() else EntryGrid(movies)
    }
}

@Composable
fun EntryGrid(items: List<Entry>) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items) { i, e ->
            PosterCard(e.name, e.logo, onClick = { PlaybackQueue.start(context, items, i) }, onLongClick = { toggleFav(context, e) })
        }
    }
}

// ---------------- Séries ----------------

@Composable
fun SeriesScreen(lib: Library, onOpen: (Show) -> Unit) {
    if (lib.showTotal == 0) { Message("Nenhuma série", "As playlists adicionadas não têm séries (ou ainda estão carregando)."); return }
    var group by rememberSaveable(lib.version) { mutableStateOf(defaultGroup(lib.showGroups, lib.showTotal)) }
    val shows = load(lib.version, group) { Repository.shows(group) }
    Row(Modifier.fillMaxSize()) {
        GroupColumn(lib.showGroups, lib.showTotal, group) { group = it }
        if (shows == null) Loading() else ShowGrid(shows, onOpen)
    }
}

@Composable
fun ShowGrid(shows: List<Show>, onOpen: (Show) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(shows) { _, s ->
            PosterCard(s.name, s.logo, subtitle = "${s.episodeCount} episódios", onClick = { onOpen(s) })
        }
    }
}

@Composable
fun SeriesDetailScreen(show: Show) {
    val context = LocalContext.current
    val all = load(show.key) { Repository.episodes(show) }
    if (all == null) { Loading(); return }
    if (all.isEmpty()) { Message("Nenhum episódio encontrado"); return }
    val seasons = remember(all) { all.groupBy { it.season ?: 0 }.toSortedMap().toList() }
    var season by remember(show) { mutableStateOf(seasons.first().first) }
    val episodes = seasons.firstOrNull { it.first == season }?.second.orEmpty()
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).padding(start = 24.dp, end = 12.dp)) {
            Logo(show.logo, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)), fallback = show.name)
            Spacer(Modifier.height(10.dp))
            Text(show.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2)
            Text("Voltar: botão ⟵ do controle", fontSize = 12.sp, color = Palette.TextDim)
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(seasons) { (s, eps) ->
                    FocusBox(onClick = { season = s }, selected = s == season, modifier = Modifier.fillMaxWidth(), focusScale = 1.02f) {
                        Text(
                            (if (s == 0) "Episódios" else "Temporada $s") + "  (${eps.size})",
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            fontSize = 15.sp, color = Palette.Text,
                        )
                    }
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 32.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(episodes) { i, e ->
                FocusBox(onClick = { PlaybackQueue.start(context, episodes, i) }, modifier = Modifier.fillMaxWidth(), focusScale = 1.02f) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.episode?.let { "E%02d".format(it) } ?: "${i + 1}", Modifier.width(60.dp), color = Palette.TextDim)
                        Text(e.name, fontSize = 16.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---------------- Favoritos ----------------

@Composable
fun FavoritesScreen(lib: Library) {
    val favorites by Repository.favorites.collectAsState()
    val items = load(lib.version, favorites) { Repository.favoriteEntries() }
    when {
        items == null -> Loading()
        items.isEmpty() -> Message(
            "Nenhum favorito ainda",
            "Segure o botão OK em um canal ou filme para favoritar.\nDurante o vídeo, o botão MENU também favorita.",
        )
        else -> ChannelList(items, Modifier.padding(start = 12.dp))
    }
}

// ---------------- Buscar ----------------

@Composable
fun SearchScreen(lib: Library, onOpen: (Show) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val results = load(lib.version, query) {
        val q = query.trim()
        if (q.isEmpty()) emptyList<Entry>() to emptyList() else Repository.search(q)
    } ?: (emptyList<Entry>() to emptyList())
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvButton("🔍  Digitar busca", onClick = { showSearchDialog(context, query) { query = it } })
            Spacer(Modifier.width(16.dp))
            if (query.isNotBlank()) {
                Text("Resultados para \"$query\": ${results.first.size + results.second.size}", color = Palette.TextDim)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (query.isBlank()) {
            Message("Busque canais, filmes e séries", "Aperte \"Digitar busca\" e escreva parte do nome.")
            return@Column
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(results.second) { s ->
                    FocusBox(onClick = { onOpen(s) }, modifier = Modifier.fillMaxWidth(), focusScale = 1.02f) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Logo(s.logo, Modifier.size(width = 52.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)), fallback = s.name, crop = true)
                            Spacer(Modifier.width(14.dp))
                            Text("Série · ${s.name}  (${s.episodeCount} episódios)", fontSize = 16.sp, color = Palette.Text)
                        }
                    }
                }
                itemsIndexed(results.first) { i, e ->
                    FocusBox(
                        onClick = { PlaybackQueue.start(context, results.first, i) },
                        onLongClick = { toggleFav(context, e) },
                        modifier = Modifier.fillMaxWidth(), focusScale = 1.02f,
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Logo(e.logo, Modifier.size(width = 84.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)), fallback = e.name)
                            Spacer(Modifier.width(14.dp))
                            val kind = if (e.type == ContentType.MOVIE) "Filme" else "Canal"
                            Text("$kind · ${e.name}", fontSize = 16.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Ajustes ----------------

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val playlists by Repository.playlists.collectAsState()
    val sources by Repository.sources.collectAsState()
    val status by Repository.status.collectAsState()
    var hours by remember { mutableStateOf(Repository.refreshHours) }
    var autoplay by remember { mutableStateOf(Repository.autoplayLast) }
    var hideAdult by remember { mutableStateOf(Repository.hideAdult) }
    val address = rememberPhoneSetupServer()
    val dateFmt = remember { DateFormat.getDateFormat(context) }
    val timeFmt = remember { DateFormat.getTimeFormat(context) }
    fun fmt(t: Long) = if (t <= 0) "nunca" else "${dateFmt.format(Date(t))} ${timeFmt.format(Date(t))}"

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (address != null) {
                    QrCode(address, Modifier.size(150.dp))
                    Spacer(Modifier.width(20.dp))
                }
                Column {
                    Text("Adicionar canais", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                    Text(
                        "Aponte a câmera do celular para o QR Code (mesmo Wi-Fi da TV) ou abra ${address ?: "(TV sem Wi-Fi)"}.\n" +
                            "Aceita link de playlist M3U, de uma página com várias listas ou de um repositório do GitHub.",
                        fontSize = 15.sp, color = Palette.TextDim,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("+ Digitar link", onClick = { showAddLinkDialog(context) { u, n, e -> addLink(context, u, n, e) } })
                TvButton("⟳ Atualizar tudo agora", onClick = {
                    Toast.makeText(context, "Atualizando…", Toast.LENGTH_SHORT).show()
                    Repository.refreshAllAsync()
                })
            }
            status?.let { Text(it, color = Palette.Accent, modifier = Modifier.padding(top = 6.dp)) }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Atualizar automaticamente a cada", fontSize = 16.sp, color = Palette.Text)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Repository.INTERVAL_OPTIONS.forEach { h ->
                    TvButton("${h}h", selected = h == hours, onClick = {
                        hours = h
                        Repository.refreshHours = h
                        RefreshWorker.schedule(context, h)
                    })
                }
            }
            Spacer(Modifier.height(10.dp))
            TvButton(
                (if (autoplay) "☑" else "☐") + "  Ao abrir o app, já tocar o último canal assistido",
                selected = autoplay,
                onClick = { autoplay = !autoplay; Repository.autoplayLast = autoplay },
            )
            Spacer(Modifier.height(10.dp))
            TvButton(
                (if (hideAdult) "☑" else "☐") + "  Esconder categorias adultas",
                selected = hideAdult,
                onClick = { hideAdult = !hideAdult; Repository.hideAdult = hideAdult },
            )
        }
        if (sources.isNotEmpty()) {
            item { Spacer(Modifier.height(8.dp)); Text("Páginas importadas", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text) }
            items(sources) { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.url, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${s.found} playlists encontradas · verificada: ${fmt(s.lastChecked)}" + (s.lastError?.let { " · erro: $it" } ?: ""),
                            fontSize = 13.sp, color = if (s.lastError != null) Palette.Error else Palette.TextDim,
                        )
                    }
                    TvButton("Remover", onClick = {
                        confirm(context, "Remover página?", "Remove a página e todas as playlists que vieram dela.", "Remover") {
                            Repository.removeSource(s.id)
                        }
                    })
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)); Text("Playlists (${playlists.size})", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text) }
        items(playlists, key = { it.id }) { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Palette.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${p.liveCount} canais · ${p.movieCount} filmes · ${p.seriesCount} episódios · atualizada: ${fmt(p.lastUpdated)}" +
                            (p.lastError?.let { " · erro: $it" } ?: ""),
                        fontSize = 13.sp, color = if (p.lastError != null) Palette.Error else Palette.TextDim,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                TvButton("Atualizar", onClick = { Repository.refreshOneAsync(p.id) })
                TvButton("Remover", onClick = {
                    confirm(context, "Remover playlist?", p.name, "Remover") { Repository.removePlaylist(p.id) }
                })
            }
        }
    }
}
