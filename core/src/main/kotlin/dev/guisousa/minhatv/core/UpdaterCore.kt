package dev.guisousa.minhatv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Registro central de plataformas do Bad Wolf TV.
 * Para adicionar um novo app no futuro (ex.: Samsung Tizen), basta registrar o nome do arquivo aqui.
 */
enum class AppPlatform(val assetName: String, val fallbackExt: String? = null) {
    ANDROID_TV("MinhaTV.apk", ".apk"),
    ANDROID_MOBILE("Mobile-BadWolfTV.apk"),
    WINDOWS("BadWolfTV-Windows.exe"),
    WEBOS_TV("BadWolfTV-LG.ipk"),
    WEBOS_ESPORTES("BadWolfEsportes-LG.ipk"),
    TIZEN_TV("BadWolfTV-Samsung.wgt"),
}

data class AppRelease(
    val code: Int,
    val name: String,
    val downloadUrl: String,
    val notes: String = "",
    val htmlUrl: String = "",
)

/**
 * Verificador e downloader unificado de atualizações via GitHub Releases.
 * Usado por Android TV (:app), Celular (:mobile) e Windows (:desktop).
 */
object UpdaterCore {
    const val REPO = "guisousaoliveira/minha-tv"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private val http by lazy {
        OkHttpClient.Builder()
            .dns(DohDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Compara versões semânticas ("1.0.155" > "1.0.152"). */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /** Faz o parse do JSON de release do GitHub para uma plataforma específica. */
    fun parseRelease(
        jsonText: String,
        platform: AppPlatform,
        currentVersion: String,
        currentCode: Int = 0,
    ): AppRelease? {
        val o = Json.parseToJsonElement(jsonText).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
        val version = tag.removePrefix("v")
        val code = version.substringAfterLast('.').toIntOrNull() ?: 0
        val newer = if (currentCode > 0 && code > 0) code > currentCode else compareVersions(version, currentVersion) > 0
        if (!newer) return null

        val assets = o["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val chosen = assets.firstOrNull { it["name"]?.jsonPrimitive?.content == platform.assetName }
            ?: platform.fallbackExt?.let { ext ->
                assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(ext) == true }
            }
            ?: return null
        val url = chosen["browser_download_url"]?.jsonPrimitive?.content ?: return null
        val notes = o["body"]?.jsonPrimitive?.content?.trim().orEmpty()
        val htmlUrl = o["html_url"]?.jsonPrimitive?.content.orEmpty()
        return AppRelease(code = code, name = version, downloadUrl = url, notes = notes, htmlUrl = htmlUrl)
    }

    /** Consulta o GitHub Releases e retorna [AppRelease] se houver versão mais nova para [platform]. */
    suspend fun check(
        platform: AppPlatform,
        currentVersion: String,
        currentCode: Int = 0,
    ): AppRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BadWolfTV-Updater")
                .build()
            val text = http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@runCatching null
            parseRelease(text, platform, currentVersion, currentCode)
        }.getOrNull()
    }

    /** Baixa o instalador para [destFile] com callback opcional de progresso (0..100). */
    suspend fun download(
        release: AppRelease,
        destFile: File,
        onProgress: ((Int) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.path + ".tmp")
        val req = Request.Builder().url(release.downloadUrl).header("User-Agent", "BadWolfTV-Updater").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("download falhou (HTTP ${resp.code})")
            val body = resp.body ?: throw IOException("resposta vazia")
            val total = body.contentLength()
            var read = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0 && onProgress != null) {
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        }
        if (destFile.exists()) destFile.delete()
        if (!tmp.renameTo(destFile)) throw IOException("não consegui salvar o instalador")
        destFile
    }
}
