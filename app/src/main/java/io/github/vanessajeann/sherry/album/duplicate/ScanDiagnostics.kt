/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album.duplicate

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, app-private diagnostics. Never upload automatically or include image bytes. */
object ScanDiagnostics {
    private const val LIMIT = 48 * 1024
    @Volatile private var lastFailure = ""

    fun stage(context: Context, stage: String, done: Int, total: Int, photo: Photo? = null) {
        val text = "$stage $done/$total" + (photo?.let { " ${it.mime} ${it.width}x${it.height} bytes=${it.bytes}" } ?: "")
        context.getSharedPreferences("scan_diagnostics", Context.MODE_PRIVATE).edit().putString("stage", text).apply()
    }

    @Synchronized fun imageFailure(context: Context, stage: String, error: Throwable) {
        val signature = "$stage: ${error.javaClass.simpleName}"
        // Do not grow logs for thousands of identical corrupt-file errors.
        if (signature == lastFailure) return
        lastFailure = signature
        val prefs = context.getSharedPreferences("scan_diagnostics", Context.MODE_PRIVATE)
        val file = File(context.filesDir, "scan-errors.txt")
        val previous = if (file.exists() && file.length() < LIMIT) file.readText() else ""
        val trace = error.stackTrace.take(12).joinToString("\n")
        file.writeText((previous + "\n${Date()} ${prefs.getString("stage", "")}\n$signature\n$trace\n").takeLast(LIMIT))
    }

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { imageFailure(context, "uncaught:${thread.name}", error) } catch (_: Throwable) { }
            if (previous != null) previous.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun report(context: Context): File {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val report = File(directory, "SherryAlbum-diagnostics.txt")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val stage = context.getSharedPreferences("scan_diagnostics", Context.MODE_PRIVATE).getString("stage", "No scan yet")
        val errors = File(context.filesDir, "scan-errors.txt").let { if (it.exists()) it.readText().takeLast(LIMIT) else "No captured Java error" }
        val exits = if (Build.VERSION.SDK_INT >= 30) {
            val manager = context.getSystemService(ActivityManager::class.java)
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 5).joinToString("\n") {
                "${Date(it.timestamp)} reason=${it.reason} status=${it.status} pss=${it.pss}KB rss=${it.rss}KB ${it.description.orEmpty()}"
            }
        } else "Process exit history requires Android 11+"
        report.writeText("Sherry Album $version\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}\nLast stage: $stage\n\nExit history (includes earlier versions):\n$exits\n\nCaptured errors:\n$errors\n")
        return report
    }
}
