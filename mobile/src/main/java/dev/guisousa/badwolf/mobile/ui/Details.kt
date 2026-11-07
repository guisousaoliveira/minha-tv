package dev.guisousa.badwolf.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.badwolf.mobile.data.Entry
import dev.guisousa.badwolf.mobile.data.LocalStore
import dev.guisousa.badwolf.mobile.data.Repository
import dev.guisousa.badwolf.mobile.data.Show
import dev.guisousa.badwolf.mobile.player.PlaybackQueue
import kotlinx.coroutines.delay

/** Fila do player: temporadas em ordem e os extras (temporada 0) por último, como na TV. */
fun seriesQueue(all: List<Entry>): List<Entry> {
    val seasons = all.groupBy { it.season ?: 0 }.toSortedMap()
    return seasons.filterKeys { it != 0 }.values.flatten() + seasons[0].orEmpty()
}

@Composable
private fun MainButton(text: String, icon: ImageVector? = Icons.Filled.PlayArrow, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.height(48.dp), shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
    ) {
        if (icon != null) { Icon(icon, null); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmallButton(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.Text),
        border = null, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, fontSize = 14.sp, maxLines = 1)
    }
}

/** Botão Versão: só aparece quando o título tem Dublado e Legendado. */
@Composable
private fun VersionButton(e: Entry, available: Set<String>, onChange: () -> Unit = {}) {
    if (available.size < 2) return
    var v by remember(e.url) { mutableStateOf(Repository.versionFor(e)) }
    SmallButton("Versão: ${Repository.versionName(v)}") {
        val next = if (v == Repository.DUB) Repository.LEG else Repository.DUB
        v = next
        Repository.setVersionFor(e, next)
        onChange()
    }
}

private fun versionInfo(available: Set<String>?): String? = available?.singleOrNull()?.let { Repository.versionName(it) }

