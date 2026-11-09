package dev.guisousa.minhatv.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.guisousa.minhatv.data.AppDirs
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Log
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Settings
import dev.guisousa.minhatv.data.UserLibrary
import dev.guisousa.minhatv.desktop.player.PlayerController
import dev.guisousa.minhatv.desktop.player.PlayerScreen
import dev.guisousa.minhatv.desktop.ui.CategoryScreen
import dev.guisousa.minhatv.desktop.ui.HomeScreen
import dev.guisousa.minhatv.desktop.ui.Ic
import dev.guisousa.minhatv.desktop.ui.IconBtn
import dev.guisousa.minhatv.desktop.ui.LiveScreen
import dev.guisousa.minhatv.desktop.ui.LocalNav
import dev.guisousa.minhatv.desktop.ui.MovieDetails
import dev.guisousa.minhatv.desktop.ui.MyListScreen
import dev.guisousa.minhatv.desktop.ui.Nav
import dev.guisousa.minhatv.desktop.ui.Palette
import dev.guisousa.minhatv.desktop.ui.Route
import dev.guisousa.minhatv.desktop.ui.SearchScreen
import dev.guisousa.minhatv.desktop.ui.SettingsScreen
import dev.guisousa.minhatv.desktop.ui.ShowDetails
import dev.guisousa.minhatv.desktop.ui.Tab
import dev.guisousa.minhatv.desktop.ui.VodScreen
import dev.guisousa.minhatv.desktop.ui.rememberHover
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.TimeUnit

private val appScope = MainScope()

