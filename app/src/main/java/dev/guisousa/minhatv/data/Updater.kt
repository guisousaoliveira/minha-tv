package dev.guisousa.minhatv.data

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.guisousa.minhatv.BuildConfig
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

/** Confere se saiu versão nova do app no GitHub e instala. */
object Updater {

    private const val LATEST = "https://api.github.com/repos/guisousaoliveira/minha-tv/releases/latest"

    data class Release(val code: Int, val name: String, val apkUrl: String)

    val currentName: String get() = BuildConfig.VERSION_NAME

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Nova versão disponível, ou null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
            val text = http.newCall(req).execute().use { if (it.isSuccessful) it.body!!.string() else null } ?: return@runCatching null
            val o = Json.parseToJsonElement(text).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return@runCatching null
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
            // o release também tem o APK do celular e o instalador do Windows: pega o da TV pelo nome
            val assets = o["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            val apk = (assets.firstOrNull { it["name"]?.jsonPrimitive?.content == "MinhaTV.apk" }
                ?: assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true })
                ?.get("browser_download_url")?.jsonPrimitive?.content ?: return@runCatching null
            if (code > BuildConfig.VERSION_CODE) Release(code, tag.removePrefix("v"), apk) else null
        }.getOrNull()
    }

    private suspend fun download(context: Context, r: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "MinhaTV-${r.code}.apk")
        http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("download falhou (${resp.code})")
            file.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
        file
    }

    /** Pergunta se quer atualizar; se sim, baixa e abre o instalador do Android. */
    fun offer(activity: Activity, r: Release, launch: (suspend () -> Unit) -> Unit) {
        AlertDialog.Builder(activity, dev.guisousa.minhatv.R.style.Theme_MinhaTV_Dialog)
            .setTitle("Nova versão disponível")
            .setMessage("A versão ${r.name} do Minha TV está pronta (você está na ${currentName}). Atualizar agora? Leva menos de um minuto e nada se perde.")
            .setPositiveButton("Atualizar") { _, _ ->
                Toast.makeText(activity, "Baixando atualização…", Toast.LENGTH_LONG).show()
                launch {
                    try {
                        install(activity, download(activity, r))
                    } catch (e: Exception) {
                        Toast.makeText(activity, "Não deu para baixar: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Permita que o Minha TV instale atualizações e aperte Atualizar de novo.", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, "Vá em Configurações > Segurança e permita fontes desconhecidas para o Minha TV.", Toast.LENGTH_LONG).show()
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}
