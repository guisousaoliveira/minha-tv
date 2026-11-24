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
import dev.guisousa.minhatv.core.AppPlatform
import dev.guisousa.minhatv.core.AppRelease
import dev.guisousa.minhatv.core.UpdaterCore
import java.io.File

/** Confere se saiu versão nova do app da TV no GitHub (via :core UpdaterCore) e instala. */
object Updater {

    data class Release(val code: Int, val name: String, val apkUrl: String)

    val currentName: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): Release? =
        UpdaterCore.check(AppPlatform.ANDROID_TV, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            ?.let { Release(it.code, it.name, it.downloadUrl) }

    private suspend fun download(context: Context, r: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return UpdaterCore.download(AppRelease(r.code, r.name, r.apkUrl), File(dir, "MinhaTV-${r.code}.apk"))
    }

    /** Pergunta se quer atualizar; se sim, baixa e abre o instalador do Android. */
    fun offer(activity: Activity, r: Release, launch: (suspend () -> Unit) -> Unit) {
        AlertDialog.Builder(activity, dev.guisousa.minhatv.R.style.Theme_MinhaTV_Dialog)
            .setTitle("Nova versão disponível")
            .setMessage("A versão ${r.name} do Bad Wolf TV está pronta (você está na ${currentName}). Atualizar agora? Leva menos de um minuto e nada se perde.")
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
            Toast.makeText(activity, "Permita que o Bad Wolf TV instale atualizações e aperte Atualizar de novo.", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, "Vá em Configurações > Segurança e permita fontes desconhecidas para o Bad Wolf TV.", Toast.LENGTH_LONG).show()
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
