package dev.guisousa.minhatv.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Programme
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Settings
import dev.guisousa.minhatv.data.UserLibrary
import dev.guisousa.minhatv.desktop.player.VideoView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

private val hhmm = SimpleDateFormat("HH:mm")
fun hora(ms: Long): String = hhmm.format(Date(ms))

private const val RECENT = "\u0000recentes"

/** Ao vivo: categorias | canais com o que está passando | prévia tocando. */
@Composable
fun LiveScreen() {
    val nav = LocalNav.current
    val player = nav.player
    val lib by Repository.library.collectAsState()
    val epg by Repository.epg.collectAsState()
    val user by UserLibrary.state.collectAsState()
    val status by Repository.status.collectAsState()
    if (lib.isEmpty) { CenterMessage(status ?: "Carregando…"); return }
    val temRecentes = user.recentesAoVivo.isNotEmpty()
    var group by remember { mutableStateOf<String?>(null) }
    if (group == null) group = if (temRecentes) RECENT else lib.liveGroups.firstOrNull()?.name
    val channels by produceState<List<Entry>>(emptyList(), group, lib.version) {
        value = if (group == RECENT) UserLibrary.recentesAoVivo() else Repository.entries(ContentType.LIVE, group)
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    // abre no último canal, se a pessoa pediu isso nos ajustes
    LaunchedEffect(Unit) {
        if (player.item == null && Settings.value.abrirNoUltimoCanal) Settings.value.ultimoCanal?.let { Repository.itemOf(it) as? Entry }?.let { player.open(it) }
    }
    val selected = player.item?.takeIf { it.type == ContentType.LIVE }

    Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
        // categorias
        val catState = rememberLazyListState()
        LazyColumn(Modifier.width(230.dp).fillMaxHeight(), state = catState, contentPadding = PaddingValues(start = 24.dp, end = 8.dp, bottom = 24.dp)) {
            if (temRecentes) item { CatRow("Vistos por último", null, group == RECENT) { group = RECENT } }
            items(lib.liveGroups) { g -> CatRow(g.name, g.count, group == g.name) { group = g.name } }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Hairline))
        // canais
        val listState = rememberLazyListState()
        LaunchedEffect(group) { listState.scrollToItem(0) }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(channels, key = { it.url }) { e ->
                ChannelRow(e, epg.current(e, now), now, e.url == selected?.url, onClick = { if (player.item?.url != e.url) player.open(e) }, onDouble = { nav.play(e) })
            }
        }
        // prévia
        Column(Modifier.weight(1.15f).fillMaxHeight().padding(start = 12.dp, end = PagePad, bottom = 24.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.Surface)) {
                if (selected != null) {
                    VideoView(player, Modifier.fillMaxSize(), onDoubleClick = { nav.play(selected) })
                } else {
                    Text("Clique num canal para ver aqui.\nDois cliques abre em tela cheia.", Modifier.align(Alignment.Center), color = Palette.TextDim, fontSize = 14.sp)
                }
            }
            if (selected != null) {
                Spacer(Modifier.height(14.dp))
                Text(selected.name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val prog = epg.forEntry(selected).orEmpty().filter { it.stop > now }.take(4)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Tela cheia", { nav.play(selected) }, Ic.Fullscreen, primary = true)
                    if (player.sources.size > 1) PillButton("Fonte ${player.sourceIndex + 1}/${player.sources.size}", { player.nextSource() }, Ic.Source)
                    val naLista = selected.url in user.minhaLista
                    PillButton(if (naLista) "Na lista" else "Minha lista", { UserLibrary.alternarLista(selected.url) }, if (naLista) Ic.Check else Ic.Add)
                }
                Spacer(Modifier.height(16.dp))
                if (prog.isEmpty()) Text("Sem guia de programação para este canal.", color = Palette.TextFaint, fontSize = 13.sp)
                prog.forEachIndexed { i, p -> ProgrammeLine(p, now, i == 0) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatRow(name: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val (src, hovered) = rememberHover()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (selected) Palette.Focus else if (hovered) Color(0x22FFFFFF) else Color.Transparent)
            .hoverable(src).pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) Palette.OnFocus else Palette.Text
        Text(name, Modifier.weight(1f), color = fg, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("$count", color = if (selected) Palette.OnFocus.copy(alpha = 0.6f) else Palette.TextFaint, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(e: Entry, now: Programme?, nowMs: Long, selected: Boolean, onClick: () -> Unit, onDouble: () -> Unit) {
    val (src, hovered) = rememberHover()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0x33FFFFFF) else if (hovered) Color(0x1AFFFFFF) else Color.Transparent)
            .hoverable(src).pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onDoubleClick = onDouble)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp, 42.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Surface)) {
            NetImage(e.logo, Modifier.fillMaxSize().padding(4.dp), crop = false)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Palette.Live)); Spacer(Modifier.width(6.dp)) }
                Text(e.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (now != null) {
                Text(now.title, color = Palette.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val f = ((nowMs - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.width(140.dp)) { ProgressLine(f) }
            }
        }
        if (e.quality != null) Tag(e.quality)
    }
}

@Composable
private fun ProgrammeLine(p: Programme, now: Long, current: Boolean) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${hora(p.start)} – ${hora(p.stop)}", color = if (current) Palette.Live else Palette.TextFaint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (current) Text("   AGORA", color = Palette.Live, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        Text(p.title, color = if (current) Palette.Text else Palette.TextDim, fontSize = if (current) 16.sp else 14.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (current && !p.desc.isNullOrBlank()) Text(p.desc, color = Palette.TextFaint, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
    }
}
