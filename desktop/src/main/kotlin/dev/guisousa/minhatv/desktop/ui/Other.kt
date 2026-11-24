package dev.guisousa.minhatv.desktop.ui

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.guisousa.minhatv.data.Conta
import dev.guisousa.minhatv.data.Nuvem
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.minhatv.data.AppDirs
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Settings
import dev.guisousa.minhatv.data.SourceHealth
import dev.guisousa.minhatv.data.UserLibrary
import kotlinx.coroutines.delay

// ---------- Busca ----------

/** Texto da busca guardado fora da tela (volta igual depois de abrir um resultado). */
object SearchState { var query by mutableStateOf("") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen() {
    val user by UserLibrary.state.collectAsState()
    val lib by Repository.library.collectAsState()
    var q by remember { mutableStateOf(SearchState.query) }
    var hero by remember { mutableStateOf<HeroItem?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val result by produceState<Repository.SearchResult?>(null, q, lib.version) {
        SearchState.query = q
        if (q.isBlank()) { value = null; return@produceState }
        delay(250)
        value = Repository.search(q)
        if (q.trim().length >= 3) { delay(1500); UserLibrary.adicionarBusca(q) }
    }
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Row(
            Modifier.padding(horizontal = PagePad).widthIn(max = 720.dp).fillMaxWidth().background(Palette.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, Palette.Hairline, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Search, null, Modifier.size(24.dp), tint = Palette.TextDim)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Filme, série ou canal…", color = Palette.TextFaint, fontSize = 18.sp)
                BasicTextField(
                    q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 18.sp), cursorBrush = SolidColor(Palette.Brand),
                )
            }
            if (q.isNotEmpty()) IconBtn(Ic.Close, { q = "" }, size = 30.dp, tint = Palette.TextDim)
        }
        val r = result
        when {
            q.isBlank() -> Column(Modifier.padding(PagePad)) {
                if (user.buscas.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Buscas recentes", color = Palette.TextDim, fontSize = 14.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("limpar", color = Palette.Brand, fontSize = 13.sp, modifier = Modifier.padding(4.dp).clickableText { UserLibrary.limparBuscas() })
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        user.buscas.forEach { b -> Chip(b, false, { q = b }) }
                    }
                } else Text("Digite para buscar em filmes, séries e canais.", color = Palette.TextDim, fontSize = 15.sp)
            }
            r == null -> CenterMessage("Buscando…")
            r.movies.isEmpty() && r.shows.isEmpty() && r.channels.isEmpty() -> CenterMessage("Nada encontrado para \"$q\".")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 22.dp, bottom = 40.dp)) {
                if (r.movies.isNotEmpty()) item { Shelf("Filmes  ·  ${r.movies.size}", r.movies, PosterW, key = { it.url }) { ItemCard(it, { hero = heroOf(it) }) } }
                if (r.shows.isNotEmpty()) item { Shelf("Séries  ·  ${r.shows.size}", r.shows, PosterW, key = { it.key }) { ItemCard(it, { hero = heroOf(it) }) } }
                if (r.channels.isNotEmpty()) item { Shelf("Canais  ·  ${r.channels.size}", r.channels, ChannelW, key = { it.url }) { ItemCard(it, { hero = heroOf(it) }) } }
            }
        }
    }
}

private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    this.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)

// ---------- Minha lista ----------

@Composable
fun MyListScreen() {
    val user by UserLibrary.state.collectAsState()
    val lib by Repository.library.collectAsState()
    var hero by remember { mutableStateOf<HeroItem?>(null) }
    var aba by rememberSaveable { mutableStateOf(0) }
    val lista = remember(user.minhaLista, lib.version) { user.minhaLista.mapNotNull { Repository.itemOf(it) } }
    val vistos = remember(user.jaAssisti, lib.version) {
        user.jaAssisti.entries.sortedByDescending { it.value }.mapNotNull { Repository.itemOf(it.key) }
    }
    val itens = if (aba == 0) lista else vistos
    if (hero == null) itens.firstOrNull()?.let { hero = heroOf(it) }
    CinemaPage(hero, headerFraction = 0.36f) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = PagePad, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Minha lista  ${lista.size}", aba == 0, { aba = 0; hero = null })
                Chip("Já assisti  ${vistos.size}", aba == 1, { aba = 1; hero = null })
            }
            if (itens.isEmpty()) CenterMessage(if (aba == 0) "Sua lista está vazia.\nNa página de um filme ou série, clique em \"Minha lista\"." else "Nada marcado como visto ainda.")
            else PosterGrid(itens) { hero = heroOf(it) }
        }
    }
}

