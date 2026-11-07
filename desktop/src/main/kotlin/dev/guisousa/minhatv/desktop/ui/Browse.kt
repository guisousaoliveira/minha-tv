package dev.guisousa.minhatv.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Show
import dev.guisousa.minhatv.data.UserLibrary

val PosterW = 150.dp
val ChannelW = 210.dp

/** Página com o destaque fixo em cima e o conteúdo rolando embaixo. */
@Composable
fun CinemaPage(hero: HeroItem?, headerFraction: Float = 0.44f, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight * headerFraction
        Column(Modifier.fillMaxSize()) {
            CinemaHeader(hero, Modifier.height(h))
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** Cartão de filme/série/episódio/canal para fileiras e grades. */
@Composable
fun ItemCard(item: Any, onHover: () -> Unit, modifier: Modifier = Modifier, resume: Boolean = false) {
    val nav = LocalNav.current
    val user by UserLibrary.state.collectAsState()
    when (item) {
        is Show -> Poster(item.name, item.logo, { nav.openShow(item) }, modifier, onHover = onHover)
        is Entry -> when (item.type) {
            ContentType.LIVE -> ChannelCard(item, { nav.play(item) }, modifier, onHover = onHover)
            ContentType.SERIES -> Poster(
                "${item.seriesName ?: item.name} · T${item.season} E${item.episode}", item.logo, { nav.play(item) }, modifier,
                progress = UserLibrary.fracao(item.url), onHover = onHover, caption = true,
            )
            ContentType.MOVIE -> Poster(
                item.name, item.logo, { if (resume) nav.play(item) else nav.openMovie(item) }, modifier,
                progress = user.progresso[item.url]?.let { p -> if (p.dur > 0) p.pos.toFloat() / p.dur else null },
                watched = item.url in user.jaAssisti, onHover = onHover, caption = resume,
            )
        }
    }
}

fun itemKey(item: Any): Any = when (item) { is Show -> "s:" + item.key; is Entry -> item.url; else -> item.hashCode() }

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val lib by Repository.library.collectAsState()
    val user by UserLibrary.state.collectAsState()
    val status by Repository.status.collectAsState()
    val rows = remember(lib.version) { Repository.homeRows() }
    var hero by remember { mutableStateOf<HeroItem?>(null) }
    if (hero == null) rows.firstOrNull()?.items?.firstOrNull()?.let { hero = heroOf(it) }
    if (lib.isEmpty) { CenterMessage(status ?: "Carregando…"); return }
    val continuar = remember(user, lib.version) { UserLibrary.continuarAssistindo() }
    val canais = remember(user) { UserLibrary.recentesAoVivo().take(20) }
    val lista = remember(user.minhaLista, lib.version) { user.minhaLista.mapNotNull { Repository.itemOf(it) }.take(30) }
    CinemaPage(hero) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)) {
            if (continuar.isNotEmpty()) item("cont") {
                Shelf("Continuar assistindo", continuar, PosterW, key = { it.url }) { e -> ItemCard(e, { hero = heroOf(e) }, resume = true) }
            }
            if (canais.isNotEmpty()) item("canais") {
                Shelf("Canais que você viu", canais, ChannelW, key = { it.url }, onTitle = { nav.selectTab(Tab.LIVE) }) { e -> ItemCard(e, { hero = heroOf(e) }) }
            }
            if (lista.isNotEmpty()) item("lista") {
                Shelf("Minha lista", lista, PosterW, key = ::itemKey, onTitle = { nav.selectTab(Tab.MYLIST) }) { it2 -> ItemCard(it2, { hero = heroOf(it2) }) }
            }
            items(rows, key = { "r:" + it.title }) { r ->
                Shelf(r.title, r.items, PosterW, key = ::itemKey) { it2 -> ItemCard(it2, { hero = heroOf(it2) }) }
            }
        }
    }
}

