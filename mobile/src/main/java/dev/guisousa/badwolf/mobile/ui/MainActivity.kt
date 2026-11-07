package dev.guisousa.badwolf.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.badwolf.mobile.data.Entry
import dev.guisousa.badwolf.mobile.data.Repository
import dev.guisousa.badwolf.mobile.data.Show

/** Telas que abrem por cima das abas. */
sealed interface Dest {
    data class Movie(val e: Entry) : Dest
    data class Series(val s: Show) : Dest
    data object Settings : Dest
}

/** Navegação simples: abas embaixo e uma pilha de telas por cima. */
class Nav {
    val stack = mutableStateListOf<Dest>()
    fun openMovie(e: Entry) { stack += Dest.Movie(e) }
    fun openShow(s: Show) { stack += Dest.Series(s) }
    fun openSettings() { stack += Dest.Settings }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    /** Abre o que veio de uma fileira (filme ou série). */
    fun open(item: Any) { when (item) { is Entry -> openMovie(item); is Show -> openShow(item) } }
}

private enum class Tab(val label: String) { HOME("Início"), MOVIES("Filmes"), SHOWS("Séries"), LIVE("Ao vivo"), SEARCH("Busca") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK))
        super.onCreate(savedInstanceState)
        setContent { BadWolfTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        Repository.refreshIfChanged()
    }
}

@Composable
private fun App() {
    val nav = remember { Nav() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val saver = rememberSaveableStateHolder()
    BackHandler(enabled = nav.stack.isNotEmpty() || tab != 0) { if (nav.stack.isNotEmpty()) nav.back() else tab = 0 }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                saver.SaveableStateProvider(Tab.entries[tab].name) {
                    when (Tab.entries[tab]) {
                        Tab.HOME -> HomeScreen(nav)
                        Tab.MOVIES -> MoviesScreen(nav)
                        Tab.SHOWS -> ShowsScreen(nav)
                        Tab.LIVE -> LiveScreen()
                        Tab.SEARCH -> SearchScreen(nav)
                    }
                }
            }
            NavigationBar(containerColor = Color(0xFF0A0A0A), tonalElevation = 0.dp) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i && nav.stack.isEmpty(),
                        onClick = { nav.stack.clear(); tab = i },
                        icon = { TabIcon(t) },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.Text, selectedTextColor = Palette.Text,
                            unselectedIconColor = Palette.TextFaint, unselectedTextColor = Palette.TextFaint,
                            indicatorColor = Palette.SurfaceHigh,
                        ),
                    )
                }
            }
        }
        // telas por cima (detalhes, ajustes) cobrem as abas e a barra de baixo
        nav.stack.lastOrNull()?.let { d ->
            Box(Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                saver.SaveableStateProvider(d.toString()) {
                    when (d) {
                        is Dest.Movie -> MovieDetailScreen(d.e, nav)
                        is Dest.Series -> SeriesDetailScreen(d.s, nav)
                        Dest.Settings -> SettingsScreen(nav)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(t: Tab) {
    when (t) {
        Tab.HOME -> Icon(Icons.Filled.Home, null)
        Tab.MOVIES -> Icon(Icons.Filled.PlayArrow, null)
        Tab.SHOWS -> Icon(Icons.Filled.Star, null)
        Tab.LIVE -> Box(Modifier.size(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { LiveDot() }
        Tab.SEARCH -> Icon(Icons.Filled.Search, null)
    }
}

/** Cabeçalho das abas: título e o botão de Ajustes. */
@Composable
fun TopTitle(title: String, nav: Nav?, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Palette.Text, modifier = Modifier.weight(1f))
        if (nav != null) androidx.compose.material3.IconButton(onClick = { nav.openSettings() }) {
            Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = Palette.TextDim)
        }
    }
}