// ---------- Ajustes ----------

@Composable
fun SettingsScreen() {
    val s by Settings.state.collectAsState()
    val status by Repository.status.collectAsState()
    var limpou by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = PagePad, vertical = 24.dp)) {
        item {
            Column(Modifier.widthIn(max = 760.dp)) {
                Text("Ajustes", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Text)
                Spacer(Modifier.height(24.dp))
                ContaGroup()
                Group("Reprodução") {
                    Line("Versão preferida", "Usada quando o título tem dublado e legendado.") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Dublado", s.versaoPreferida == Repository.DUB, { Settings.edit { it.copy(versaoPreferida = Repository.DUB) } })
                            Chip("Legendado", s.versaoPreferida == Repository.LEG, { Settings.edit { it.copy(versaoPreferida = Repository.LEG) } })
                        }
                    }
                    Line("Próximo episódio sozinho", "Ao terminar um episódio, começa o seguinte.") {
                        Toggle(s.proximoEpisodio) { v -> Settings.edit { it.copy(proximoEpisodio = v) } }
                    }
                    Line("Abrir no último canal", "Ao entrar em Ao vivo, já toca o último canal visto.") {
                        Toggle(s.abrirNoUltimoCanal) { v -> Settings.edit { it.copy(abrirNoUltimoCanal = v) } }
                    }
                    Line("Volume inicial", "${s.volume}%") {
                        Slider(
                            s.volume.toFloat(), { v -> Settings.edit { it.copy(volume = v.toInt()) } }, Modifier.width(220.dp), valueRange = 0f..150f,
                            colors = SliderDefaults.colors(thumbColor = Palette.Text, activeTrackColor = Palette.Brand, inactiveTrackColor = Palette.SurfaceHigh),
                        )
                    }
                }
                Group("Catálogo") {
                    Line("Atualizar agora", status ?: "Versão do catálogo: ${Repository.versaoCatalogo.ifBlank { "—" }}. Ele também se atualiza sozinho.") {
                        PillButton("Atualizar", { Repository.refreshAllAsync() }, Ic.Restart)
                    }
                    Line("Esquecer fontes", if (limpou) "Pronto." else "Apaga a memória de quais links tocam ou falham neste computador.") {
                        PillButton("Limpar", { SourceHealth.clear(); limpou = true })
                    }
                    Line("Limpar buscas recentes", null) { PillButton("Limpar", { UserLibrary.limparBuscas() }) }
                }
                Group("Sobre") {
                    val updAvail by dev.guisousa.minhatv.data.Updater.available.collectAsState()
                    val updStatus by dev.guisousa.minhatv.data.Updater.status.collectAsState()
                    val updBusy by dev.guisousa.minhatv.data.Updater.busy.collectAsState()
                    Line(
                        "Bad Wolf TV para Windows",
                        updStatus ?: "Versão ${dev.guisousa.minhatv.data.Updater.currentVersion} · player VLC embutido",
                    ) {
                        if (updAvail != null) {
                            PillButton(
                                if (updBusy) "Baixando…" else "Atualizar para ${updAvail!!.name}",
                                { dev.guisousa.minhatv.data.Updater.installNow() },
                                Ic.Restart,
                                primary = true,
                            )
                        } else {
                            PillButton("Verificar atualização", { dev.guisousa.minhatv.data.Updater.checkAsync(manual = true) }, Ic.Restart)
                        }
                    }
                    Line("Seus dados", AppDirs.data.absolutePath) {
                        PillButton("Abrir pasta", { runCatching { java.awt.Desktop.getDesktop().open(AppDirs.data.also { it.mkdirs() }) } })
                    }
                    Line("Registro (para suporte)", java.io.File(AppDirs.cache, "log.txt").absolutePath) {
                        PillButton("Abrir pasta", { runCatching { java.awt.Desktop.getDesktop().open(AppDirs.cache.also { it.mkdirs() }) } })
                    }
                }
            }
        }
    }
}

