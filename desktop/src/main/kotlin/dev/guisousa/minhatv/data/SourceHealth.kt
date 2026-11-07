// Adaptado de app/src/main/java/dev/guisousa/minhatv/data/SourceHealth.kt (app da TV):
// as SharedPreferences viraram um arquivo JSON em %LOCALAPPDATA%\BadWolfTV\fontes.json.
package dev.guisousa.minhatv.data

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.net.URI

/**
 * Memória de quais fontes tocam e quais falham neste computador, para começar pela que funciona.
 * Guarda por link e por servidor (host): um servidor fora do ar costuma falhar em todos os canais.
 */
object SourceHealth {
    private const val HOST_FAIL_MS = 30 * 60_000L        // servidor que falhou fica no fim por 30 min
    private const val URL_FAIL_MS = 6 * 60 * 60_000L     // link que falhou fica no fim por 6 h
    private const val OK_MS = 7 * 24 * 60 * 60_000L      // link que tocou vai primeiro por 7 dias

    private val store = JsonFile(File(AppDirs.cache, "fontes.json"), MapSerializer(String.serializer(), Long.serializer())) { emptyMap() }
    private val map: MutableMap<String, Long> = HashMap<String, Long>().also { m ->
        // limpa o que já venceu para não crescer sem fim
        val old = System.currentTimeMillis() - OK_MS
        store.load().forEach { (k, v) -> if (v >= old) m[k] = v }
    }

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: ""
    private fun get(k: String) = synchronized(map) { map[k] ?: 0L }
    private fun put(vararg kv: Pair<String, Long>) { synchronized(map) { kv.forEach { map[it.first] = it.second }; store.save(HashMap(map)) } }
    private fun del(vararg k: String) { synchronized(map) { k.forEach { map.remove(it) }; store.save(HashMap(map)) } }

    fun markWorking(url: String) {
        val now = System.currentTimeMillis()
        put("ok:$url" to now, "hok:${host(url)}" to now)
        del("fail:$url", "hfail:${host(url)}")
    }

    fun markFailed(url: String) {
        val now = System.currentTimeMillis()
        put("fail:$url" to now, "hfail:${host(url)}" to now)
    }

    /** O .ts tocou depois do HLS falhar: da próxima vez vai direto no .ts. */
    fun markTsOnly(url: String) = put("ts:$url" to System.currentTimeMillis())
    fun tsOnly(url: String) = get("ts:$url") > 0

    fun clear() { synchronized(map) { map.clear(); store.save(emptyMap()) } }

    /** 0 = já tocou, 1 = servidor tocou há pouco, 2 = sem histórico, 3 = servidor falhou há pouco, 4 = link falhou há pouco. */
    private fun score(url: String, now: Long): Int {
        val ok = get("ok:$url"); val fail = get("fail:$url")
        if (fail > ok && now - fail < URL_FAIL_MS) return 4
        if (ok > 0 && now - ok < OK_MS) return 0
        val h = host(url); val hok = get("hok:$h"); val hfail = get("hfail:$h")
        if (hfail > hok && now - hfail < HOST_FAIL_MS) return 3
        if (hok > 0 && now - hok < HOST_FAIL_MS) return 1
        return 2
    }

    /** Mesma lista, com as que tocaram antes e as que falharam no fim (ordem do servidor no empate). */
    fun <T> order(list: List<T>, url: (T) -> String): List<T> {
        if (list.size < 2) return list
        val now = System.currentTimeMillis()
        return list.withIndex().sortedWith(compareBy({ score(url(it.value), now) }, { it.index })).map { it.value }
    }
}
