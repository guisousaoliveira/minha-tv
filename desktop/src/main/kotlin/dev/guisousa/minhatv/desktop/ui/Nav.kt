package dev.guisousa.minhatv.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Show
import dev.guisousa.minhatv.desktop.player.PlayerController

enum class Tab(val title: String) { HOME("Início"), LIVE("Ao vivo"), MOVIES("Filmes"), SHOWS("Séries"), MYLIST("Minha lista"), SEARCH("Buscar"), SETTINGS("Ajustes") }

sealed class Route {
    data class MovieDetails(val e: Entry) : Route()
    data class ShowDetails(val s: Show) : Route()
    data class Category(val type: ContentType, val group: String?, val title: String) : Route()
    data object Player : Route()
}

/** Navegação: a aba atual e uma pilha de telas por cima dela (detalhes, grade, player). */
class Nav(val player: PlayerController) {
    var tab by mutableStateOf(Tab.HOME)
        private set
    val stack = mutableStateListOf<Route>()
    val top: Route? get() = stack.lastOrNull()
    var fullscreen by mutableStateOf(false)

    fun selectTab(t: Tab) {
        if (player.item != null && (t != Tab.LIVE || stack.isNotEmpty())) player.stop()
        stack.clear()
        tab = t
    }

    fun push(r: Route) { stack.add(r) }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        val r = stack.removeAt(stack.lastIndex)
        if (r == Route.Player) {
            fullscreen = false
            // canal aberto pela prévia do Ao vivo continua tocando na prévia
            if (!(tab == Tab.LIVE && stack.isEmpty() && player.live)) player.stop()
        }
        return true
    }

    fun openMovie(e: Entry) = push(Route.MovieDetails(e))
    fun openShow(s: Show) = push(Route.ShowDetails(s))

    /** Abre qualquer coisa: filme → detalhes, série → detalhes, canal/episódio → player. */
    fun open(item: Any) {
        when (item) {
            is Show -> openShow(item)
            is Entry -> when (item.type) {
                ContentType.MOVIE -> openMovie(item)
                else -> play(item)
            }
        }
    }

    fun play(e: Entry) {
        if (player.item?.url != e.url) player.open(e)
        if (top != Route.Player) push(Route.Player)
    }

    /** Episódio da série a partir da chave salva; filme pela chave. */
    fun openKey(key: String) { Repository.itemOf(key)?.let { open(it) } }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("sem Nav") }
