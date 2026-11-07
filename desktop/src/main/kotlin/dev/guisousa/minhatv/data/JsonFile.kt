package dev.guisousa.minhatv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Um objeto guardado num arquivo JSON. Gravação atrasada (junta várias mudanças seguidas)
 * e atômica (escreve num .tmp e troca), para não corromper se o app fechar no meio.
 */
class JsonFile<T>(private val file: File, private val ser: KSerializer<T>, private val default: () -> T) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; prettyPrint = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    fun load(): T = runCatching {
        if (file.exists()) json.decodeFromString(ser, file.readText(Charsets.UTF_8)) else default()
    }.onFailure { Log.w("não li ${file.name}, começando do zero", it) }.getOrElse { default() }

    /** Grava daqui a pouco (várias chamadas seguidas viram uma gravação só). */
    @Synchronized
    fun save(value: T) {
        pending?.cancel()
        pending = scope.launch { delay(400); writeNow(value) }
    }

    @Synchronized
    fun writeNow(value: T) {
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(ser, value), Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.w("não gravei ${file.name}", it) }
    }
}
