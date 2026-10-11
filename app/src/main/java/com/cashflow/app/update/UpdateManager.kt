package com.cashflow.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Manual, user-initiated self-update from the latest-debug GitHub release. No background checks,
 * no credentials: both files are public. Pure decisions live in [UpdateLogic].
 */
class UpdateManager(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private var info: VersionInfo? = null

    private val packageInfo get() = app.packageManager.getPackageInfo(app.packageName, 0)
    val installedVersionName: String get() = packageInfo.versionName ?: "?"
    val installedVersionCode: Long get() = PackageInfoCompat.getLongVersionCode(packageInfo)

    private val apkFile get() = File(File(app.cacheDir, UPDATES_DIR), "update.apk")

    fun check() {
        if (busy()) return
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = try {
                val remote = withContext(Dispatchers.IO) { UpdateLogic.parseVersionInfo(fetchText(UpdateLogic.VERSION_URL)) }
                info = remote
                if (UpdateLogic.isNewer(remote.versionCode, installedVersionCode)) {
                    UpdateState.UpdateAvailable(remote.versionName)
                } else {
                    UpdateState.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Error(describe(e))
            }
        }
    }

    fun download() {
        val remote = info
        if (remote == null || _state.value !is UpdateState.UpdateAvailable) return
        _state.value = UpdateState.Downloading(0f)
        scope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) { downloadAndVerify(remote) }
                UpdateState.ReadyToInstall
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                apkFile.delete()
                UpdateState.Error(describe(e))
            }
        }
    }

    /** Starts the system installer, or asks for the "install unknown apps" permission first. */
    fun install() {
        val s = _state.value
        if (s != UpdateState.ReadyToInstall && s != UpdateState.NeedsInstallPermission) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsInstallPermission
            return
        }
        launchInstaller()
    }

    /** Sends the user to this app's "install unknown apps" screen. */
    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    /** Call when the app returns to the foreground: continues the install once permission is granted. */
    fun onResumed() {
        if (_state.value == UpdateState.NeedsInstallPermission && app.packageManager.canRequestPackageInstalls()) {
            launchInstaller()
        }
    }

    private fun launchInstaller() {
        val file = apkFile
        if (!file.exists()) {
            _state.value = UpdateState.Error("The downloaded update is gone. Check for updates again.")
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        _state.value = UpdateState.ReadyToInstall
    }

    private fun busy() = _state.value is UpdateState.Checking || _state.value is UpdateState.Downloading

    // ---- network (always called on Dispatchers.IO) -----------------------------------------

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true // github.com -> objects.githubusercontent.com
        conn.setRequestProperty("User-Agent", "Cashflow-Updater")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("Server returned HTTP $code")
        }
        return conn
    }

    private fun fetchText(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.use { it.readBytes() }.also {
                if (it.size > MAX_JSON_BYTES) throw IOException("Unexpected response size")
            }.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadAndVerify(remote: VersionInfo) {
        val file = apkFile
        file.parentFile?.mkdirs()
        file.delete()
        val digest = UpdateLogic.newDigest()
        val conn = open(remote.apkUrl)
        try {
            val total = conn.contentLengthLong
            var read = 0L
            var lastShown = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        val fraction = UpdateLogic.progress(read, total)
                        val percent = fraction?.let { (it * 100).toInt() } ?: -1
                        if (percent != lastShown) {
                            lastShown = percent
                            _state.value = UpdateState.Downloading(fraction)
                        }
                    }
                }
            }
            if (total > 0 && read != total) throw IOException("Download was cut short")
        } finally {
            conn.disconnect()
        }
        if (!UpdateLogic.checksumMatches(UpdateLogic.hex(digest.digest()), remote.sha256)) {
            file.delete()
            throw IOException("Checksum mismatch: the download is corrupt or was tampered with. It was deleted.")
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException -> "No internet connection."
        is SocketTimeoutException -> "The server took too long to respond."
        is IllegalArgumentException -> "The update information is invalid (${e.message})."
        else -> e.message ?: "Update failed."
    }

    companion object {
        private const val UPDATES_DIR = "updates"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_JSON_BYTES = 64 * 1024

        /** Removes any downloaded APK left over from an earlier run. */
        fun deleteCachedApk(context: Context) {
            File(context.cacheDir, UPDATES_DIR).deleteRecursively()
        }
    }
}
