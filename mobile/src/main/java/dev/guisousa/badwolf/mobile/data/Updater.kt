package dev.guisousa.badwolf.mobile.data

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.guisousa.badwolf.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Confere se saiu versão nova no GitHub (o mesmo release da TV, que também leva o APK do celular)
 * e instala. Igual ao Updater do app da TV, mas baixando o "Mobile-BadWolfTV.apk".
 */
object Updater {

    private const val LATEST = "https://api.github.com/repos/guisousaoliveira/minha-tv/releases/latest"
    private const val APK = "Mobile-BadWolfTV.apk"

    data class Release(val code: Int, val name: String, val apkUrl: String)

    val currentName: String get() = BuildConfig.VERSION_NAME

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Nova versão disponível, ou null (sem internet também dá null). */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
            val text = http.newCall(req).execute().use { if (it.isSuccessful) it.body!!.string() else null } ?: return@runCatching null
            val o = Json.parseToJsonElement(text).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return@runCatching null
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
            val apk = o["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content == APK }
                ?.get("browser_download_url")?.jsonPrimitive?.content ?: return@runCatching null
            if (code > BuildConfig.VERSION_CODE) Release(code, tag.removePrefix("v"), apk) else null
        }.getOrNull()
    }

    /** Baixa o APK novo para o cache do app. */
    suspend fun download(context: Context, r: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "BadWolf-${r.code}.apk")
        http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("download falhou (${resp.code})")
            file.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
        file
    }

    /** Abre o instalador do Android (pede a permissão de instalar, na 1ª vez). */
    fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Permita que o Bad Wolf instale atualizações e toque em Atualizar de novo.", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, "Em Configurações > Segurança, permita fontes desconhecidas para o Bad Wolf.", Toast.LENGTH_LONG).show()
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
