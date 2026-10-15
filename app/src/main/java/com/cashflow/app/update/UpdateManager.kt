package com.cashflow.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.pm.PackageInfoCompat
import com.cashflow.app.R
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

/** A failure whose message is a string resource, so the UI text stays translatable. */
class UpdateFailure(@StringRes val messageId: Int, vararg val args: Any) : IOException()

/**
 * User-initiated self-update from the latest-debug GitHub release. One tap on "Check for updates"
 * checks, downloads, verifies the sha256 and installs. No background checks, no credentials.
 * Pure decisions live in [UpdateLogic]. Installation uses PackageInstaller sessions, so once this
 * app is the installer of record (Android 12+) later updates can apply without a confirmation.
 */
class UpdateManager(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private var pending: VersionInfo? = null

    private val packageInfo get() = app.packageManager.getPackageInfo(app.packageName, 0)
    val installedVersionName: String get() = packageInfo.versionName ?: "?"
    val installedVersionCode: Long get() = PackageInfoCompat.getLongVersionCode(packageInfo)

    private val apkFile get() = File(File(app.cacheDir, UPDATES_DIR), "update.apk")

    /** One tap: check; if a newer version exists, download, verify and install it. */
    fun check() {
        if (busy()) return
        _state.value = UpdateState.Checking
        scope.launch {
            try {
                val remote = withContext(Dispatchers.IO) { UpdateLogic.parseVersionInfo(fetchText(com.cashflow.app.BuildConfig.UPDATE_VERSION_URL)) }
                when (val decision = UpdateLogic.decide(remote, installedVersionCode, app.packageManager.canRequestPackageInstalls())) {
                    UpdateLogic.Decision.UpToDate -> _state.value = UpdateState.UpToDate
                    is UpdateLogic.Decision.NeedsPermission -> {
                        pending = decision.info
                        _state.value = UpdateState.NeedsInstallPermission(decision.info.versionName)
                    }
                    is UpdateLogic.Decision.Download -> downloadAndInstall(decision.info)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** The user read the explanation and agreed: open this app's "install unknown apps" screen. */
    fun openInstallPermissionSettings() {
        val info = pending ?: return
        _state.value = UpdateState.WaitingForPermission(info.versionName)
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    /** "Not now" on the explanation. */
    fun dismissPermissionPrompt() {
        pending = null
        _state.value = UpdateState.Idle
    }

    /** Call when the app returns to the foreground: continues automatically once the permission was granted. */
    fun onResumed() {
        val info = pending ?: return
        val s = _state.value
        if ((s is UpdateState.WaitingForPermission || s is UpdateState.NeedsInstallPermission) && app.packageManager.canRequestPackageInstalls()) {
            scope.launch { downloadAndInstall(info) }
        } else if (s is UpdateState.WaitingForPermission) {
            _state.value = UpdateState.NeedsInstallPermission(info.versionName) // came back without allowing it
        }
    }

    /** Called by [UpdateStatusReceiver] with the PackageInstaller session result. */
    fun onInstallStatus(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and restarted by the system
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Error(app.getString(R.string.update_cancelled))
            else -> _state.value = UpdateState.Error(app.getString(R.string.update_error_install, message ?: status.toString()))
        }
    }

    private suspend fun downloadAndInstall(info: VersionInfo) {
        _state.value = UpdateState.Downloading(info.versionName, 0f)
        try {
            withContext(Dispatchers.IO) { downloadAndVerify(info) }
            // The notice is shown now; the system takes over right after.
            _state.value = UpdateState.Installing(info.versionName)
            withContext(Dispatchers.IO) { commitInstallSession() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            apkFile.delete()
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        _state.value = UpdateState.Error(describe(e))
    }

    private fun busy() = _state.value.let {
        it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing
    }

    // ---- install (PackageInstaller session) --------------------------------------------------

    private fun commitInstallSession() {
        val file = apkFile
        if (!file.exists()) throw UpdateFailure(R.string.update_file_gone)
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Once we are the installer of record, updates apply without a confirmation screen.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(app, UpdateStatusReceiver::class.java).setAction(UpdateStatusReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(app, sessionId, intent, flags).intentSender)
        }
    }

    // ---- network (always on Dispatchers.IO) ---------------------------------------------------

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true // github.com -> objects.githubusercontent.com
        conn.setRequestProperty("User-Agent", "Cashflow-Updater")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw UpdateFailure(R.string.update_error_server, code)
        }
        return conn
    }

    private fun fetchText(url: String): String {
        val conn = open(url)
        try {
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_JSON_BYTES) throw UpdateFailure(R.string.update_error_invalid)
            return bytes.toString(Charsets.UTF_8)
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
                            _state.value = UpdateState.Downloading(remote.versionName, fraction)
                        }
                    }
                }
            }
            if (total > 0 && read != total) throw UpdateFailure(R.string.update_error_cut)
        } finally {
            conn.disconnect()
        }
        if (!UpdateLogic.checksumMatches(UpdateLogic.hex(digest.digest()), remote.sha256)) {
            file.delete()
            throw UpdateFailure(R.string.update_error_checksum)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UpdateFailure -> app.getString(e.messageId, *e.args)
        is UnknownHostException -> app.getString(R.string.update_error_no_internet)
        is SocketTimeoutException -> app.getString(R.string.update_error_timeout)
        is IllegalArgumentException -> app.getString(R.string.update_error_invalid)
        else -> app.getString(R.string.update_error_generic)
    }

    companion object {
        private const val UPDATES_DIR = "updates"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_JSON_BYTES = 64 * 1024

        /** Removes any downloaded APK left over from an earlier run. */
        fun deleteCachedApk(context: Context) {
            File(context.cacheDir, UPDATES_DIR).deleteRecursively()
        }
    }
}
