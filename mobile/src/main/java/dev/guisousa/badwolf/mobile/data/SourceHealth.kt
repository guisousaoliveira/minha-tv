package dev.guisousa.badwolf.mobile.data

import android.content.Context
import android.content.SharedPreferences
import dev.guisousa.minhatv.core.HealthStorage
import dev.guisousa.minhatv.core.SourceHealthCore

/** Memória de saúde das fontes (delegada ao módulo :core, persistida em SharedPreferences). */
object SourceHealth {
    private val core = SourceHealthCore()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("fontes", Context.MODE_PRIVATE)
        prefs = p
        core.attach(object : HealthStorage {
            override fun getLong(key: String): Long = p.getLong(key, 0L)
            override fun putLongs(entries: Map<String, Long>) {
                p.edit().apply { entries.forEach { (k, v) -> putLong(k, v) } }.apply()
            }
            override fun removeKeys(keys: Collection<String>) {
                p.edit().apply { keys.forEach { remove(it) } }.apply()
            }
            override fun allEntries(): Map<String, Long> =
                p.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
        })
    }

    fun markWorking(url: String) = core.markWorking(url)
    fun markFailed(url: String) = core.markFailed(url)
    fun markTsOnly(url: String) = core.markTsOnly(url)
    fun tsOnly(url: String): Boolean = core.tsOnly(url)
    fun <T> order(list: List<T>, url: (T) -> String): List<T> = core.order(list, url)
}
