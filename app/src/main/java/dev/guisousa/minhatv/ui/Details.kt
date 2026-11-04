package dev.guisousa.minhatv.ui

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Show
import dev.guisousa.minhatv.player.PlaybackQueue

// Telas de detalhes no estilo do Saimo TV: fundo com a capa, capa 152x228, tipo/título/gêneros,
// três botões de 52 de altura (Assistir/Continuar, Versão, Minha lista).

private fun clock(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
}

/** Fundo da tela de detalhes: a capa ampliada e escurecida, sumindo para o preto. */
@Composable
private fun Backdrop(image: String?) {
    if (image.isNullOrBlank()) return
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        AsyncImage(
            model = image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.28f),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Palette.Background))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Palette.Background, Color.Transparent))))
    }
}

@Composable
private fun BigButton(
    text: String,
    requester: FocusRequester? = null,
    key: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    // ao abrir a tela, o foco já começa no botão principal (Assistir/Continuar)
    if (requester != null) {
        var done by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) { if (!done) { delay(80); runCatching { requester.requestFocus() }; done = true } }
    }
    FocusBox(
        onClick = onClick, shape = RoundedCornerShape(6.dp), focusKey = key,
        modifier = Modifier.height(52.dp).then(if (requester != null) Modifier.focusRequester(requester) else Modifier),
    ) { focused ->
        Row(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                androidx.compose.material3.Icon(icon, null, tint = textOn(focused), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = textOn(focused), maxLines = 1)
        }
    }
}

/** Cabeçalho comum: capa, tipo, título, linha de informações e botões. */
@Composable
private fun DetailTop(kind: String, title: String, poster: String?, info: List<String>, overview: String? = null, buttons: @Composable () -> Unit) {
    Row(Modifier.padding(start = 48.dp, end = 48.dp, top = 20.dp)) {
        Logo(poster, Modifier.width(152.dp).height(228.dp).clip(RoundedCornerShape(6.dp)), fallback = title, crop = true)
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text(kind.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.TextDim, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp)
            Spacer(Modifier.height(8.dp))
            Text(info.filter { it.isNotBlank() }.joinToString("  ·  "), fontSize = 15.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!overview.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(overview, fontSize = 14.sp, color = Palette.TextDim, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.8f))
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
        }
    }
}

