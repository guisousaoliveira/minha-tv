@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.guisousa.minhatv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Library
import dev.guisousa.minhatv.data.Repository
import kotlinx.coroutines.launch

// Páginas de Filmes/Séries e a grade "Ver todos", no estilo do Saimo TV.

/** Grupo especial: "Todos de A a Z". */
const val ALL_GROUP = "\u0001todos"

val POSTER_SAIMO_W = 136.dp

private val yearRe = Regex("""\b(19|20)\d{2}\b""")

private fun sortKey(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trimStart { !it.isLetterOrDigit() }

/** Chave para juntar o mesmo filme repetido: sem etiquetas (FHD, [L], Dublado), sem ano, sem acento. */
internal fun dupKey(s: String): String = dev.guisousa.minhatv.data.Normalize.name(yearRe.replace(dev.guisousa.minhatv.data.Normalize.displayCached(s).title, " "))

private fun initialOf(s: String): Char {
    val c = sortKey(s).firstOrNull() ?: '#'
    return if (c in 'a'..'z') c.uppercaseChar() else '#'
}

/** Um item da grade: filme ou série. */
private class GridItem(val id: String, val title: String, val image: String?, val subtitle: String, val year: Int, val order: Int, val open: () -> Unit, val fav: (() -> Unit)?, val ref: String? = null)

/** Fileira "Lançamentos" no topo das páginas de Filmes e Séries. */
@Composable
internal fun ReleasesRow(lib: Library, type: ContentType, nav: Nav, header: HeaderState) {
    val context = LocalContext.current
    if (type == ContentType.MOVIE) {
        // enquanto carrega, guarda o espaço da fileira: assim as de baixo não "pulam" quando ela aparece
        val items = load(lib.version, "rel-m") { Repository.recentMovies() } ?: run { Box(Modifier.height(290.dp)); return }
        if (items.isEmpty()) return
        SectionTitle("Lançamentos", trailing = "${items.size}")
        MediaRow(items, POSTER_SAIMO_W) { _, e ->
            PosterCard(
                e.name, e.logo, key = "rel:${e.url}", progress = Repository.progress(e.url),
                onFocused = { header.show(e, "Filme · ${e.group}") },
                onClick = { nav.openMovie(e) }, onLongClick = { toggleFav(context, e) },
            )
        }
    } else {
        val shows = load(lib.version, "rel-s") { Repository.recentShows() } ?: run { Box(Modifier.height(290.dp)); return }
        if (shows.isEmpty()) return
        SectionTitle("Lançamentos", trailing = "${shows.size}")
        MediaRow(shows, POSTER_SAIMO_W) { _, s ->
            PosterCard(s.name, s.logo, key = "rel:${s.key}", tmdbTitle = s.name, tv = true, onFocused = { header.show(s, "Série · ${s.group}") }, onClick = { nav.openShow(s) })
        }
    }
}

/** "Em alta no Brasil": filmes populares agora no Brasil (TMDB) que estão nas listas. */
@Composable
internal fun PopularRow(lib: Library, nav: Nav, header: HeaderState) {
    val context = LocalContext.current
    val items = load(lib.version, "pop-m") { Repository.popularMovies() } ?: run { Box(Modifier.height(290.dp)); return }
    if (items.isEmpty()) return
    SectionTitle("Em alta no Brasil", trailing = "${items.size}")
    MediaRow(items, POSTER_SAIMO_W) { _, e ->
        PosterCard(
            e.name, e.logo, key = "pop:${e.url}", progress = Repository.progress(e.url),
            onFocused = { header.show(e, "Filme · ${e.group}") },
            onClick = { nav.openMovie(e) }, onLongClick = { toggleFav(context, e) },
        )
    }
}

/** Cartão largo "Todos de A a Z" no fim da página. */
@Composable
fun AllAzRow(type: ContentType, total: Int, nav: Nav) {
    Spacer(Modifier.height(18.dp))
    Box(Modifier.padding(horizontal = 48.dp, vertical = 10.dp)) {
        FocusBox(onClick = { nav.openCategory(type, ALL_GROUP) }, frame = true, focusScale = 1.04f, modifier = Modifier.width(264.dp).height(140.dp)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Todos de A a Z", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                Text("$total ${if (type == ContentType.SERIES) "séries" else "filmes"}", fontSize = 13.sp, color = Palette.TextDim)
            }
        }
    }
}

