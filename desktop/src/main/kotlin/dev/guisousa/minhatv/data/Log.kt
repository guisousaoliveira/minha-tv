package dev.guisousa.minhatv.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Pastas do app no Windows: dados do usuário em %APPDATA%, cache (catálogo, imagens, log) em %LOCALAPPDATA%. */
object AppDirs {
    private val home = System.getProperty("user.home")
    private fun env(name: String, fallback: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

    /** Continuar assistindo, Minha lista, Já assisti e ajustes. */
    val data: File = File(env("APPDATA", "$home/AppData/Roaming"), "BadWolfTV").apply { mkdirs() }

    /** Catálogo baixado, imagens e log (pode apagar sem perder nada). */
    val cache: File = File(env("LOCALAPPDATA", "$home/AppData/Local"), "BadWolfTV").apply { mkdirs() }
}

/** Log simples: console + arquivo (%LOCALAPPDATA%\BadWolfTV\log.txt, o anterior vira log-anterior.txt). */
object Log {
    private val file = File(AppDirs.cache, "log.txt")
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val out: PrintWriter? = runCatching {
        if (file.exists()) { File(AppDirs.cache, "log-anterior.txt").delete(); file.renameTo(File(AppDirs.cache, "log-anterior.txt")) }
        PrintWriter(file.outputStream().bufferedWriter(Charsets.UTF_8), true)
    }.getOrNull()

    @Synchronized
    private fun write(level: String, msg: String, t: Throwable?) {
        val line = "${LocalDateTime.now().format(fmt)} $level $msg" + (t?.let { "\n" + StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) } } ?: "")
        System.err.println(line)
        out?.println(line)
    }

    fun i(msg: String) = write("I", msg, null)
    fun w(msg: String, t: Throwable? = null) = write("W", msg, t)
    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)
}
