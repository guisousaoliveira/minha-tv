package dev.guisousa.minhatv.ui

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.PhoneSetupServer
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

    override fun onResume() {
        super.onResume()
        Repository.refreshIfStale()
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
    val playlists by Repository.playlists.collectAsState()
    val sources by Repository.sources.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stack = remember { mutableStateListOf<Detail>() }
    val firstTab = remember { FocusRequester() }

    LaunchedEffect(loading) {
        if (!loading) {
            onLoaded()
            runCatching { firstTab.requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30 * 60_000L)
            Repository.refreshIfStale()
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    val nav = Nav(
        openShow = { stack.add(Detail.OfShow(it)) },
        openCategory = { type, group -> stack.add(Detail.Category(type, group)) },
    )
    val nothingAdded = !loading && playlists.isEmpty() && sources.isEmpty()

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "MINHA TV",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp),
                color = Palette.Text,
            )
            Spacer(Modifier.width(28.dp))
            Tab.entries.forEachIndexed { i, t ->
                NavItem(
                    t.title,
                    selected = tab == i,
                    onClick = { tab = i; stack.clear() },
                    modifier = if (i == 0) Modifier.focusRequester(firstTab) else Modifier,
                )
            }
            Spacer(Modifier.weight(1f))
            StatusText()
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val top = stack.lastOrNull()
            // guarda o estado de cada tela (termo da busca, rolagem, temporada...) quando ela sai da frente,
            // para o Voltar devolver exatamente onde você estava
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            when {
                loading -> Message("Carregando…")
                nothingAdded && Tab.entries[tab] != Tab.SETTINGS -> Welcome(onTyped = { url, name, epg -> addLink(context, url, name, epg) })
                top is Detail.OfShow -> holder.SaveableStateProvider("show:${top.show.key}") { SeriesDetailScreen(top.show) }
                top is Detail.Category -> holder.SaveableStateProvider("cat:${top.type}:${top.group}") { CategoryScreen(library, top.type, top.group, nav) }
                // troca de aba com um fade rápido
                else -> androidx.compose.animation.Crossfade(Tab.entries[tab], animationSpec = androidx.compose.animation.core.tween(180), label = "tab") { t ->
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

class Nav(val openShow: (Show) -> Unit, val openCategory: (ContentType, String) -> Unit)

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
                    if (selected && !focused) Palette.Text else androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
    }
}

fun addLink(context: android.content.Context, url: String, name: String?, epg: String?) {
    if (url.isBlank()) return
    Toast.makeText(context, "Carregando o link…", Toast.LENGTH_SHORT).show()
    Repository.addFromUrl(url, name, epg) { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
}

/** Primeira abertura: QR Code para configurar pelo celular. */
@Composable
fun Welcome(onTyped: (String, String?, String?) -> Unit) {
    val context = LocalContext.current
    val address = rememberPhoneSetupServer()
    Row(Modifier.fillMaxSize().padding(horizontal = 96.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Vamos colocar os canais", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.height(16.dp))
            Text(
                "1. Conecte o celular no mesmo Wi-Fi da TV.\n" +
                    "2. Aponte a câmera para o código ao lado.\n" +
                    "3. Cole o link da playlist, da página ou do repositório do GitHub.",
                fontSize = 18.sp, color = Palette.TextDim, lineHeight = 30.sp,
            )
            Spacer(Modifier.height(28.dp))
            TvButton("Digitar o link com o controle", onClick = { showAddLinkDialog(context, onTyped) })
        }
        Spacer(Modifier.width(48.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (address != null) {
                QrCode(address, Modifier.size(240.dp))
                Spacer(Modifier.height(12.dp))
                Text(address, fontSize = 15.sp, color = Palette.TextFaint)
            } else {
                Text("Conecte a TV ao Wi-Fi para usar o QR Code", color = Palette.Error)
            }
        }
    }
}

/** Liga o servidor de configuração pelo celular enquanto a tela estiver aberta. */
@Composable
fun rememberPhoneSetupServer(): String? {
    val context = LocalContext.current
    val server = remember {
        PhoneSetupServer { url, name, epg ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { addLink(context, url, name, epg) }
        }
    }
    DisposableEffect(server) {
        server.start()
        onDispose { server.stop() }
    }
    return remember(server) { server.address() }
}
