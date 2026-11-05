@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.guisousa.minhatv.ui

import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Show
import dev.guisousa.minhatv.data.Updater
import dev.guisousa.minhatv.player.PlaybackQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var autoplayDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repository.init(this)
        autoplayDone = savedInstanceState != null
        setContent { MinhaTvTheme { AppRoot(::maybeAutoplay) } }
    }

    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        // voltou do player: devolve o foco ao item que estava selecionado
        if (resumedOnce) FocusMemory.restore.intValue++
        resumedOnce = true
        Repository.refreshIfChanged()
        checkUpdate(force = false)
    }

    /** Confere atualização do app (no máximo a cada 3 horas, ou na hora se [force]). */
    fun checkUpdate(force: Boolean) {
        val prefs = getSharedPreferences("minhatv", MODE_PRIVATE)
        val last = prefs.getLong("last_update_check", 0)
        if (!force && System.currentTimeMillis() - last < 3 * 3_600_000L) return
        prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
        lifecycleScope.launch {
            val r = Updater.check()
            if (r != null && !isFinishing) Updater.offer(this@MainActivity, r) { block -> lifecycleScope.launch { block() } }
            else if (force) Toast.makeText(this@MainActivity, "Você já está na versão mais nova (${Updater.currentName}).", Toast.LENGTH_SHORT).show()
        }
    }

    /** Se ligado nos ajustes, abre direto no último canal assistido. */
    private fun maybeAutoplay() {
        if (autoplayDone) return
        autoplayDone = true
        if (!Repository.autoplayLast) return
        lifecycleScope.launch {
            val (list, i) = Repository.lastChannelQueue() ?: return@launch
            PlaybackQueue.start(this@MainActivity, list, i)
        }
    }
}

enum class Tab(val title: String) {
    HOME("Início"), LIVE("Ao vivo"), MOVIES("Filmes"), SERIES("Séries"), MYLIST("Minha lista"), SEARCH("Buscar"), SETTINGS("Ajustes")
}

/** Telas abertas por cima das abas (voltar fecha a de cima). */
sealed class Detail {
    data class OfShow(val show: Show) : Detail()
    data class OfMovie(val entry: dev.guisousa.minhatv.data.Entry) : Detail()
    data class Category(val type: ContentType, val group: String) : Detail()
}

/** Lê o status sozinho: assim só este texto se redesenha durante a importação, não a tela toda. */
@Composable
private fun StatusText() {
    val status by Repository.status.collectAsState()
    androidx.compose.animation.AnimatedVisibility(
        visible = status != null,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner(12.dp)
            Spacer(Modifier.width(8.dp))
            Text(status.orEmpty(), fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
        }
    }
}

