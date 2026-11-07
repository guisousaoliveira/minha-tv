package dev.guisousa.badwolf.mobile.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Tudo o que é do usuário e fica guardado no aparelho: Minha lista, onde parou, já assisti, recentes,
 * buscas, versão preferida e ajustes. Fica só aqui para a sincronização entre aparelhos entrar depois
 * num lugar só (ouvir [changes] para enviar, chamar os métodos para aplicar o que chegou).
 *
 * As chaves são as mesmas do app da TV (app/.../data/Repository.kt), para os dados serem compatíveis.
 */
object LocalStore {

    private const val MIN_RESUME_MS = 5_000L
    const val DUB = "dub"
    const val LEG = "leg"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Avisa a chave que mudou (gancho para a sincronização futura). */
    val changes: SharedFlow<String> = _changes

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites
    private val _watched = MutableStateFlow<Set<String>>(emptySet())
    /** Hashes dos itens já vistos até o fim. */
    val watched: StateFlow<Set<String>> = _watched
    private val _recentsVersion = MutableStateFlow(0)
    /** Muda quando "Continuar assistindo" ou o progresso mudam (para as telas recarregarem). */
    val recentsVersion: StateFlow<Int> = _recentsVersion

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("minhatv", Context.MODE_PRIVATE)
        _favorites.value = prefs.getStringSet(FAV_KEY, emptySet())!!.toSet()
        _watched.value = prefs.getStringSet(WATCHED_KEY, emptySet())!!.toSet()
    }

    private inline fun edit(key: String, block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _changes.tryEmit(key)
    }

    // ---------- Minha lista ----------

    private const val FAV_KEY = "v2_favorites"

    fun toggleFavorite(url: String): Boolean {
        val now = _favorites.value.toMutableSet()
        val added = if (url in now) { now.remove(url); false } else { now.add(url); true }
        setFavorites(now)
        return added
    }

    fun setFavorites(urls: Set<String>) {
        _favorites.value = urls
        edit(FAV_KEY) { putStringSet(FAV_KEY, urls) }
    }

    // ---------- Onde parou / já assisti ----------

    private const val WATCHED_KEY = "watched"

    fun getResume(url: String): Long = prefs.getLong("resume_" + url.hashCode(), 0)

    /** Duração conhecida (ms). 0 = não sei. */
    fun duration(url: String): Long = prefs.getLong("dur_" + url.hashCode(), 0)

    /** Guarda a posição; faltando menos de 2 min para o fim, conta como visto. */
    fun saveResume(url: String, position: Long, duration: Long) {
        val key = "resume_" + url.hashCode()
        val nearEnd = duration > 0 && position >= duration - 120_000
        if (nearEnd && position > 60_000) markWatched(url)
        if (position > MIN_RESUME_MS && !nearEnd) {
            edit(key) { putLong(key, position).putLong("dur_" + url.hashCode(), duration) }
            _recentsVersion.value++
        } else if (nearEnd && position > 60_000) {
            edit(key) { remove(key).remove("dur_" + url.hashCode()) }
            _recentsVersion.value++
        }
    }

    fun clearResume(url: String) {
        val key = "resume_" + url.hashCode()
        edit(key) { remove(key).remove("dur_" + url.hashCode()) }
        _recentsVersion.value++
    }

    /** Quanto já foi visto (0..1), ou null se não começou. */
    fun progress(url: String): Float? {
        val pos = getResume(url)
        val dur = duration(url)
        return if (pos <= 0 || dur <= 0) null else pos.toFloat() / dur
    }

    fun isWatched(url: String) = url.hashCode().toString() in _watched.value

    fun markWatched(url: String) {
        val h = url.hashCode().toString()
        if (h in _watched.value) return
        _watched.value = _watched.value + h
        edit(WATCHED_KEY) { putStringSet(WATCHED_KEY, _watched.value) }
    }

    fun unmarkWatched(url: String) {
        val h = url.hashCode().toString()
        if (h !in _watched.value) return
        _watched.value = _watched.value - h
        edit(WATCHED_KEY) { putStringSet(WATCHED_KEY, _watched.value) }
    }

    // ---------- Recentes ----------

    private const val RECENT_LIVE = "v2_recent_live"
    private const val RECENT_VOD = "v2_recent_vod"
    private val recentLock = Any()

    fun addRecent(e: Entry) {
        if (!e.url.startsWith("v2:")) return
        val key = if (e.type == ContentType.LIVE) RECENT_LIVE else RECENT_VOD
        synchronized(recentLock) {
            val list = (listOf(e) + readRecent(key).filterNot { it.url == e.url }).take(30)
            edit(key) { putString(key, json.encodeToString(ListSerializer(Entry.serializer()), list)) }
        }
        _recentsVersion.value++
    }

    fun removeRecent(url: String) = synchronized(recentLock) {
        listOf(RECENT_LIVE, RECENT_VOD).forEach { key ->
            val list = readRecent(key)
            if (list.any { it.url == url }) {
                edit(key) { putString(key, json.encodeToString(ListSerializer(Entry.serializer()), list.filterNot { it.url == url })) }
            }
        }
        _recentsVersion.value++
    }

    private fun readRecent(key: String): List<Entry> = runCatching {
        json.decodeFromString(ListSerializer(Entry.serializer()), prefs.getString(key, null) ?: return emptyList())
    }.getOrDefault(emptyList())

    fun recentLive(): List<Entry> = readRecent(RECENT_LIVE)
    fun recentVod(): List<Entry> = readRecent(RECENT_VOD)

    // ---------- Buscas ----------

    private const val SEARCH_KEY = "recent_searches"

    fun recentSearches(): List<String> = prefs.getString(SEARCH_KEY, "")!!.split('\n').filter { it.isNotBlank() }

    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val list = (listOf(t) + recentSearches().filterNot { it.equals(t, ignoreCase = true) }).take(8)
        edit(SEARCH_KEY) { putString(SEARCH_KEY, list.joinToString("\n")) }
    }

    fun clearRecentSearches() = edit(SEARCH_KEY) { remove(SEARCH_KEY) }

    // ---------- Versão e ajustes ----------

    /** Versão preferida em todo o app (padrão: Dublado). */
    var preferredVersion: String
        get() = prefs.getString("pref_version", DUB) ?: DUB
        set(v) = edit("pref_version") { putString("pref_version", v) }

    private fun titleKey(e: Entry) = "ver_" + (if (e.type == ContentType.SERIES) e.seriesName ?: e.name else e.name).hashCode()

    fun versionFor(e: Entry): String = prefs.getString(titleKey(e), null) ?: preferredVersion
    fun setVersionFor(e: Entry, v: String) { val k = titleKey(e); edit(k) { putString(k, v) } }

    /** Formato da tela do player (AspectRatioFrameLayout.RESIZE_MODE_*). */
    var resizeMode: Int
        get() = prefs.getInt("resize_mode", 0)
        set(v) = edit("resize_mode") { putInt("resize_mode", v) }

    var lastChannelUrl: String?
        get() = prefs.getString("last_channel", null)
        set(v) = edit("last_channel") { putString("last_channel", v) }

    /** Apaga o que foi visto/onde parou (botão em Ajustes). Minha lista fica. */
    fun clearHistory() {
        synchronized(recentLock) {
            val e = prefs.edit()
            prefs.all.keys.filter { it.startsWith("resume_") || it.startsWith("dur_") }.forEach { e.remove(it) }
            e.remove(RECENT_LIVE).remove(RECENT_VOD).remove(WATCHED_KEY).apply()
        }
        _watched.value = emptySet()
        _recentsVersion.value++
        _changes.tryEmit("history")
    }
}
