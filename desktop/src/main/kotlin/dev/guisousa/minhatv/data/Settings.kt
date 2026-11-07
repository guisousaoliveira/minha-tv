package dev.guisousa.minhatv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Ajustes(
    /** Versão preferida em todo o app: "dub" ou "leg". */
    val versaoPreferida: String = "dub",
    /** 0..100 */
    val volume: Int = 90,
    val ultimoCanal: String? = null,
    /** Ao abrir o app, já toca o último canal. */
    val abrirNoUltimoCanal: Boolean = false,
    /** Ao terminar um episódio, toca o próximo. */
    val proximoEpisodio: Boolean = true,
)

/** Ajustes deste computador (não sincronizam). Arquivo: %APPDATA%\BadWolfTV\ajustes.json */
object Settings {
    private val store = JsonFile(File(AppDirs.data, "ajustes.json"), Ajustes.serializer()) { Ajustes() }
    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<Ajustes> = _state
    val value get() = _state.value

    @Synchronized
    fun edit(f: (Ajustes) -> Ajustes) {
        val n = f(_state.value)
        if (n == _state.value) return
        _state.value = n
        store.save(n)
    }

    fun flush() = store.writeNow(_state.value)
}
