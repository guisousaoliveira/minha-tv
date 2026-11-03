package dev.guisousa.minhatv.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.minhatv.data.PhoneSetupServer
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Show
import dev.guisousa.minhatv.player.PlaybackQueue
import kotlinx.coroutines.delay

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
    LIVE("Ao vivo"), MOVIES("Filmes"), SERIES("Séries"), FAVORITES("Favoritos"), SEARCH("Buscar"), SETTINGS("Ajustes")
}

@Composable
fun AppRoot(onLoaded: () -> Unit) {
    val context = LocalContext.current
    val loading by Repository.isLoading.collectAsState()
    val library by Repository.library.collectAsState()
    val playlists by Repository.playlists.collectAsState()
    val sources by Repository.sources.collectAsState()
    val status by Repository.status.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openShow by remember { mutableStateOf<Show?>(null) }
    val firstTab = remember { FocusRequester() }

    LaunchedEffect(loading) {
        if (!loading) {
            onLoaded()
            runCatching { firstTab.requestFocus() }
        }
    }
    // enquanto o app estiver aberto, confere a cada 30 min se já passou da hora de atualizar
    LaunchedEffect(Unit) {
        while (true) {
            delay(30 * 60_000L)
            Repository.refreshIfStale()
        }
    }

    BackHandler(enabled = openShow != null) { openShow = null }

    val nothingAdded = !loading && playlists.isEmpty() && sources.isEmpty()

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("📺 Minha TV", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.width(16.dp))
            Tab.entries.forEachIndexed { i, t ->
                TvButton(
                    t.title,
                    onClick = { tab = i; openShow = null },
                    selected = tab == i,
                    modifier = if (i == 0) Modifier.focusRequester(firstTab) else Modifier,
                )
            }
            Spacer(Modifier.weight(1f))
            status?.let { Text(it, fontSize = 13.sp, color = Palette.TextDim, maxLines = 1) }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Message("Carregando…")
                nothingAdded && Tab.entries[tab] != Tab.SETTINGS -> Welcome(onTyped = { url, name, epg ->
                    addLink(context, url, name, epg)
                })
                openShow != null -> SeriesDetailScreen(openShow!!)
                else -> when (Tab.entries[tab]) {
                    Tab.LIVE -> LiveScreen(library)
                    Tab.MOVIES -> MoviesScreen(library)
                    Tab.SERIES -> SeriesScreen(library) { openShow = it }
                    Tab.FAVORITES -> FavoritesScreen(library)
                    Tab.SEARCH -> SearchScreen(library) { openShow = it }
                    Tab.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

fun addLink(context: android.content.Context, url: String, name: String?, epg: String?) {
    if (url.isBlank()) return
    Toast.makeText(context, "Carregando o link…", Toast.LENGTH_SHORT).show()
    Repository.addFromUrl(url, name, epg) { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
}

/** Primeira abertura: mostra o endereço para configurar pelo celular. */
@Composable
fun Welcome(onTyped: (String, String?, String?) -> Unit) {
    val context = LocalContext.current
    val address = rememberPhoneSetupServer()
    Message(
        "Bem-vindo! Vamos adicionar os canais.",
        "Aponte a câmera do celular para o QR Code (o celular precisa estar no mesmo Wi-Fi da TV)\n" +
            "e cole o link da playlist, da página ou do repositório do GitHub. A TV recebe na hora.",
    ) {
        if (address != null) {
            QrCode(address, Modifier.size(220.dp))
            Spacer(Modifier.height(10.dp))
            Text(address, fontSize = 16.sp, color = Palette.TextDim)
        } else {
            Text("(conecte a TV ao Wi-Fi para usar o QR Code)", color = Palette.Error)
        }
        Spacer(Modifier.height(20.dp))
        TvButton("Ou digitar o link aqui com o controle", onClick = { showAddLinkDialog(context, onTyped) })
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