/** Topo dos detalhes: fundo, capa, título, informações e sinopse. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailTop(
    nav: Nav, kicker: String, title: String, poster: String?, backdrop: String?, meta: List<String>, overview: String?,
    buttons: @Composable () -> Unit,
) {
    Box {
        Backdrop(backdrop ?: poster)
        IconButton(onClick = { nav.back() }, modifier = Modifier.statusBarsPadding().padding(4.dp).clip(RoundedCornerShape(50)).background(Color(0x66000000))) {
            Text("←", fontSize = 22.sp, color = Color.White)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).offset(y = (-56).dp), verticalAlignment = Alignment.Bottom) {
        Logo(poster, Modifier.width(104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)), fallback = title, crop = true)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(kicker.uppercase(), fontSize = 11.sp, color = Palette.Brand, fontWeight = FontWeight.SemiBold)
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Meta(meta)
        }
    }
    Column(Modifier.padding(horizontal = 16.dp).offset(y = (-40).dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        if (!overview.isNullOrBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Text(
                overview, fontSize = 14.sp, color = Palette.TextDim, lineHeight = 20.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp).combinedClickableCompat { expanded = !expanded },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

// ======================= Filme =======================

@Composable
fun MovieDetailScreen(e: Entry, nav: Nav) {
    val context = LocalContext.current
    val favs by Repository.favorites.collectAsState()
    val watched by Repository.watched.collectAsState()
    val recents by LocalStore.recentsVersion.collectAsState()
    val versions = remember(e.url) { Repository.movieVersions(e) }
    val resume = remember(e.url, recents) { Repository.getResume(e.url) }
    val info = load("ficha", e.url) { Repository.movieInfo(e) }
    val destaque = remember(e.url) { Repository.destaque(e.url) }
    val similar = load("similar", e.url) { Repository.similarMovies(e) }
    val seen = e.url.hashCode().toString() in watched

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item("top") {
            DetailTop(
                nav, "Filme", e.name, info?.p ?: e.logo, destaque?.fundo ?: info?.b,
                listOfNotNull(
                    (info?.a ?: e.year)?.toString(),
                    destaque?.generos?.take(2)?.joinToString(", ")?.ifBlank { null } ?: info?.g?.take(2)?.joinToString(", ")?.ifBlank { null },
                    "4K".takeIf { destaque?.k4 == true },
                    versionInfo(versions),
                ),
                info?.s,
            ) {
                MainButton(if (resume > 0) "Continuar de ${clock(resume)}" else "Assistir") { PlaybackQueue.start(context, listOf(e), 0) }
                if (resume > 0) SmallButton("Do início") { Repository.clearResume(e.url); PlaybackQueue.start(context, listOf(e), 0) }
                VersionButton(e, versions)
                SmallButton(if (e.url in favs) "Na Minha lista" else "Minha lista", if (e.url in favs) Icons.Filled.Check else Icons.Filled.Add) { toggleFav(context, e) }
                SmallButton(if (seen) "Já assisti ✓" else "Marcar como visto") { if (seen) Repository.unmarkWatched(e.url) else Repository.markWatched(e.url) }
            }
        }
        if (!similar.isNullOrEmpty()) item("similar") {
            SectionTitle("Mais como este", Modifier.offset(y = (-32).dp))
            Box(Modifier.offset(y = (-32).dp)) {
                MediaRow(similar, 108.dp) { _, m ->
                    PosterCard(m.name, m.logo, progress = Repository.progress(m.url), seen = m.url.hashCode().toString() in watched, onLongClick = { toggleFav(context, m) }) { nav.openMovie(m) }
                }
            }
        }
    }
}

// ======================= Série =======================

@Composable
fun SeriesDetailScreen(show: Show, nav: Nav) {
    val context = LocalContext.current
    var attempt by remember(show.key) { mutableIntStateOf(0) }
    val result = load(show.key, attempt) { Repository.episodes(show) }
    val destaque = remember(show.key) { Repository.destaque(Repository.showRef(show)) }
    val favs by Repository.favorites.collectAsState()
    val watched by Repository.watched.collectAsState()
    val recents by LocalStore.recentsVersion.collectAsState()

    if (result == null) {
        Column(Modifier.fillMaxSize()) {
            BackTitle(show.name, nav)
            var secs by remember(show.key, attempt) { mutableIntStateOf(0) }
            LaunchedEffect(show.key, attempt) { while (true) { delay(1_000); secs++ } }
            Box(Modifier.fillMaxSize()) {
                Loading()
                if (secs >= 2) Text("Buscando os episódios… $secs s", fontSize = 14.sp, color = Palette.TextDim, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
            }
        }
        return
    }
    val all = result.list
    if (all.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            BackTitle(show.name, nav)
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (result.error == null) "Nenhum episódio encontrado" else "Não consegui buscar os episódios", fontSize = 18.sp, color = Palette.Text)
                result.error?.let { Text("Motivo: $it", fontSize = 14.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(20.dp))
                MainButton("Tentar de novo", null) { attempt++ }
            }
        }
        return
    }
    val seasons = remember(all) { all.groupBy { it.season ?: 0 }.toSortedMap().toList() }
    val queue = remember(all) { seriesQueue(all) }
    // botão principal: continua de onde parou, ou o primeiro episódio ainda não visto
    val target = remember(queue, watched, recents) {
        queue.firstOrNull { Repository.progress(it.url) != null }
            ?: queue.lastOrNull { Repository.isWatched(it.url) }?.let { last -> queue.getOrNull(queue.indexOf(last) + 1) }
            ?: queue.first()
    }
    var season by rememberSaveable(show.key) { mutableIntStateOf(target.season ?: seasons.first().first) }
    val episodes = seasons.firstOrNull { it.first == season }?.second.orEmpty()
    val seasonCount = seasons.count { it.first != 0 }
    fun play(e: Entry) = PlaybackQueue.start(context, queue, queue.indexOfFirst { it.url == e.url }.coerceAtLeast(0))
    val ep = if (target.season != null && target.episode != null) " T${target.season} E${target.episode}" else ""
    val info = result.info
    val inList = Repository.isShowFavorite(show, favs)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item("top") {
            DetailTop(
                nav, "Série", show.name, info?.p ?: show.logo, destaque?.fundo ?: info?.b,
                listOfNotNull(
                    info?.a?.toString(),
                    if (seasonCount > 0) "$seasonCount temporada${if (seasonCount > 1) "s" else ""}" else null,
                    "${all.size} episódio${if (all.size > 1) "s" else ""}",
                    versionInfo(result.versions),
                ),
                info?.s,
            ) {
                MainButton((if (Repository.progress(target.url) != null) "Continuar" else "Assistir") + ep) { play(target) }
                // trocou a versão: refaz a lista para os episódios virem na versão escolhida
                VersionButton(target, result.versions) { attempt++ }
                SmallButton(if (inList) "Na Minha lista" else "Minha lista", if (inList) Icons.Filled.Check else Icons.Filled.Add) {
                    val added = Repository.toggleShowFavorite(show, target.url)
                    android.widget.Toast.makeText(context, if (added) "Adicionado à Minha lista" else "Removido da Minha lista", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (seasons.size > 1) item("seasons") {
            Box(Modifier.offset(y = (-28).dp)) {
                Chips(seasons.map { (s, _) -> if (s == 0) "Extras" else "Temporada $s" }, if (season == 0) "Extras" else "Temporada $season") { label ->
                    season = if (label == "Extras") 0 else label.removePrefix("Temporada ").toIntOrNull() ?: season
                }
            }
        }
        itemsIndexed(episodes, key = { _, e -> e.url }) { i, e ->
            Box(Modifier.offset(y = (-20).dp)) {
                EpisodeRow(e, i, show.logo, e.url.hashCode().toString() in watched, Repository.progress(e.url), onClick = { play(e) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(e: Entry, i: Int, fallbackImage: String?, seen: Boolean, progress: Float?, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { toggleFav(context, e) }).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(5.dp))) {
            Logo(e.logo ?: fallbackImage, Modifier.fillMaxSize().alpha(0.55f), fallback = e.name, crop = true)
            Text((e.episode ?: (i + 1)).toString(), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.Center))
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Episódio ${e.episode ?: (i + 1)}", fontSize = 15.sp, color = Palette.Text, fontWeight = FontWeight.Medium)
            val d = Repository.duration(e.url)
            val sub = when {
                seen -> "Visto"
                progress != null && d > 0 -> "Faltam ${clock(d - Repository.getResume(e.url))}"
                else -> null
            }
            if (sub != null) Text(sub, fontSize = 12.sp, color = Palette.TextDim)
        }
        IconButton(onClick = { if (seen) Repository.unmarkWatched(e.url) else Repository.markWatched(e.url) }) {
            Icon(Icons.Filled.Check, contentDescription = if (seen) "Desmarcar visto" else "Marcar como visto", tint = if (seen) Palette.Brand else Palette.TextFaint)
        }
    }
}