// ---------- Conta W4LLE ----------

/** true enquanto um campo de texto dos Ajustes tem o foco (os atalhos de teclado do app ficam quietos). */
@Volatile var campoDeTextoFocado = false

@Composable
private fun ContaGroup() {
    val u by Conta.usuario.collectAsState()
    val ultima by Nuvem.ultima.collectAsState()
    val sincronizando by Nuvem.sincronizando.collectAsState()
    Group("Conta W4LLE") {
        val usuario = u
        if (usuario != null) {
            val sub = when {
                sincronizando -> "Sincronizando…"
                ultima > 0 -> "Sincronizado às ${horaDe(ultima)}. Onde parou, Minha lista e Já assisti ficam iguais na TV, no celular e no site."
                else -> "Onde parou, Minha lista e Já assisti ficam iguais na TV, no celular e no site."
            }
            Line("Conectado como ${usuario.nome.ifBlank { "você" }}", sub) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Sincronizar", { Nuvem.sincronizar(forcar = true) }, Ic.Restart, enabled = !sincronizando)
                    PillButton("Sair", { Conta.sair() })
                }
            }
        } else {
            LoginForm()
        }
    }
}

private fun horaDe(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

@Composable
private fun LoginForm() {
    var nome by rememberSaveable { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var entrando by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun entrar() {
        if (entrando) return
        entrando = true
        erro = null
        scope.launch {
            erro = Conta.entrarComSenha(nome, senha)
            entrando = false
            if (erro == null) senha = ""
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text("Entre com a sua conta W4LLE", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "A mesma do site, da TV e do celular. Assistiu num, continua no outro do mesmo ponto; Minha lista e Já assisti também.",
            color = Palette.TextDim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Campo(nome, { nome = it; erro = null }, "Usuário", Modifier.weight(1f), onEnter = ::entrar)
            Campo(senha, { senha = it; erro = null }, "Senha", Modifier.weight(1f), senha = true, onEnter = ::entrar)
            PillButton(if (entrando) "Entrando…" else "Entrar", ::entrar, primary = true, enabled = !entrando)
        }
        erro?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Palette.Error, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Campo(
    valor: String, onChange: (String) -> Unit, dica: String, modifier: Modifier = Modifier,
    senha: Boolean = false, onEnter: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    var focado by remember { mutableStateOf(false) }
    Box(
        modifier.height(44.dp).background(Palette.SurfaceHigh, RoundedCornerShape(22.dp))
            .border(1.dp, if (focado) Color(0x66FFFFFF) else Palette.Hairline, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (valor.isEmpty()) Text(dica, color = Palette.TextFaint, fontSize = 15.sp)
        BasicTextField(
            valor, onChange,
            Modifier.fillMaxWidth()
                .onFocusChanged { focado = it.isFocused; campoDeTextoFocado = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Enter, Key.NumPadEnter -> { onEnter(); true }
                        Key.Tab -> { focus.moveFocus(if (ev.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true }
                        else -> false
                    }
                },
            singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp), cursorBrush = SolidColor(Palette.Text),
            visualTransformation = if (senha) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), color = Palette.Brand, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(10.dp)).padding(vertical = 4.dp)) { content() }
    Spacer(Modifier.height(26.dp))
}

@Composable
private fun Line(title: String, subtitle: String?, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Palette.TextDim, fontSize = 13.sp)
        }
        Spacer(Modifier.width(16.dp))
        control()
    }
}

@Composable
private fun Toggle(v: Boolean, onChange: (Boolean) -> Unit) {
    Switch(v, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Brand, checkedThumbColor = Color.White, uncheckedTrackColor = Palette.SurfaceHigh, uncheckedThumbColor = Palette.TextDim, uncheckedBorderColor = Color.Transparent))
}
