package com.libertad.ridedecider

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chequea si hay una versión más nueva publicada en GitHub Releases, la descarga y
 * dispara el instalador de Android. No requiere token (el repo tiene que ser público,
 * ver README): la app pública de GitHub permite leer /releases/latest sin autenticar.
 *
 * Android NO permite que una app instalada por fuera de Play Store se actualice sola
 * en silencio — es una restricción de seguridad del sistema. Lo más automático posible
 * es: detectar la versión nueva, descargarla sola, y dejarte un toque para confirmar
 * la instalación (el diálogo del instalador de Android).
 */
object UpdateChecker {

    private const val REPO = "lord-amd/ride-decider"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_FILE_NAME = "ride-decider-update.apk"

    data class UpdateInfo(val buildNumber: Int, val apkDownloadUrl: String, val releaseLabel: String)

    /** Llamar desde un hilo de UI; el chequeo de red corre en background y el callback vuelve al hilo principal. */
    fun checkForUpdate(onResult: (UpdateInfo?) -> Unit) {
        Thread {
            val info = fetchLatestRelease()
            Handler(Looper.getMainLooper()).post { onResult(info) }
        }.start()
    }

    private fun fetchLatestRelease(): UpdateInfo? = try {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(body)
        val tag = json.getString("tag_name") // formato esperado: "build-123"
        val buildNumber = tag.substringAfter("build-", "").toIntOrNull()

        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                apkUrl = asset.getString("browser_download_url")
                break
            }
        }

        if (buildNumber == null || apkUrl == null) null
        else UpdateInfo(buildNumber, apkUrl, json.optString("name", tag))
    } catch (_: Exception) {
        null // sin internet, repo todavía privado, o cualquier otro fallo de red -> simplemente no hay update disponible
    }

    /** true si el release encontrado es más nuevo que el build actual de la app instalada. */
    fun isNewer(info: UpdateInfo): Boolean = info.buildNumber > BuildConfig.BUILD_NUMBER

    /**
     * Descarga el APK con el DownloadManager del sistema (maneja solo la descarga; no
     * requiere permisos de almacenamiento porque guarda dentro del espacio propio de la
     * app) y registra un BroadcastReceiver para abrir el instalador cuando termine.
     */
    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val destFile = File(destDir, APK_FILE_NAME)
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(info.apkDownloadUrl)).apply {
            setTitle("Ride Decider — actualización")
            setDescription(info.releaseLabel)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
        }
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId == downloadId) {
                    context.unregisterReceiver(this)
                    promptInstall(context, destFile)
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    /**
     * Si falta el permiso "instalar apps desconocidas", manda a esa pantalla de Ajustes
     * en vez de abrir el instalador directamente (Android lo rechazaría igual).
     */
    private fun promptInstall(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return
        }

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }
}
