package dev.guisousa.minhatv.data

import dev.guisousa.minhatv.core.HealthStorage
import dev.guisousa.minhatv.core.SourceHealthCore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Memória de saúde das fontes no Desktop (delegada ao módulo :core, persistida em fontes.json). */
object SourceHealth {
    private val store = JsonFile(File(AppDirs.cache, "fontes.json"), MapSerializer(String.serializer(), Long.serializer())) { emptyMap() }
    private val map: MutableMap<String, Long> = HashMap(store.load())
    private val core = SourceHealthCore().apply {
        attach(object : HealthStorage {
            override fun getLong(key: String): Long = synchronized(map) { map[key] ?: 0L }
            override fun putLongs(entries: Map<String, Long>) {
                synchronized(map) { map.putAll(entries); store.save(HashMap(map)) }
            }
            override fun removeKeys(keys: Collection<String>) {
                synchronized(map) { keys.forEach { map.remove(it) }; store.save(HashMap(map)) }
            }
            override fun allEntries(): Map<String, Long> = synchronized(map) { HashMap(map) }
        })
    }

    fun markWorking(url: String) = core.markWorking(url)
    fun markFailed(url: String) = core.markFailed(url)
    fun markTsOnly(url: String) = core.markTsOnly(url)
    fun tsOnly(url: String): Boolean = core.tsOnly(url)
    fun clear() { synchronized(map) { map.clear(); store.save(emptyMap()) } }
    fun <T> order(list: List<T>, url: (T) -> String): List<T> = core.order(list, url)
}
