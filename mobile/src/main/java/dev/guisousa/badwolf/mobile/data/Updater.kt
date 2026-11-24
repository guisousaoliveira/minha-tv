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
import dev.guisousa.minhatv.core.AppPlatform
import dev.guisousa.minhatv.core.AppRelease
import dev.guisousa.minhatv.core.UpdaterCore
import java.io.File

/** Confere se saiu versão nova do app de celular no GitHub (via :core UpdaterCore) e instala. */
object Updater {

    data class Release(val code: Int, val name: String, val apkUrl: String)

    val currentName: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): Release? =
        UpdaterCore.check(AppPlatform.ANDROID_MOBILE, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            ?.let { Release(it.code, it.name, it.downloadUrl) }

    suspend fun download(context: Context, r: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return UpdaterCore.download(AppRelease(r.code, r.name, r.apkUrl), File(dir, "BadWolf-${r.code}.apk"))
    }

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
