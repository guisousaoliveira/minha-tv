package dev.guisousa.minhatv.core

import java.net.URI

/** Armazenamento chave-valor (Long) fornecido por cada plataforma (SharedPreferences no Android, arquivo no Desktop). */
interface HealthStorage {
    fun getLong(key: String): Long
    fun putLongs(entries: Map<String, Long>)
    fun removeKeys(keys: Collection<String>)
    fun allEntries(): Map<String, Long>
}

/**
 * Memória compartilhada de saúde das fontes e servidores (TV, Mobile e Desktop).
 * - Corrige colisão de chave vazia quando a URL é malformada (host() devolve null).
 * - Suporta marcação persistente de `tsOnly` (quando .ts tocou após HLS falhar).
 */
class SourceHealthCore(private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val HOST_FAIL_MS = 30 * 60_000L        // servidor que falhou fica no fim por 30 min
        const val URL_FAIL_MS = 6 * 60 * 60_000L     // link que falhou fica no fim por 6 h
        const val OK_MS = 7 * 24 * 60 * 60_000L      // link que tocou vai primeiro por 7 dias
    }

    @Volatile private var storage: HealthStorage? = null

    fun attach(store: HealthStorage) {
        storage = store
        val cutoff = clock() - OK_MS
        val stale = store.allEntries().filter { it.value < cutoff }.keys
        if (stale.isNotEmpty()) store.removeKeys(stale)
    }

    internal fun host(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun get(k: String): Long = storage?.getLong(k) ?: 0L

    fun markWorking(url: String) {
        val s = storage ?: return
        val now = clock()
        val h = host(url)
        val puts = LinkedHashMap<String, Long>(2)
        puts["ok:$url"] = now
        if (h != null) puts["hok:$h"] = now
        s.putLongs(puts)
        val dels = ArrayList<String>(2)
        dels.add("fail:$url")
        if (h != null) dels.add("hfail:$h")
        s.removeKeys(dels)
    }

    fun markFailed(url: String) {
        val s = storage ?: return
        val now = clock()
        val h = host(url)
        val puts = LinkedHashMap<String, Long>(2)
        puts["fail:$url"] = now
        if (h != null) puts["hfail:$h"] = now
        s.putLongs(puts)
    }

    /** O .ts tocou depois do HLS falhar: da próxima vez vai direto no .ts. */
    fun markTsOnly(url: String) {
        storage?.putLongs(mapOf("ts:$url" to clock()))
    }

    fun tsOnly(url: String): Boolean = get("ts:$url") > 0L

    /** 0 = já tocou, 1 = servidor tocou há pouco, 2 = sem histórico, 3 = servidor falhou há pouco, 4 = link falhou há pouco. */
    fun score(url: String, now: Long = clock()): Int {
        val ok = get("ok:$url")
        val fail = get("fail:$url")
        if (fail > ok && now - fail < URL_FAIL_MS) return 4
        if (ok > 0 && now - ok < OK_MS) return 0
        val h = host(url) ?: return 2
        val hok = get("hok:$h")
        val hfail = get("hfail:$h")
        if (hfail > hok && now - hfail < HOST_FAIL_MS) return 3
        if (hok > 0 && now - hok < HOST_FAIL_MS) return 1
        return 2
    }

    /** Mesma lista, com as que tocaram antes e as que falharam no fim (ordem do servidor no empate). */
    fun <T> order(list: List<T>, url: (T) -> String): List<T> {
        if (list.size < 2 || storage == null) return list
        val now = clock()
        return list.withIndex().sortedWith(compareBy({ score(url(it.value), now) }, { it.index })).map { it.value }
    }
}

/**
 * Atraso progressivo para reconexão de canais ao vivo:
 * - HTTP 401/403: 2,5s (tempo para o painel IPTV liberar a conexão anterior de 1 tela).
 * - Outros erros/EOF: 1s, 2s, 3s, 4s, máx 5s (evita loop em 0ms que gera ban/rate-limit).
 */
object LiveBackoff {
    fun delayMs(attempt: Int, httpCode: Int = 0): Long = when {
        httpCode == 401 || httpCode == 403 -> 2_500L
        attempt <= 1 -> 1_000L
        attempt == 2 -> 2_000L
        attempt == 3 -> 3_000L
        attempt == 4 -> 4_000L
        else -> 5_000L
    }
}
