package com.cashflow.app.update

import java.io.InputStream
import java.security.MessageDigest

/** What `version.json` on the latest-debug release says about the newest build. */
data class VersionInfo(
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val apkUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class UpdateAvailable(val version: String) : UpdateState

    /** [progress] is 0..1, or null when the server did not report a size. */
    data class Downloading(val progress: Float?) : UpdateState
    data object ReadyToInstall : UpdateState

    /** The user has to allow "install unknown apps" for this app first. */
    data object NeedsInstallPermission : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Pure update logic (no Android): parsing, version comparison, checksum handling. */
object UpdateLogic {
    const val VERSION_URL = "https://github.com/Dragovitcmia-dot/MoneyPlan/releases/download/latest-debug/version.json"

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    /** Parses the flat JSON object CI publishes. Throws [IllegalArgumentException] if malformed. */
    fun parseVersionInfo(json: String): VersionInfo {
        val code = field(json, "versionCode")?.toLongOrNull()
            ?: throw IllegalArgumentException("version.json: missing or invalid versionCode")
        require(code >= 0) { "version.json: negative versionCode" }
        val name = stringField(json, "versionName")
        val sha = stringField(json, "sha256")
        require(SHA256.matches(sha)) { "version.json: sha256 must be 64 hex characters" }
        val url = stringField(json, "apkUrl")
        require(url.startsWith("https://")) { "version.json: apkUrl must be an https URL" }
        return VersionInfo(code, name, sha.lowercase(), url)
    }

    /** True when the published build is newer than the installed one. */
    fun isNewer(remoteVersionCode: Long, installedVersionCode: Long) = remoteVersionCode > installedVersionCode

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256Hex(bytes: ByteArray): String = hex(newDigest().digest(bytes))

    fun sha256Hex(input: InputStream): String {
        val digest = newDigest()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return hex(digest.digest())
    }

    /** Case-insensitive comparison of a computed digest with the expected one. */
    fun checksumMatches(actualHex: String, expectedHex: String) = actualHex.equals(expectedHex.trim(), ignoreCase = true)

    /** Fraction downloaded, or null when the total size is unknown. */
    fun progress(read: Long, total: Long): Float? =
        if (total <= 0) null else (read.toFloat() / total).coerceIn(0f, 1f)

    private fun field(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+)").find(json)?.groupValues?.get(1)

    private fun stringField(json: String, key: String): String {
        val raw = field(json, key) ?: throw IllegalArgumentException("version.json: missing $key")
        require(raw.startsWith("\"")) { "version.json: $key must be a string" }
        val value = raw.substring(1, raw.length - 1).replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
        require(value.isNotBlank()) { "version.json: $key is empty" }
        return value
    }
}
