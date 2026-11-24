package dev.guisousa.minhatv.data

import dev.guisousa.minhatv.core.AppPlatform
import dev.guisousa.minhatv.core.AppRelease
import dev.guisousa.minhatv.core.UpdaterCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Atualização automática do Bad Wolf TV no Windows (via :core UpdaterCore).
 * Confere o último release no GitHub, baixa BadWolfTV-Windows.exe e abre o instalador por cima.
 */
object Updater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val currentVersion: String get() = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev"

    private val _available = MutableStateFlow<AppRelease?>(null)
    val available = _available.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun checkAsync(manual: Boolean = false) {
        if (_busy.value) return
        scope.launch {
            if (manual) _status.value = "Verificando atualizações…"
            val cur = currentVersion
            // Em modo dev local sem jpackage, só mostra resultado se for verificação manual
            if (cur == "dev" && !manual) return@launch
            val rel = UpdaterCore.check(AppPlatform.WINDOWS, if (cur == "dev") "0.0.0" else cur)
            _available.value = rel
            if (manual) {
                _status.value = if (rel != null) "Nova versão ${rel.name} disponível." else "Você já está na versão mais recente ($cur)."
            }
        }
    }

    /** Baixa o instalador e executa a atualização por cima, encerrando o app atual. */
    fun installNow(onExit: () -> Unit = { kotlin.system.exitProcess(0) }) {
        val rel = _available.value ?: return
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                _status.value = "Baixando versão ${rel.name}…"
                val dir = File(AppDirs.cache, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, "BadWolfTV-Windows-${rel.name}.exe")
                UpdaterCore.download(rel, dest) { pct -> _status.value = "Baixando versão ${rel.name}… $pct%" }
                _status.value = "Iniciando instalador…"
                ProcessBuilder(dest.absolutePath, "/SILENT", "/CLOSEAPPLICATIONS").start()
                onExit()
            } catch (e: Exception) {
                Log.w("falha ao atualizar no Windows", e)
                _status.value = "Não deu para baixar: ${e.message}"
                _busy.value = false
            }
        }
    }
}