/**
 * Grade "Ver todos": total, filtro de gênero, ordem A–Z ou Mais novos e coluna de letras à esquerda.
 * Segurar OK num cartão adiciona à Minha lista.
 */
@Composable
fun VodGridScreen(lib: Library, type: ContentType, group: String, nav: Nav) {
    val context = LocalContext.current
    val header = remember(type, group) { HeaderState() }
    val isAll = group == ALL_GROUP
    var genre by rememberSaveable(type, group) { mutableStateOf<String?>(if (isAll) null else group) }
    var newest by rememberSaveable(type, group) { mutableStateOf(false) }
    var picking by rememberSaveable(type, group) { mutableStateOf(false) }
    val kind = if (type == ContentType.SERIES) "Série" else "Filme"
    val groups0 = if (type == ContentType.SERIES) lib.showGroups else lib.movieGroups
    val size = genre?.let { g -> groups0.firstOrNull { it.name == g }?.count } ?: groups0.sumOf { it.count }
    // muita coisa (ex.: "Todos", 120 mil filmes): uma letra por vez, direto do banco; senão, tudo de uma vez
    val paged = size > 2000
    var letter by rememberSaveable(type, group) { mutableStateOf('A') }
    val items = load(lib.version, type, genre, newest, if (paged) letter else null) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val list = if (type == ContentType.SERIES) {
                val shows = if (paged) Repository.showsPage(genre, if (newest) null else letter) else Repository.shows(genre)
                shows.mapIndexed { i, s -> GridItem(s.key, s.name, s.logo?.ifBlank { null }, "Série · ${s.group}", yearRe.find(s.name)?.value?.toInt() ?: 0, i, { nav.openShow(s) }, null, Repository.showRef(s)) }
            } else {
                val movies = if (paged) Repository.moviesPage(genre, if (newest) null else letter) else Repository.entries(ContentType.MOVIE, genre, 3000)
                movies.mapIndexed { i, e -> GridItem(e.url, e.name, e.logo?.ifBlank { null }, "Filme · ${e.group}", e.year ?: yearRe.find(e.name)?.value?.toInt() ?: 0, i, { nav.openMovie(e) }, { toggleFav(context, e) }, e.url) }
            }
            // ordena fora da tela principal, calculando a chave uma vez só por item
            // (o ano do nome, não o do catálogo: "novidades" segue a ordem de chegada que vem do servidor)
            if (newest) list.sortedWith(compareByDescending<GridItem> { yearRe.find(it.title)?.value?.toInt() ?: 0 }.thenBy { if (paged) it.order else -it.order })
            else list.map { sortKey(it.title) to it }.sortedBy { it.first }.map { it.second }
        }.let { l ->
            // o mesmo título repetido (dublado e legendado, outra qualidade): um cartão só, as versões ficam nos detalhes
            // anos diferentes (refilmagem: Aladdin 1992 e 2019) ficam separados
            val seen = HashSet<String>()
            l.filter { it.title.isNotBlank() }.filter { item ->
                val k = dupKey(item.title).ifEmpty { sortKey(item.title) }
                // séries: o ano no nome costuma ser só da temporada, então junta sempre
                seen.add(if (type == ContentType.SERIES) k else "$k|${item.year}")
            }
        }
    }
    val letters = remember(items, newest, paged) {
        when {
            newest -> emptyList()
            // paginado: as letras trocam a página (índice -1 = escolher a letra)
            paged -> ("#" + ('A'..'Z').joinToString("")).map { it to -1 }
            items == null -> emptyList()
            else -> {
                val firsts = LinkedHashMap<Char, Int>()
                items.forEachIndexed { i, it -> firsts.putIfAbsent(initialOf(it.title), i) }
                firsts.toList()
            }
        }
    }
    val gridState = rememberLazyGridState()
    // ◀ da grade cai na letra em que você está (e não na letra mais próxima na tela)
    val letterRequesters = remember { HashMap<Char, FocusRequester>() }
    val shownLetters = remember { HashSet<Char>() }
    var gridLetter by remember { mutableStateOf('A') }
    val currentLetter = if (paged && !newest) letter else gridLetter
    val firstPoster = remember { FocusRequester() }
    var focusedOnce by remember(type, group) { mutableStateOf(false) }
    // enquanto carrega, o foco fica num cartão de espera dentro da grade (e não pula para o menu de cima)
    val waitBox = remember { FocusRequester() }
    var waiting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val groups = if (type == ContentType.SERIES) lib.showGroups else lib.movieGroups
    val title = when { genre != null -> genre!!; else -> if (type == ContentType.SERIES) "Todas as séries" else "Todos os filmes" }

    // mesmo visual das abas: fundo e ficha do título em foco em cima (mais baixo, para caber a grade)
    CinemaLayout(header, lib.version, title, if (isAll) "De A a Z" else kind + "s", heroHeight = 232.dp, infoTop = 14.dp, snapMargin = 14.dp) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 48.dp, end = 48.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when {
                    items == null -> "Carregando…"
                    paged && !newest -> "${items.size} com $letter"
                    else -> "${items.size} títulos"
                }, fontSize = 15.sp, color = Palette.TextDim, modifier = Modifier.width(130.dp))
            TvButton("Gênero: ${genre ?: "Todos"}", selected = picking, onClick = { picking = !picking })
            TvButton("A–Z", selected = !newest, onClick = { newest = false; scope.launch { gridState.scrollToItem(0) } })
            TvButton("Mais novos", selected = newest, onClick = { newest = true; scope.launch { gridState.scrollToItem(0) } })
        }
        Row(Modifier.fillMaxSize()) {
            when {
                picking -> GroupColumn(groups, groups.sumOf { it.count }, genre, alwaysAll = true) { g -> genre = g; picking = false }
                letters.isNotEmpty() -> LazyColumn(
                    Modifier.width(56.dp).fillMaxHeight().padding(start = 12.dp)
                        .focusProperties { enter = { if (currentLetter in shownLetters) letterRequesters[currentLetter] ?: FocusRequester.Default else FocusRequester.Default } }
                        .focusGroup(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(letters) { (c, idx) ->
                        androidx.compose.runtime.DisposableEffect(c) { shownLetters += c; onDispose { shownLetters -= c } }
                        FocusBox(
                            onClick = { if (idx < 0) { letter = c; header.title = null; header.subtitle = null; header.image = null; scope.launch { gridState.scrollToItem(0) } } else scope.launch { gridState.scrollToItem(idx) } },
                            onFocused = { if (idx >= 0) scope.launch { gridState.scrollToItem(idx) } },
                            selected = paged && c == letter,
                            modifier = Modifier.fillMaxWidth().height(30.dp).focusRequester(letterRequesters.getOrPut(c) { FocusRequester() }), focusScale = 1f, background = Color.Transparent, shape = RoundedCornerShape(4.dp),
                        ) { focused ->
                            Text(
                                c.toString(), Modifier.align(Alignment.Center), textAlign = TextAlign.Center,
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Palette.OnFocus else Palette.TextDim,
                            )
                        }
                    }
                }
                else -> Spacer(Modifier.width(56.dp))
            }
            if (items == null) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, top = 8.dp)) {
                    FocusBox(onClick = {}, frame = true, focusScale = 1f, modifier = Modifier.width(POSTER_SAIMO_W).aspectRatio(2f / 3f).focusRequester(waitBox).onFocusChanged { if (it.isFocused) waiting = true }) {
                        Spinner(26.dp, Modifier.align(Alignment.Center))
                    }
                }
                LaunchedEffect(Unit) { if (!focusedOnce || waiting) { delay(60); runCatching { waitBox.requestFocus() } } }
                return@Row
            }
            if (items.isEmpty()) { Message("Nada nesta categoria"); return@Row }
            // ao abrir a grade, o foco começa na primeira capa (e não fica perdido no menu)
            LaunchedEffect(items.isNotEmpty()) {
                if (!focusedOnce || waiting) { delay(80); runCatching { firstPoster.requestFocus() }; focusedOnce = true; waiting = false }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(POSTER_SAIMO_W),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 20.dp, end = 48.dp, top = 8.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(items, key = { i, it -> "$i:${it.title}" }) { i, it ->
                    PosterCard(
                        it.title, it.image, key = "grid:${it.id}", tmdbTitle = if (type == ContentType.SERIES) it.title else null, tv = true, onFocused = { header.show(it.title, it.subtitle, it.image, it.ref); gridLetter = initialOf(it.title) }, onClick = it.open, onLongClick = it.fav,
                        requester = if (i == 0) firstPoster else null,
                    )
                }
            }
        }
    }
}
}