@Composable
fun AppRoot(onLoaded: () -> Unit) {
    val context = LocalContext.current
    val loading by Repository.isLoading.collectAsState()
    val library by Repository.library.collectAsState()
    val status by Repository.status.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // telas abertas por cima: guardadas mesmo se o Android fechar o app no fundo enquanto o vídeo toca
    val stack = rememberSaveable(saver = detailSaver) { mutableStateListOf<Detail>() }
    val tabRequesters = remember { Tab.entries.map { FocusRequester() } }
    val firstTab = tabRequesters[0]

    LaunchedEffect(loading) {
        if (!loading) {
            onLoaded()
            runCatching { firstTab.requestFocus() }
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); FocusMemory.pop() }
    // nas abas: VOLTAR vai para o Início; no Início, precisa apertar de novo para sair do app
    var lastBack by remember { mutableStateOf(0L) }
    BackHandler(enabled = stack.isEmpty()) {
        val now = System.currentTimeMillis()
        when {
            tab != Tab.HOME.ordinal -> { tab = Tab.HOME.ordinal; runCatching { firstTab.requestFocus() } }
            now - lastBack < 4000 -> (context as? android.app.Activity)?.finish()
            else -> {
                lastBack = now
                Toast.makeText(context, "Aperte VOLTAR de novo para sair", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val nav = Nav(
        openShow = { FocusMemory.push(); stack.add(Detail.OfShow(it)) },
        openMovie = { FocusMemory.push(); stack.add(Detail.OfMovie(it)) },
        openCategory = { type, group -> FocusMemory.push(); stack.add(Detail.Category(type, group)) },
    )

    val top = stack.lastOrNull()
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Box(Modifier.fillMaxSize()) {
            // guarda o estado de cada tela (termo da busca, rolagem, temporada...) quando ela sai da frente,
            // para o Voltar devolver exatamente onde você estava
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            when {
                loading -> Box(Modifier.padding(top = TOP_BAR)) { Message("Carregando…") }
                library.isEmpty && Tab.entries[tab] != Tab.SETTINGS -> Box(Modifier.padding(top = TOP_BAR)) { Message(status ?: "Baixando o catálogo…") }
                else -> {
                // a aba continua montada (invisível e sem foco) por baixo dos detalhes: ao voltar,
                // a lista, a busca e a rolagem estão do mesmo jeito e o foco volta para o cartão
                androidx.compose.runtime.CompositionLocalProvider(LocalFocusEnabled provides (top == null)) {
                Box(Modifier.fillMaxSize().placedIf(top == null)) {
                // Início, Ao vivo, Filmes e Séries ficam montadas depois da primeira visita (invisíveis e sem foco):
                // voltar para elas é na hora, sem recarregar as fileiras nem as imagens
                val cur = Tab.entries[tab]
                val kept = remember { mutableSetOf<Tab>() }
                if (cur in KEEP) kept += cur
                Tab.entries.filter { it == cur || it in kept }.forEach { t ->
                    androidx.compose.runtime.key(t) {
                    val on = t == cur
                    androidx.compose.runtime.CompositionLocalProvider(LocalFocusEnabled provides (top == null && on)) {
                    // Início, Filmes, Séries e Ao vivo vão até o topo (o menu fica por cima); o resto começa abaixo do menu
                    Box(Modifier.fillMaxSize().placedIf(on).padding(top = if (t in FULL_BLEED) 0.dp else TOP_BAR)) {
                    holder.SaveableStateProvider("tab:${t.name}") { when (t) {
                        Tab.HOME -> HomeScreen(library, nav)
                        Tab.LIVE -> LiveScreen(library)
                        Tab.MOVIES -> RowsScreen(library, ContentType.MOVIE, nav)
                        Tab.SERIES -> RowsScreen(library, ContentType.SERIES, nav)
                        Tab.MYLIST -> MyListScreen(library, nav)
                        Tab.SEARCH -> SearchScreen(library, nav)
                        Tab.SETTINGS -> SettingsScreen()
                    } }
                    }
                    }
                    }
                }
                }
                }
                // as telas de baixo da pilha (ex.: a grade "Todos de A a Z" sob os detalhes) também continuam
                // montadas: ao voltar, a grade está na mesma rolagem e o foco volta para a capa que você abriu
                stack.forEachIndexed { i, d ->
                    androidx.compose.runtime.key(i, d) {
                        val isTop = i == stack.lastIndex
                        androidx.compose.runtime.CompositionLocalProvider(LocalFocusEnabled provides isTop) {
                            Box(Modifier.fillMaxSize().placedIf(isTop).background(Palette.Background).padding(top = TOP_BAR)) {
                                when (d) {
                                    is Detail.OfShow -> holder.SaveableStateProvider("show:${d.show.key}") { SeriesDetailScreen(d.show) }
                                    is Detail.OfMovie -> holder.SaveableStateProvider("movie:${d.entry.url}") { MovieDetailScreen(d.entry, nav) }
                                    is Detail.Category -> holder.SaveableStateProvider("cat:${d.type}:${d.group}") { CategoryScreen(library, d.type, d.group, nav) }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                // o menu fica por cima da tela (o fundo do destaque passa por trás dele)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xE6000000), androidx.compose.ui.graphics.Color.Transparent)))
                // subindo da tela para o menu, o foco cai na aba aberta (e não sempre no Início)
                .focusProperties { enter = { tabRequesters[tab] } }
                .focusGroup()
                .padding(start = 48.dp, end = 48.dp, top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BrandMark()
            Spacer(Modifier.width(28.dp))
            Tab.entries.forEachIndexed { i, t ->
                if (t == Tab.SEARCH || t == Tab.SETTINGS) return@forEachIndexed
                NavItem(
                    t.title,
                    selected = tab == i,
                    onClick = { tab = i; stack.clear(); FocusMemory.clear() },
                    modifier = Modifier.focusRequester(tabRequesters[i]),
                )
            }
            Spacer(Modifier.weight(1f))
            StatusText()
            Spacer(Modifier.width(12.dp))
            // busca (lupa) e ajustes (engrenagem) como ícones, à direita
            IconNav(androidx.compose.material.icons.Icons.Filled.Search, "Buscar", selected = Tab.entries[tab] == Tab.SEARCH, modifier = Modifier.focusRequester(tabRequesters[Tab.SEARCH.ordinal])) { tab = Tab.SEARCH.ordinal; stack.clear(); FocusMemory.clear() }
            IconNav(androidx.compose.material.icons.Icons.Filled.Settings, "Ajustes", selected = Tab.entries[tab] == Tab.SETTINGS, modifier = Modifier.focusRequester(tabRequesters[Tab.SETTINGS.ordinal])) { tab = Tab.SETTINGS.ordinal; stack.clear(); FocusMemory.clear() }
        }

    }
}

private val TOP_BAR = 64.dp
private val FULL_BLEED = setOf(Tab.HOME, Tab.LIVE, Tab.MOVIES, Tab.SERIES, Tab.MYLIST, Tab.SEARCH)
/** Abas que ficam montadas depois da primeira visita (trocar de aba é na hora). */
private val KEEP = setOf(Tab.HOME, Tab.LIVE, Tab.MOVIES, Tab.SERIES, Tab.SEARCH)

/** Marca no canto do menu: lobo + BAD WOLF TV (TV no ciano do W4LLE). */
@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(dev.guisousa.minhatv.R.drawable.logo_lobo), contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text("BAD WOLF", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp), color = Palette.Text)
        Spacer(Modifier.width(6.dp))
        Text("TV", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp), color = Palette.Brand)
    }
}

private val detailJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Guarda a pilha de telas como texto (sobrevive se o Android recriar a tela do app). */
private val detailSaver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Detail>, String>(
    save = { list ->
        list.map { d ->
            when (d) {
                is Detail.OfShow -> "s" + detailJson.encodeToString(Show.serializer(), d.show)
                is Detail.OfMovie -> "m" + detailJson.encodeToString(dev.guisousa.minhatv.data.Entry.serializer(), d.entry)
                is Detail.Category -> "c" + d.type.ordinal + "|" + d.group
            }
        }
    },
    restore = { saved ->
        val out = mutableStateListOf<Detail>()
        saved.forEach { s ->
            runCatching {
                when (s[0]) {
                    's' -> Detail.OfShow(detailJson.decodeFromString(Show.serializer(), s.substring(1)))
                    'm' -> Detail.OfMovie(detailJson.decodeFromString(dev.guisousa.minhatv.data.Entry.serializer(), s.substring(1)))
                    else -> s.substring(1).let { r -> Detail.Category(ContentType.entries[r.substringBefore('|').toInt()], r.substringAfter('|')) }
                }
            }.getOrNull()?.let { out += it }
        }
        out
    },
)

class Nav(val openShow: (Show) -> Unit, val openMovie: (dev.guisousa.minhatv.data.Entry) -> Unit, val openCategory: (ContentType, String) -> Unit)

@Composable
private fun IconNav(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier, focusScale = 1f, background = if (selected) Palette.SurfaceHigh else androidx.compose.ui.graphics.Color.Transparent, shape = RoundedCornerShape(50)) { focused ->
        androidx.compose.material3.Icon(
            icon, contentDescription = description,
            tint = if (focused) Palette.OnFocus else if (selected) Palette.Text else Palette.TextDim,
            modifier = Modifier.padding(9.dp).size(22.dp),
        )
    }
}

@Composable
private fun NavItem(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(onClick = onClick, modifier = modifier, focusScale = 1f, background = androidx.compose.ui.graphics.Color.Transparent, shape = RoundedCornerShape(4.dp)) { focused ->
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                color = if (focused) Palette.OnFocus else if (selected) Palette.Text else Palette.TextDim,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.width(18.dp).height(2.dp).background(
                    if (selected && !focused) Palette.Brand else androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
    }
}

/**
 * Mede a tela escondida (ela continua pronta) mas não coloca ela na tela: assim ela não desenha
 * e a busca de foco do controle não entra nas listas dela (antes o app travava montando milhares de itens).
 */
private fun Modifier.placedIf(show: Boolean): Modifier = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) { if (show) p.place(0, 0) }
}
