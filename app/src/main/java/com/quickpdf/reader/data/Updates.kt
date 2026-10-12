package com.quickpdf.reader.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class Release(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long,
    /** "sha256:<hex>" as reported by GitHub, if available. */
    val apkDigest: String?,
)

/** Checks GitHub releases for a newer version and installs it through the system package installer. */
class Updates(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private val _available = MutableStateFlow<Release?>(null)

    /** A newer release than the installed one, once [check] found it. */
    val available: StateFlow<Release?> = _available.asStateFlow()

    val currentVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Version the user chose to skip in the home banner. */
    var dismissedVersion: String?
        get() = prefs.getString(KEY_DISMISSED, null)
        set(value) = prefs.edit().putString(KEY_DISMISSED, value).apply()

    /**
     * Asks GitHub for the latest release, at most once a day unless [force]. Returns the newer release,
     * or null if the installed version is current. Throws on network errors only when [force]d.
     */
    suspend fun check(force: Boolean): Release? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return@withContext _available.value
        val release = try {
            fetchLatest()
        } catch (e: IOException) {
            if (force) throw e
            return@withContext null
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        release.takeIf { isNewer(it.version, currentVersion) }.also { _available.value = it }
    }

    /** Downloads the release APK into the app's cache, verifying GitHub's checksum when it provides one. */
    suspend fun download(release: Release, onProgress: (done: Long, total: Long) -> Unit): File = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: throw IOException("This release has no APK")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "QuickPDF-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val expected = release.apkDigest?.removePrefix("sha256:")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && !expected.equals(actual, ignoreCase = true)) {
            file.delete()
            throw IOException("The download is damaged")
        }
        file
    }

    /** True if the user still has to allow this app to install updates (Settings › Install unknown apps). */
    fun needsInstallPermission(): Boolean = !context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(activity: Activity) {
        activity.startActivity(
            Intent(SystemSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        )
    }

    /** Hands the downloaded APK to the system installer, which also checks it's signed with the same key. */
    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun fetchLatest(): Release {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "QuickPDF/$currentVersion (Android ${Build.VERSION.RELEASE})")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
            return Release(
                version = json.getString("tag_name").removePrefix("v"),
                notes = plainNotes(json.optString("body")),
                pageUrl = json.optString("html_url", RELEASES_PAGE),
                apkUrl = apk?.optString("browser_download_url"),
                apkSize = apk?.optLong("size") ?: 0,
                apkDigest = apk?.optString("digest")?.takeIf { it.startsWith("sha256:") },
            )
        } catch (e: org.json.JSONException) {
            throw IOException("Unexpected response", e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val RELEASES_PAGE = "https://github.com/RGB-Outl4w/quickpdf-android/releases/latest"
        private const val LATEST_URL = "https://api.github.com/repos/RGB-Outl4w/quickpdf-android/releases/latest"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_DISMISSED = "dismissed"

        /** GitHub release notes (Markdown) as plain text for the update dialog. */
        fun plainNotes(markdown: String): String = markdown.lines()
            .filterNot { it.contains("Full Changelog") }
            .map { line ->
                line.replace(Regex("^#+\\s*"), "")
                    .replace(Regex("^\\s*[*-]\\s+"), "• ")
                    .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
                    .replace("**", "")
                    .replace("`", "")
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        /** Compares dotted versions numerically ("1.10.0" > "1.9.2"); a "v" prefix and suffixes are ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