/** Página de Filmes ou Séries: Lançamentos e uma fileira por categoria. */
@Composable
fun VodScreen(type: ContentType) {
    val nav = LocalNav.current
    val lib by Repository.library.collectAsState()
    val status by Repository.status.collectAsState()
    val groups = if (type == ContentType.MOVIE) lib.movieGroups else lib.showGroups
    var hero by remember(type) { mutableStateOf<HeroItem?>(null) }
    val recentes: List<Any> = remember(lib.version, type) { if (type == ContentType.MOVIE) Repository.recentMovies() else Repository.recentShows() }
    if (hero == null) recentes.firstOrNull()?.let { hero = heroOf(it) }
    if (lib.isEmpty) { CenterMessage(status ?: "Carregando…"); return }
    val label = if (type == ContentType.MOVIE) "Filmes" else "Séries"
    CinemaPage(hero) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp)) {
            item("cats") {
                LazyRow(contentPadding = PaddingValues(horizontal = PagePad), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                    item { Chip("Todos", false, { nav.push(Route.Category(type, null, "Todos os ${label.lowercase()}")) }) }
                    items(groups) { g -> Chip(g.name, false, { nav.push(Route.Category(type, g.name, g.name)) }) }
                }
            }
            if (recentes.isNotEmpty()) item("rec") {
                Shelf("Lançamentos", recentes, PosterW, key = ::itemKey) { it2 -> ItemCard(it2, { hero = heroOf(it2) }) }
            }
            items(groups, key = { "g:" + it.name }) { g ->
                val list by produceState<List<Any>>(emptyList(), lib.version, g.name) {
                    value = if (type == ContentType.MOVIE) Repository.entries(type, g.name, 30) else Repository.shows(g.name, 30)
                }
                if (list.isEmpty()) Box(Modifier.height(260.dp))
                else Shelf("${g.name}  ·  ${g.count}", list, PosterW, key = ::itemKey, onTitle = { nav.push(Route.Category(type, g.name, g.name)) }) { it2 ->
                    ItemCard(it2, { hero = heroOf(it2) })
                }
            }
        }
    }
}

/** Grade de uma categoria, com filtro por letra (Novos, A–Z, #). */
@Composable
fun CategoryScreen(r: Route.Category) {
    val lib by Repository.library.collectAsState()
    var letter by remember(r) { mutableStateOf<Char?>(null) }
    var hero by remember(r) { mutableStateOf<HeroItem?>(null) }
    val list by produceState<List<Any>?>(null, r, letter, lib.version) {
        value = null
        value = if (r.type == ContentType.MOVIE) Repository.moviesPage(r.group, letter) else Repository.showsPage(r.group, letter)
    }
    if (hero == null) list?.firstOrNull()?.let { hero = heroOf(it) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(letter) { gridState.scrollToItem(0) }
    CinemaPage(hero, headerFraction = 0.36f) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = PagePad, end = PagePad, bottom = 6.dp)) {
                Text(r.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                list?.let { Text("   ${it.size} títulos", fontSize = 14.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 6.dp)) }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = PagePad, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Chip("Novos", letter == null, { letter = null }, compact = true) }
                items(('A'..'Z').toList() + '#') { c -> Chip(c.toString(), letter == c, { letter = c }, compact = true) }
            }
            val l = list
            when {
                l == null -> CenterMessage("Carregando…")
                l.isEmpty() -> CenterMessage("Nada com essa letra.")
                else -> PosterGrid(l, gridState) { hero = heroOf(it) }
            }
        }
    }
}

@Composable
fun PosterGrid(items: List<Any>, state: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(), minWidth: Dp = PosterW, onHover: (Any) -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(minWidth), Modifier.fillMaxWidth(), state = state,
        contentPadding = PaddingValues(start = PagePad, end = PagePad, top = 10.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = ::itemKey) { ItemCard(it, { onHover(it) }) }
    }
}
