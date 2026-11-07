package dev.guisousa.minhatv.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Ficha
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Show
import dev.guisousa.minhatv.data.UserLibrary

fun duracaoTxt(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

fun relogio(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

/** Fundo grande da ficha, sumindo no preto, com o conteúdo por cima. */
@Composable
private fun DetailsBackdrop(fundo: String?, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (fundo != null) {
            Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.75f).height(maxHeight * 0.75f)) {
                AsyncImage(fundo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Palette.Background, 0.5f to Color(0x99000000), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Palette.Background)))
            }
        }
        content()
    }
}

@Composable
private fun VersionPicker(versions: Set<String>, current: String, onPick: (String) -> Unit) {
    if (versions.size < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Versão", color = Palette.TextDim, fontSize = 13.sp)
        listOf(Repository.DUB, Repository.LEG).forEach { v -> Chip(Repository.versionName(v), current == v, { onPick(v) }, compact = true) }
    }
}

@Composable
private fun TitleBlock(title: String, d: Repository.Destaque?, ficha: Ficha?, subtitle: String) {
    Text(subtitle, color = Palette.Brand, fontSize = 12.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(title, color = Palette.Text, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(10.dp))
    if (d != null) MetaLine(d)
    Spacer(Modifier.height(12.dp))
    Text(ficha?.s ?: "", color = Palette.Synopsis, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieDetails(e: Entry) {
    val nav = LocalNav.current
    val user by UserLibrary.state.collectAsState()
    val d = remember(e.url) { Repository.destaque(e.url) }
    val ficha by produceState<Ficha?>(null, e.url) { value = Repository.fichaDe(e.url) }
    val versions = remember(e.url) { Repository.movieVersions(e) }
    var version by remember(e.url) { mutableStateOf(Repository.versionFor(e)) }
    val similares by produceState<List<Entry>>(emptyList(), e.url) { value = Repository.similarMovies(e) }
    var hero by remember { mutableStateOf<HeroItem?>(null) }
    val pos = user.progresso[e.url]
    val naLista = e.url in user.minhaLista
    val visto = e.url in user.jaAssisti
    DetailsBackdrop(d?.fundo ?: Repository.fundoUrl(ficha?.b)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Column(Modifier.fillMaxWidth(0.55f).padding(start = PagePad, top = 40.dp, bottom = 28.dp)) {
                    TitleBlock(e.name, d, ficha, "FILME · ${e.group}".uppercase())
                    Spacer(Modifier.height(22.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (pos != null && pos.pos > 0) {
                            PillButton("Continuar  ${relogio(pos.pos)}", { nav.play(e) }, Ic.Play, primary = true)
                            PillButton("Do início", { UserLibrary.limparPosicao(e.url); nav.play(e) }, Ic.Restart)
                        } else {
                            PillButton("Assistir", { nav.play(e) }, Ic.Play, primary = true)
                        }
                        PillButton(if (naLista) "Na minha lista" else "Minha lista", { UserLibrary.alternarLista(e.url) }, if (naLista) Ic.Check else Ic.Add)
                        PillButton(if (visto) "Já assisti" else "Marcar como visto", { UserLibrary.marcarAssistido(e.url, !visto) }, if (visto) Ic.Check else Ic.Eye)
                    }
                    Spacer(Modifier.height(16.dp))
                    VersionPicker(versions, version) { version = it; Repository.setVersionFor(e, it) }
                    if (pos != null && pos.dur > 0) {
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.width(320.dp)) { ProgressLine(pos.pos.toFloat() / pos.dur) }
                        Text("Faltam ${duracaoTxt(pos.dur - pos.pos)}", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (similares.isNotEmpty()) item {
                Shelf("Parecidos", similares, PosterW, key = { it.url }) { s -> ItemCard(s, { hero = heroOf(s) }) }
            }
        }
    }
}

@Composable
fun ShowDetails(s: Show) {
    val nav = LocalNav.current
    val user by UserLibrary.state.collectAsState()
    val ref = Repository.showRef(s)
    val d = remember(ref) { Repository.destaque(ref) }
    val eps by produceState<Repository.Episodes?>(null, s.key) { value = Repository.episodes(s) }
    val list = eps?.list.orEmpty()
    val seasons = remember(list) { list.mapNotNull { it.season }.distinct() }
    // episódio para "Continuar": o último aberto desta série, senão o primeiro não visto
    val recente = user.recentesVod.firstOrNull { r -> r.url.startsWith(ref + ":") && r.url !in user.jaAssisti }
    val alvo = recente?.let { r -> list.firstOrNull { it.url == r.url } } ?: list.firstOrNull { it.url !in user.jaAssisti } ?: list.firstOrNull()
    var season by remember(s.key) { mutableStateOf<Int?>(null) }
    val temporada = season ?: alvo?.season ?: seasons.firstOrNull()
    val naLista = ref in user.minhaLista
    var version by remember(s.key) { mutableStateOf(Repository.versionFor(Entry(s.name, "", type = dev.guisousa.minhatv.data.ContentType.SERIES, seriesName = s.name))) }
    DetailsBackdrop(d?.fundo ?: Repository.fundoUrl(eps?.info?.b)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Column(Modifier.fillMaxWidth(0.55f).padding(start = PagePad, top = 40.dp, bottom = 20.dp)) {
                    TitleBlock(s.name, d, eps?.info, "SÉRIE · ${s.group}".uppercase())
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (alvo != null) {
                            val cont = UserLibrary.posicao(alvo.url) > 0
                            PillButton("${if (cont) "Continuar" else "Assistir"} T${alvo.season} E${alvo.episode}", { nav.play(alvo) }, Ic.Play, primary = true)
                        }
                        PillButton(if (naLista) "Na minha lista" else "Minha lista", { UserLibrary.alternarLista(ref) }, if (naLista) Ic.Check else Ic.Add)
                    }
                    Spacer(Modifier.height(16.dp))
                    VersionPicker(eps?.versions.orEmpty(), version) { v ->
                        version = v
                        Repository.setVersionFor(Entry(s.name, "", type = dev.guisousa.minhatv.data.ContentType.SERIES, seriesName = s.name), v)
                    }
                }
            }
            when {
                eps == null -> item { Text("Carregando episódios…", color = Palette.TextDim, modifier = Modifier.padding(horizontal = PagePad)) }
                eps?.error != null -> item { Text(eps?.error ?: "", color = Palette.Error, modifier = Modifier.padding(horizontal = PagePad)) }
                else -> {
                    if (seasons.size > 1) item {
                        LazyRow(contentPadding = PaddingValues(horizontal = PagePad), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                            items(seasons) { t -> Chip(if (t == 0) "Especiais" else "Temporada $t", t == temporada, { season = t }) }
                        }
                    }
                    items(list.filter { it.season == temporada }, key = { it.url }) { ep ->
                        EpisodeRow(ep, user.progresso[ep.url]?.let { p -> if (p.dur > 0) p.pos.toFloat() / p.dur else null }, ep.url in user.jaAssisti,
                            onPlay = { nav.play(ep) }, onToggleSeen = { UserLibrary.marcarAssistido(ep.url, ep.url !in user.jaAssisti) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(e: Entry, progress: Float?, watched: Boolean, onPlay: () -> Unit, onToggleSeen: () -> Unit) {
    val (src, hovered) = rememberHover()
    Row(
        Modifier.fillMaxWidth(0.7f).padding(start = PagePad - 12.dp, bottom = 4.dp).clip(RoundedCornerShape(6.dp))
            .background(if (hovered) Color(0x22FFFFFF) else Color.Transparent).hoverable(src).pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(interactionSource = src, indication = null, onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${e.episode}", Modifier.width(44.dp), color = Palette.TextDim, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text("Episódio ${e.episode}", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (progress != null && !watched) { Spacer(Modifier.height(6.dp)); Box(Modifier.width(220.dp)) { ProgressLine(progress) } }
        }
        if (hovered) Icon(Ic.Play, null, Modifier.size(26.dp).padding(end = 6.dp), tint = Palette.Text)
        IconBtn(if (watched) Ic.Check else Ic.Eye, onToggleSeen, size = 34.dp, tint = if (watched) Palette.Brand else Palette.TextFaint)
    }
}