/** Botão "Versão": alterna Dublado/Legendado só para este título. */
@Composable
private fun VersionButton(e: Entry, available: Set<String>) {
    val context = LocalContext.current
    var v by remember(e.url) { mutableStateOf(Repository.versionFor(e)) }
    BigButton("Versão: ${Repository.versionName(v)}") {
        val next = if (v == Repository.DUB) Repository.LEG else Repository.DUB
        v = next
        Repository.setVersionFor(e, next)
        if (available.isNotEmpty() && next !in available) {
            android.widget.Toast.makeText(context, "Não achei ${Repository.versionName(next)} nesta lista; vai tocar o que tiver.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

// ======================= Filme =======================

@Composable
fun MovieDetailScreen(e: Entry, nav: Nav) {
    val context = LocalContext.current
    val favs by Repository.favorites.collectAsState()
    val watched by Repository.watched.collectAsState()
    val versions = load(e.url) { (listOf(e) + Repository.alternates(e)).mapNotNull { Repository.versionOf(it) }.toSet() }
    val resume = Repository.getResume(e.url)
    // capa, fundo e sinopse oficiais (TMDB), quando houver
    // procura pelo nome limpo (sem FHD, [L], Dublado...), senão o TMDB não acha e só sobra a capa esticada
    val tmdb = load("tmdb-m", e.name) { dev.guisousa.minhatv.data.Tmdb.lookup(dev.guisousa.minhatv.data.Normalize.displayCached(e.name).title, tv = false) }
    val extra = load("tmdb-x", e.name) { dev.guisousa.minhatv.data.Tmdb.extra(dev.guisousa.minhatv.data.Normalize.displayCached(e.name).title, tv = false) }
    // parecidos de verdade (recomendações do TMDB que existem na lista), e não a categoria em ordem alfabética
    val similar = load(e.url, extra?.related?.size ?: -1) { Repository.similarMovies(e, extra?.related.orEmpty()) }
    Box(Modifier.fillMaxSize()) {
        // só imagem horizontal de verdade (de preferência sem letreiro); a capa em pé esticada fica feia
        Backdrop(extra?.backdrop ?: tmdb?.backdrop)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item("top") {
                DetailTop(
                    "Filme", e.name, e.logo ?: tmdb?.poster,
                    listOfNotNull(e.group, e.quality, if (e.url.hashCode().toString() in watched) "Visto" else null),
                    overview = tmdb?.overview,
                ) {
                    BigButton(if (resume > 0) "Continuar de ${clock(resume)}" else "Assistir", remember { FocusRequester() }, key = "main:${e.url}", icon = androidx.compose.material.icons.Icons.Filled.PlayArrow) { PlaybackQueue.start(context, listOf(e), 0) }
                    VersionButton(e, versions.orEmpty())
                    BigButton(if (e.url in favs) "Na Minha lista" else "Minha lista", icon = if (e.url in favs) androidx.compose.material.icons.Icons.Filled.Check else androidx.compose.material.icons.Icons.Filled.Add) { toggleFav(context, e) }
                }
            }
            if (!similar.isNullOrEmpty()) item("similar") {
                Spacer(Modifier.height(20.dp))
                SectionTitle("Mais como este")
                MediaRow(similar, 136.dp) { _, m ->
                    PosterCard(m.name, m.logo, key = "sim:${m.url}", progress = Repository.progress(m.url), onClick = { nav.openMovie(m) }, onLongClick = { toggleFav(context, m) })
                }
            }
        }
    }
}

// ======================= Série =======================

@Composable
fun SeriesDetailScreen(show: Show) {
    val context = LocalContext.current
    val all = load(show.key) { Repository.episodes(show) }
    if (all == null) { Loading(); return }
    if (all.isEmpty()) { Message("Nenhum episódio encontrado"); return }
    val favs by Repository.favorites.collectAsState()
    val watched by Repository.watched.collectAsState()
    val seasons = remember(all) { all.groupBy { it.season ?: 0 }.toSortedMap().toList() }
    // fila do player: todas as temporadas em ordem (o próximo episódio passa para a temporada seguinte)
    val queue = remember(seasons) { seasons.filter { it.first != 0 }.flatMap { it.second } + seasons.filter { it.first == 0 }.flatMap { it.second } }
    // botão principal: continua de onde parou, ou o primeiro episódio ainda não visto
    val target = remember(queue, watched) {
        queue.firstOrNull { Repository.progress(it.url) != null }
            ?: queue.lastOrNull { Repository.isWatched(it.url) }?.let { last -> queue.getOrNull(queue.indexOf(last) + 1) }
            ?: queue.first()
    }
    var season by rememberSaveable(show.key) { mutableStateOf(target.season ?: seasons.first().first) }
    val episodes = seasons.firstOrNull { it.first == season }?.second.orEmpty()
    val seasonCount = seasons.count { it.first != 0 }
    val versions = remember(all) { all.mapNotNull { Repository.versionOf(it) }.toSet() }
    fun play(e: Entry) = PlaybackQueue.start(context, queue, queue.indexOfFirst { it.url == e.url }.coerceAtLeast(0))
    val ep = if (target.season != null && target.episode != null) " T${target.season} E${target.episode}" else ""

    val tmdb = load("tmdb-t", show.name) { dev.guisousa.minhatv.data.Tmdb.lookup(show.name, tv = true) }
    Box(Modifier.fillMaxSize()) {
        Backdrop(tmdb?.backdrop ?: show.logo)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item("top") {
                DetailTop(
                    "Série", show.name, tmdb?.poster ?: show.logo,
                    listOfNotNull(
                        if (seasonCount > 0) "$seasonCount temporada${if (seasonCount > 1) "s" else ""}" else null,
                        "${all.size} episódios",
                        show.group,
                    ),
                    overview = tmdb?.overview,
                ) {
                    BigButton((if (Repository.progress(target.url) != null) "Continuar" else "Assistir") + ep, remember { FocusRequester() }, key = "main:${show.key}", icon = androidx.compose.material.icons.Icons.Filled.PlayArrow) { play(target) }
                    VersionButton(target, versions)
                    BigButton(if (target.url in favs) "Na Minha lista" else "Minha lista", icon = if (target.url in favs) androidx.compose.material.icons.Icons.Filled.Check else androidx.compose.material.icons.Icons.Filled.Add) { toggleFav(context, target) }
                }
            }
            if (seasons.size > 1) item("seasons") {
                Spacer(Modifier.height(24.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons) { (s, _) ->
                        TvButton(if (s == 0) "Extras" else "Temporada $s", selected = s == season, onClick = { season = s })
                    }
                }
            }
            item("episodes:$season") {
                Spacer(Modifier.height(12.dp))
                MediaRow(episodes, 300.dp) { i, e ->
                    EpisodeCard(e, i, show.logo, e.url.hashCode().toString() in watched) { play(e) }
                }
            }
        }
    }
}

/** Episódio em cartão largo: número grande, imagem, "Visto", progresso, título e duração. */
@Composable
private fun EpisodeCard(e: Entry, i: Int, fallbackImage: String?, seen: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val progress = Repository.progress(e.url)
    val duration = Repository.duration(e.url)
    Column {
        FocusBox(
            onClick = onClick, onLongClick = { toggleFav(context, e) }, frame = true, focusScale = 1.05f,
            modifier = Modifier.width(300.dp).height(162.dp), focusKey = "e:${e.url}",
        ) {
            Logo(e.logo ?: fallbackImage, Modifier.fillMaxSize().alpha(0.55f), fallback = e.name, crop = true)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
            Text(
                (e.episode ?: (i + 1)).toString(),
                Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 10.dp),
                fontSize = 44.sp, fontWeight = FontWeight.Black, color = Palette.Text,
            )
            if (seen) {
                Text(
                    "Visto", Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .background(Color(0xE6000000), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text,
                )
            }
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart))
        }
        Text(dev.guisousa.minhatv.data.Normalize.episodeTitle(e.name, e.seriesName, e.episode), Modifier.padding(top = 8.dp).width(300.dp), fontSize = 14.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                if (e.season != null && e.episode != null) "T${e.season} E${e.episode}" else null,
                if (duration > 0) "${(duration / 60_000).coerceAtLeast(1)} min" else null,
                e.quality,
            ).joinToString("  ·  "),
            fontSize = 12.sp, color = Palette.TextFaint, maxLines = 1,
        )
    }
}
