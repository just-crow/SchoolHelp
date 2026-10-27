package com.schoolhelp.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
    private var pending: File? = null

    fun onStart() {
        pending?.let { if (activity.canShowUpdate() && activity.packageManager.canRequestPackageInstalls()) install(it); return }
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
        Toast.makeText(activity, "Downloading SchoolHelp update…", Toast.LENGTH_LONG).show()
        activity.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val folder = File(activity.cacheDir, "updates").apply { mkdirs() }
                    val target = File(folder, "SchoolHelp.apk")
                    val connection = URL(address).openConnection() as HttpURLConnection
                    connection.connectTimeout = 20000; connection.readTimeout = 60000
                    try {
                        connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                        val digest = MessageDigest.getInstance("SHA-256")
                        target.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
                        check(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)) { "Update checksum did not match. Please try again." }
                        val archive = activity.packageManager.getPackageArchiveInfo(target.path, 0)
                        check(archive?.packageName == activity.packageName && archive.longVersionCode > BuildConfig.VERSION_CODE) { "This update is not compatible with the installed app." }
                        target
                    } finally { connection.disconnect() }
                }
                pending = file
                if (!activity.canShowUpdate() || !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    Toast.makeText(activity, "Update downloaded. Reopen SchoolHelp from Home to install.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    AlertDialog.Builder(activity).setTitle("Allow SchoolHelp updates")
                        .setMessage("Enable Allow from this source for SchoolHelp, then return here to confirm installation.")
                        .setNegativeButton("Later") { _, _ -> pending = null }
                        .setPositiveButton("Open settings") { _, _ -> activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }.show()
                } else install(file)
            } catch (e: Exception) {
                Toast.makeText(activity, "Update failed: ${e.message ?: "Please try again later"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun install(file: File) {
        pending = null
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
