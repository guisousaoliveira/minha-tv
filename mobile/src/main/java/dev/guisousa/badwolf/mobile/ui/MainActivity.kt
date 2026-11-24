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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.badwolf.mobile.data.Conta
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
    data object Auth : Dest
}

/** Navegação simples: abas embaixo e uma pilha de telas por cima. */
class Nav {
    val stack = mutableStateListOf<Dest>()
    fun openMovie(e: Entry) { stack += Dest.Movie(e) }
    fun openShow(s: Show) { stack += Dest.Series(s) }
    fun openSearch() { if (stack.lastOrNull() != Dest.Search) stack += Dest.Search }
    fun openAuth() { if (stack.lastOrNull() != Dest.Auth) stack += Dest.Auth }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    /** Abre o que veio de uma fileira (filme ou série). */
    fun open(item: Any) { when (item) { is Entry -> openMovie(item); is Show -> openShow(item) } }
}

private enum class Tab(val label: String) {
    HOME("Início"),
    MOVIES("Filmes"),
    SHOWS("Séries"),
    LIVE("Ao vivo"),
    SETTINGS("Ajustes"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val obsidian = android.graphics.Color.parseColor("#06070A")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(obsidian),
        )
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
    var showWelcome by rememberSaveable { mutableStateOf(!Conta.conectado && !Conta.jaViuBoasVindas) }

    BackHandler(enabled = !showWelcome && (nav.stack.isNotEmpty() || tab != 0)) {
        if (nav.stack.isNotEmpty()) nav.back() else tab = 0
    }

    // ao abrir: confere se saiu versão nova (Ajustes > Atualização automática)
    LaunchedEffect(Unit) { if (Repository.autoUpdate) update.release = Updater.check() }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        if (showWelcome) {
            AuthScreen(
                isOnboarding = true,
                onDone = { showWelcome = false },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                ) {
                    saver.SaveableStateProvider(Tab.entries[tab].name) {
                        when (Tab.entries[tab]) {
                            Tab.HOME -> HomeScreen(nav)
                            Tab.MOVIES -> MoviesScreen(nav)
                            Tab.SHOWS -> ShowsScreen(nav)
                            Tab.LIVE -> LiveScreen(nav)
                            Tab.SETTINGS -> SettingsScreen(update, nav)
                        }
                    }
                }
                ObsidianBottomBar(
                    selectedTab = tab,
                    hasOverlay = nav.stack.isNotEmpty(),
                    onSelect = { i ->
                        nav.stack.clear()
                        tab = i
                    },
                )
            }

            // telas por cima (detalhes, busca, conta) cobrem as abas e a barra de baixo
            nav.stack.lastOrNull()?.let { d ->
                val needsBottomInset = d != Dest.Auth
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Palette.Background)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                if (needsBottomInset) WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                                else WindowInsetsSides.Horizontal,
                            ),
                        ),
                ) {
                    saver.SaveableStateProvider(d.toString()) {
                        when (d) {
                            is Dest.Movie -> MovieDetailScreen(d.e, nav)
                            is Dest.Series -> SeriesDetailScreen(d.s, nav)
                            Dest.Search -> SearchScreen(nav)
                            Dest.Auth -> AuthScreen(isOnboarding = false, onDone = { nav.back() })
                        }
                    }
                }
            }
        }
    }
    UpdateDialog(update)
}

/** Barra inferior compacta em vidro obsidiana espelhando o BottomNav do W4LLE TV mobile. */
@Composable
private fun ObsidianBottomBar(selectedTab: Int, hasOverlay: Boolean, onSelect: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.SurfaceGlass)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEachIndexed { i, t ->
                val active = selectedTab == i && !hasOverlay
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clickable(interactionSource = interaction, indication = null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Pontinho ciano no topo da aba ativa (marca registrada do W4LLE BottomNav)
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(if (active) Palette.Brand else Color.Transparent),
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(46.dp)
                            .height(28.dp)
                            .clip(PillShape)
                            .background(if (active) Palette.BrandSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        TabIcon(t, active)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t.label,
                        fontSize = 10.5.sp,
                        letterSpacing = (-0.1).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) Palette.Text else Palette.TextFaint,
                    )
                }
            }
        }
    }
}

/** "Nova versão disponível": baixa e abre o instalador do Android. */
@Composable
fun UpdateDialog(update: UpdateState) {
    val r = update.release ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!update.baixando) update.release = null },
        title = { Text("Nova versão disponível", color = Palette.Text) },
        text = {
            Text(
                if (update.baixando) "Baixando a versão ${r.name}… Quando terminar, o Android pergunta se pode instalar."
                else "A versão ${r.name} do Bad Wolf está pronta (você está na ${Updater.currentName}). Leva menos de um minuto e nada se perde.",
                color = Palette.TextDim,
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
        dismissButton = {
            if (!update.baixando) {
                TextButton(onClick = { update.release = null }) { Text("Depois", color = Palette.TextDim) }
            }
        },
        containerColor = Palette.SurfaceHigh,
    )
}

@Composable
private fun TabIcon(t: Tab, active: Boolean) {
    val tint = if (active) Palette.Brand else Palette.TextFaint
    when (t) {
        Tab.HOME -> Icon(Icons.Filled.Home, null, tint = tint, modifier = Modifier.size(21.dp))
        Tab.MOVIES -> Icon(Icons.Filled.PlayArrow, null, tint = tint, modifier = Modifier.size(22.dp))
        Tab.SHOWS -> Icon(Icons.Filled.List, null, tint = tint, modifier = Modifier.size(21.dp))
        Tab.LIVE -> Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            LiveDot(Modifier.size(10.dp), pulse = active)
            if (!active) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 1.dp, y = (-1).dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Palette.Live)
                        .border(1.dp, Palette.Background, CircleShape),
                )
            }
        }
        Tab.SETTINGS -> Icon(Icons.Filled.Settings, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