/** Capas e fundos: OkHttp com cache em disco em %LOCALAPPDATA%\BadWolfTV\imagens. */
private fun setupImages() {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .build()
    SingletonImageLoader.setSafe { ctx: PlatformContext ->
        ImageLoader.Builder(ctx)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                add(DecoderNitido.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(256L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(File(AppDirs.cache, "imagens").toOkioPath()).maxSizeBytes(600L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }
}

fun main() {
    Log.i("Bad Wolf TV abrindo (Java ${System.getProperty("java.version")})")
    Thread.setDefaultUncaughtExceptionHandler { t, e -> Log.e("erro na thread ${t.name}", e) }
    setupImages()
    Repository.init()
    val player = PlayerController()
    player.preparar()
    application {
        val nav = remember { Nav(player) }
        val state = rememberWindowState(size = DpSize(1366.dp, 800.dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = {
                runCatching { player.release() }
                UserLibrary.flush(); Settings.flush()
                exitApplication()
            },
            state = state,
            title = "Bad Wolf TV",
            icon = painterResource("logo_lobo.png"),
            onPreviewKeyEvent = { ev -> ev.type == KeyEventType.KeyDown && handleKey(nav, ev.key, ev.isAltPressed) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(960, 600)
                window.background = java.awt.Color.BLACK
            }
            // tela cheia sem borda (não sai sozinha ao clicar em outro monitor); se não der, usa a do Java
            LaunchedEffect(nav.fullscreen) {
                if (!TelaCheia.definir(window, nav.fullscreen)) {
                    state.placement = if (nav.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
                }
            }
            App(nav)
        }
    }
}

/** Atalhos de teclado. Devolve true quando a tecla foi usada. */
private fun handleKey(nav: Nav, key: Key, alt: Boolean): Boolean {
    val p = nav.player
    val inPlayer = nav.top == Route.Player
    val typing = nav.top == null && nav.tab == Tab.SEARCH
    if (key == Key.F11 || (key == Key.Enter && alt)) { nav.fullscreen = !nav.fullscreen; return true }
    if (key == Key.Escape) {
        // Esc sai da tela cheia primeiro (como no navegador); de novo, volta uma tela
        if (nav.fullscreen) { nav.fullscreen = false; return true }
        return nav.back()
    }
    if (typing) return false
    if (key == Key.Backspace) return nav.back()
    if (!inPlayer) {
        // prévia do ao vivo tocando: M liga/desliga o som
        if (key == Key.M && p.item != null) { p.toggleMute(); return true }
        return false
    }
    when (key) {
        Key.F -> nav.fullscreen = !nav.fullscreen
        Key.Spacebar, Key.K -> p.togglePause()
        Key.DirectionLeft -> p.seekBy(-10_000)
        Key.DirectionRight -> p.seekBy(10_000)
        Key.DirectionUp -> p.changeVolume(p.volume + 5)
        Key.DirectionDown -> p.changeVolume(p.volume - 5)
        Key.M -> p.toggleMute()
        Key.PageUp, Key.PageDown -> {
            val cur = p.item ?: return false
            if (!p.live) return false
            val dir = if (key == Key.PageUp) -1 else 1
            appScope.launch {
                val (list, i) = Repository.channelQueue(cur.url) ?: return@launch
                if (list.isNotEmpty()) p.open(list[(i + dir + list.size) % list.size])
            }
        }
        else -> return false
    }
    return true
}

@Composable
private fun App(nav: Nav) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Brand, background = Palette.Background, surface = Palette.Surface)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background)) {
            // a interface cresce com a janela (1280 dp de largura = tamanho normal)
            val scale = (maxWidth.value / 1280f).coerceIn(0.8f, 1.6f)
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale), LocalNav provides nav) {
                val top = nav.top
                if (top == Route.Player) PlayerScreen()
                else Column(Modifier.fillMaxSize()) {
                    TopBar(nav)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (top) {
                            is Route.MovieDetails -> MovieDetails(top.e)
                            is Route.ShowDetails -> ShowDetails(top.s)
                            is Route.Category -> CategoryScreen(top)
                            else -> when (nav.tab) {
                                Tab.HOME -> HomeScreen()
                                Tab.LIVE -> LiveScreen()
                                Tab.MOVIES -> VodScreen(ContentType.MOVIE)
                                Tab.SHOWS -> VodScreen(ContentType.SERIES)
                                Tab.MYLIST -> MyListScreen()
                                Tab.SEARCH -> SearchScreen()
                                Tab.SETTINGS -> SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(nav: Nav) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (nav.stack.isNotEmpty()) {
            IconBtn(Ic.Back, { nav.back() }, size = 40.dp)
            Spacer(Modifier.width(8.dp))
        }
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).pointerHoverIcon(PointerIcon.Hand).clickable { nav.selectTab(Tab.HOME) }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource("logo_lobo.png"), null, Modifier.size(34.dp))
            Spacer(Modifier.width(8.dp))
            Text("BAD WOLF", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text(" TV", color = Palette.Brand, fontSize = 17.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(Tab.HOME, Tab.LIVE, Tab.MOVIES, Tab.SHOWS, Tab.MYLIST).forEach { t -> TabItem(t.title, nav.tab == t) { nav.selectTab(t) } }
        }
        Spacer(Modifier.weight(1f))
        IconBtn(Ic.Search, { nav.selectTab(Tab.SEARCH) }, size = 40.dp, tint = if (nav.tab == Tab.SEARCH) Palette.Brand else Palette.Text)
        Spacer(Modifier.width(4.dp))
        TabItem(Tab.SETTINGS.title, nav.tab == Tab.SETTINGS) { nav.selectTab(Tab.SETTINGS) }
    }
}

@Composable
private fun TabItem(title: String, current: Boolean, onClick: () -> Unit) {
    val (src, hovered) = rememberHover()
    Column(
        Modifier.clip(RoundedCornerShape(6.dp)).pointerHoverIcon(PointerIcon.Hand)
            .background(if (hovered) Color(0x1AFFFFFF) else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = if (current) Palette.Text else Palette.TextDim, fontSize = 15.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(18.dp).height(2.dp).background(if (current) Palette.Brand else Color.Transparent))
    }
}
