package dev.guisousa.minhatv.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** Onde parou num filme/episódio. */
@Serializable
data class Progresso(val pos: Long, val dur: Long, val em: Long)

/** Tudo do usuário que um dia vai sincronizar com a conta (por enquanto só no arquivo). */
@Serializable
data class UserData(
    val formato: Int = 1,
    /** Chaves dos itens ("v2:f:123", "v2:e:45", "v2:c:9"), o mais novo primeiro. */
    val minhaLista: List<String> = emptyList(),
    /** Chave -> quando terminou de ver. */
    val jaAssisti: Map<String, Long> = emptyMap(),
    /** Chave -> onde parou. */
    val progresso: Map<String, Progresso> = emptyMap(),
    /** Filmes/episódios abertos por último (para o Continuar assistindo). */
    val recentesVod: List<Entry> = emptyList(),
    val recentesAoVivo: List<Entry> = emptyList(),
    val buscas: List<String> = emptyList(),
    /** Título -> versão escolhida (dub/leg). */
    val versaoPorTitulo: Map<String, String> = emptyMap(),
)

/**
 * Continuar assistindo, Minha lista e Já assisti. É o ÚNICO lugar que lê e grava isso:
 * a sincronização com a conta (depois) entra aqui, ouvindo [mudancas] e chamando [aplicar].
 * Arquivo: %APPDATA%\BadWolfTV\biblioteca.json
 */
object UserLibrary {
    private const val MIN_RESUME_MS = 5_000L
    private const val END_MARGIN_MS = 120_000L

    private val store = JsonFile(File(AppDirs.data, "biblioteca.json"), UserData.serializer()) { UserData() }
    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<UserData> = _state

    /** O que mudou (para a sincronização futura mandar só a diferença). */
    sealed class Mudanca {
        data class Lista(val key: String, val naLista: Boolean) : Mudanca()
        data class Assistido(val key: String, val visto: Boolean) : Mudanca()
        data class Posicao(val key: String, val p: Progresso?) : Mudanca()
    }
    private val _mudancas = MutableSharedFlow<Mudanca>(extraBufferCapacity = 64)
    val mudancas: SharedFlow<Mudanca> = _mudancas

    @Synchronized
    private fun edit(change: Mudanca? = null, f: (UserData) -> UserData) {
        val novo = f(_state.value)
        if (novo == _state.value) return
        _state.value = novo
        store.save(novo)
        change?.let { _mudancas.tryEmit(it) }
    }

    /** Troca tudo de uma vez (ex.: dados que vieram da conta). */
    fun aplicar(dados: UserData) = edit { dados }

    /** Grava na hora (ao fechar o app). */
    fun flush() = store.writeNow(_state.value)

    // ---- Minha lista ----

    fun naLista(key: String) = key in _state.value.minhaLista

    fun alternarLista(key: String): Boolean {
        val agora = !naLista(key)
        edit(Mudanca.Lista(key, agora)) { d -> d.copy(minhaLista = if (agora) listOf(key) + d.minhaLista else d.minhaLista - key) }
        return agora
    }

    // ---- Já assisti ----

    fun assistido(key: String) = key in _state.value.jaAssisti

    fun marcarAssistido(key: String, visto: Boolean = true) = edit(Mudanca.Assistido(key, visto)) { d ->
        if (visto) d.copy(jaAssisti = d.jaAssisti + (key to System.currentTimeMillis()), progresso = d.progresso - key)
        else d.copy(jaAssisti = d.jaAssisti - key)
    }

    // ---- Onde parou ----

    fun posicao(key: String): Long = _state.value.progresso[key]?.pos ?: 0
    fun duracao(key: String): Long = _state.value.progresso[key]?.dur ?: 0

    /** Quanto já foi visto (0..1), ou null se não começou. */
    fun fracao(key: String): Float? = _state.value.progresso[key]?.let { p -> if (p.dur > 0) p.pos.toFloat() / p.dur else null }

    /** Guarda a posição; faltando menos de 2 min para o fim (e passado 1 min), conta como visto. */
    fun salvarPosicao(key: String, pos: Long, dur: Long) {
        val perto = dur > 0 && pos >= dur - END_MARGIN_MS
        when {
            perto && pos > 60_000 -> marcarAssistido(key)
            pos > MIN_RESUME_MS -> {
                val p = Progresso(pos, dur, System.currentTimeMillis())
                edit(Mudanca.Posicao(key, p)) { d -> d.copy(progresso = d.progresso + (key to p)) }
            }
        }
    }

    fun limparPosicao(key: String) = edit(Mudanca.Posicao(key, null)) { d -> d.copy(progresso = d.progresso - key) }

    // ---- Recentes ----

    fun adicionarRecente(e: Entry) {
        if (!e.url.startsWith("v2:")) return
        edit { d ->
            if (e.type == ContentType.LIVE) d.copy(recentesAoVivo = (listOf(e) + d.recentesAoVivo.filterNot { it.url == e.url }).take(30))
            else d.copy(recentesVod = (listOf(e) + d.recentesVod.filterNot { it.url == e.url }).take(40))
        }
    }

    /** Filmes/episódios começados e não terminados (o mais recente primeiro). */
    fun continuarAssistindo(): List<Entry> {
        val d = _state.value
        return d.recentesVod.filter { (d.progresso[it.url]?.pos ?: 0) > 0 && it.url !in d.jaAssisti }
    }

    fun recentesAoVivo(): List<Entry> = _state.value.recentesAoVivo

    // ---- Buscas ----

    fun buscas(): List<String> = _state.value.buscas

    fun adicionarBusca(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        edit { d -> d.copy(buscas = (listOf(t) + d.buscas.filterNot { it.equals(t, ignoreCase = true) }).take(8)) }
    }

    fun limparBuscas() = edit { it.copy(buscas = emptyList()) }

    // ---- Versão por título ----

    fun versao(titulo: String): String? = _state.value.versaoPorTitulo[titulo]
    fun definirVersao(titulo: String, v: String) = edit { d -> d.copy(versaoPorTitulo = d.versaoPorTitulo + (titulo to v)) }
}
