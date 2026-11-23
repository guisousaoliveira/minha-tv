package dev.guisousa.badwolf.mobile.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.badwolf.mobile.data.Entry
import dev.guisousa.badwolf.mobile.data.Repository
import dev.guisousa.badwolf.mobile.data.Show
import dev.guisousa.badwolf.mobile.data.Updater
import kotlinx.coroutines.launch

/** Telas que abrem por cima das abas. */
sealed interface Dest {
    data class Movie(val e: Entry) : Dest
    data class Series(val s: Show) : Dest
    data object Search : Dest
}

/** Navegação simples: abas embaixo e uma pilha de telas por cima. */
class Nav {
    val stack = mutableStateListOf<Dest>()
    fun openMovie(e: Entry) { stack += Dest.Movie(e) }
    fun openShow(s: Show) { stack += Dest.Series(s) }
    fun openSearch() { if (stack.lastOrNull() != Dest.Search) stack += Dest.Search }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    /** Abre o que veio de uma fileira (filme ou série). */
    fun open(item: Any) { when (item) { is Entry -> openMovie(item); is Show -> openShow(item) } }
}

private enum class Tab(val label: String) { HOME("Início"), MOVIES("Filmes"), SHOWS("Séries"), LIVE("Ao vivo"), SETTINGS("Ajustes") }

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

/** Estado da atualização do app (aviso de versão nova, baixando). */
class UpdateState {
    var release by mutableStateOf<Updater.Release?>(null)
    var baixando by mutableStateOf(false)
}

@Composable
private fun App() {
    val nav = remember { Nav() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val saver = rememberSaveableStateHolder()
    val update = remember { UpdateState() }
    BackHandler(enabled = nav.stack.isNotEmpty() || tab != 0) { if (nav.stack.isNotEmpty()) nav.back() else tab = 0 }

    // ao abrir: confere se saiu versão nova (Ajustes > Atualização automática)
    LaunchedEffect(Unit) { if (Repository.autoUpdate) update.release = Updater.check() }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                saver.SaveableStateProvider(Tab.entries[tab].name) {
                    when (Tab.entries[tab]) {
                        Tab.HOME -> HomeScreen(nav)
                        Tab.MOVIES -> MoviesScreen(nav)
                        Tab.SHOWS -> ShowsScreen(nav)
                        Tab.LIVE -> LiveScreen(nav)
                        Tab.SETTINGS -> SettingsScreen(update)
                    }
                }
            }
            NavigationBar(containerColor = Color(0xFF0B0B0D), tonalElevation = 0.dp) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i && nav.stack.isEmpty(),
                        onClick = { nav.stack.clear(); tab = i },
                        icon = { TabIcon(t) },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black, selectedTextColor = Palette.Text,
                            unselectedIconColor = Palette.TextFaint, unselectedTextColor = Palette.TextFaint,
                            indicatorColor = Palette.Brand,
                        ),
                    )
                }
            }
        }
        // telas por cima (detalhes, busca) cobrem as abas e a barra de baixo
        nav.stack.lastOrNull()?.let { d ->
            Box(Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                saver.SaveableStateProvider(d.toString()) {
                    when (d) {
                        is Dest.Movie -> MovieDetailScreen(d.e, nav)
                        is Dest.Series -> SeriesDetailScreen(d.s, nav)
                        Dest.Search -> SearchScreen(nav)
                    }
                }
            }
        }
    }
    UpdateDialog(update)
}

/** "Nova versão disponível": baixa e abre o instalador do Android. */
@Composable
fun UpdateDialog(update: UpdateState) {
    val r = update.release ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!update.baixando) update.release = null },
        title = { Text("Nova versão disponível") },
        text = {
            Text(
                if (update.baixando) "Baixando a versão ${r.name}… Quando terminar, o Android pergunta se pode instalar."
                else "A versão ${r.name} do Bad Wolf está pronta (você está na ${Updater.currentName}). Leva menos de um minuto e nada se perde.",
            )
        },
        confirmButton = {
            TextButton(enabled = !update.baixando, onClick = {
                update.baixando = true
                scope.launch {
                    try {
                        val file = Updater.download(context, r)
                        (context as? Activity)?.let { Updater.install(it, file) }
                        update.release = null
                    } catch (e: Exception) {
                        Toast.makeText(context, "Não deu para baixar: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        update.baixando = false
                    }
                }
            }) { Text(if (update.baixando) "Baixando…" else "Atualizar", color = Palette.Brand, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { if (!update.baixando) TextButton(onClick = { update.release = null }) { Text("Depois", color = Palette.TextDim) } },
        containerColor = Palette.SurfaceHigh,
    )
}

@Composable
private fun TabIcon(t: Tab) {
    when (t) {
        Tab.HOME -> Icon(Icons.Filled.Home, null)
        Tab.MOVIES -> Icon(Icons.Filled.PlayArrow, null)
        Tab.SHOWS -> Icon(Icons.Filled.List, null)
        Tab.LIVE -> Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { LiveDot(Modifier.size(10.dp)) }
        Tab.SETTINGS -> Icon(Icons.Filled.Settings, null)
    }
}
