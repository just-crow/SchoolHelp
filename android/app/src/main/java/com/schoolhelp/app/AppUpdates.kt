package com.schoolhelp.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Updates only from our published release, with a checksum and Android signature enforcement. */
class AppUpdates(private val activity: MainActivity) {
    private var checkedAt = 0L
    private var running = false
    private val preferences = activity.getSharedPreferences("app-updates", android.content.Context.MODE_PRIVATE)
    private var pending: File? = File(activity.cacheDir, "updates/SchoolHelp.apk")
        .takeIf { preferences.getBoolean("verified", false) && it.exists() }
    private var downloading = false
    private var installPrompt: AlertDialog? = null

    fun onStart() {
        if (downloading) return
        pending?.let {
            val version = activity.packageManager.getPackageArchiveInfo(it.path, 0)?.longVersionCode ?: 0
            if (version <= BuildConfig.VERSION_CODE) {
                pending = null; preferences.edit().remove("verified").apply(); it.delete()
            } else { if (activity.canShowUpdate()) offerInstall(it); return }
        }
        if (running || System.currentTimeMillis() - checkedAt < 6 * 60 * 60 * 1000) return
        // Never interrupt listening or generation with an installer prompt.
        if (!activity.canShowUpdate()) return
        running = true
        checkedAt = System.currentTimeMillis()
        activity.lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    JSONObject(read("https://just-crow.github.io/SchoolHelp/android-update.json"))
                }
                if (info.getLong("versionCode") <= BuildConfig.VERSION_CODE || !activity.canShowUpdate() || activity.isFinishing) return@launch
                val url = info.getString("url")
                require(url.startsWith("https://github.com/just-crow/SchoolHelp/releases/download/"))
                val sha = info.getString("sha256")
                require(sha.matches(Regex("[a-fA-F0-9]{64}")))
                AlertDialog.Builder(activity).setTitle("SchoolHelp update available")
                    .setMessage("Version ${info.getString("versionName")} is ready. Your saved answers and settings will be kept. Android will ask you to confirm installation.")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Download update") { _, _ -> download(url, sha) }.show()
            } catch (_: Exception) { /* Offline checks must not disturb normal use. */ }
            finally { running = false }
        }
    }

    private fun read(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 20000
        connection.setRequestProperty("Cache-Control", "no-cache")
        try { return connection.inputStream.bufferedReader().use { it.readText() } }
        finally { connection.disconnect() }
    }

    private fun download(address: String, expected: String) {
        if (downloading) return
        downloading = true
        preferences.edit().remove("verified").apply()
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val label = TextView(activity).apply { text = "Connecting to the update server…" }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
        }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(padding, padding / 2, padding, padding / 2)
            addView(label); addView(progress, LinearLayout.LayoutParams(-1, -2))
        }
        var job: Job? = null
        val dialog = AlertDialog.Builder(activity).setTitle("Downloading SchoolHelp update")
            .setView(view).setNegativeButton("Cancel") { _, _ -> job?.cancel() }.create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        job = activity.lifecycleScope.launch {
            val partial = File(activity.cacheDir, "updates/SchoolHelp.partial.apk")
            try {
                val file = withContext(Dispatchers.IO) {
                    val folder = File(activity.cacheDir, "updates").apply { mkdirs() }
                    val target = File(folder, "SchoolHelp.apk")
                    val connection = URL(address).openConnection() as HttpURLConnection
                    connection.connectTimeout = 20000; connection.readTimeout = 60000
                    try {
                        check(connection.responseCode == 200) { "The download server returned ${connection.responseCode}. Try again." }
                        val total = connection.contentLengthLong
                        var received = 0L
                        var lastShown = 0L
                        connection.inputStream.use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n); received += n
                                val now = System.currentTimeMillis()
                                if (now - lastShown >= 200 || received == total) {
                                    lastShown = now
                                    val bytes = received
                                    withContext(Dispatchers.Main) {
                                        progress.isIndeterminate = total <= 0
                                        val percent = if (total > 0) ((bytes * 100 / total).coerceIn(0, 100)).toInt() else 0
                                        progress.progress = percent
                                        label.text = if (total > 0) "$percent% · ${megabytes(bytes)} / ${megabytes(total)} MB" else "${megabytes(bytes)} MB downloaded"
                                    }
                                }
                            }
                        } }
                        check(received > 0 && (total <= 0 || received == total)) { "Download was incomplete. Try again." }
                        withContext(Dispatchers.Main) {
                            dialog.setTitle("Verifying update")
                            label.text = "Download complete. Checking the file before installation…"
                            progress.isIndeterminate = true
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        partial.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                digest.update(buffer, 0, n)
                            }
                        }
                        check(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)) { "Update checksum did not match. Please try again." }
                        val archive = activity.packageManager.getPackageArchiveInfo(partial.path, 0)
                        check(archive?.packageName == activity.packageName && archive.longVersionCode > BuildConfig.VERSION_CODE) { "This update is not compatible with the installed app." }
                        if (target.exists()) check(target.delete()) { "Could not replace the previous download." }
                        check(partial.renameTo(target)) { "Could not save the update." }
                        target
                    } finally { connection.disconnect() }
                }
                pending = file
                preferences.edit().putBoolean("verified", true).apply()
                dialog.dismiss()
                if (activity.canShowUpdate() && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) offerInstall(file)
                else Toast.makeText(activity, "Update ready. Return to Home and reopen SchoolHelp to install.", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) AlertDialog.Builder(activity)
                    .setTitle("Update could not finish")
                    .setMessage(e.message ?: "Check your connection and try again.")
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Retry") { _, _ -> download(address, expected) }.show()
            } finally {
                partial.delete()
                downloading = false
                dialog.dismiss()
            }
        }
    }

    private fun megabytes(bytes: Long) = String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0)

    private fun offerInstall(file: File) {
        if (installPrompt?.isShowing == true) return
        val allowed = activity.packageManager.canRequestPackageInstalls()
        installPrompt = AlertDialog.Builder(activity).setTitle("Update ready to install")
            .setMessage(if (allowed) "Download and verification finished. Tap Install update to open Android’s installation screen. Your saved answers and settings will be kept."
                else "Download and verification finished. Android needs permission to install SchoolHelp updates. Tap Allow updates, enable Allow from this source, then return here to install.")
            .setNegativeButton("Later", null)
            .setPositiveButton(if (allowed) "Install update" else "Allow updates") { _, _ ->
                try {
                    if (allowed) install(file)
                    else activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                } catch (e: Exception) {
                    AlertDialog.Builder(activity).setTitle("Installation could not open").setMessage(e.message).setPositiveButton("OK", null).show()
                }
            }.show()
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
