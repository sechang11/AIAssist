package com.aitextassistant.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.aitextassistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * A build newer than the one running, as advertised by the publish script.
 *
 * @param note what changed, written by hand at publish time. The point of the
 *   whole feature is not having to ask what is in a build before installing it.
 */
data class Available(
    val versionCode: Int,
    val versionName: String,
    val note: String,
    val built: String,
    val url: String,
)

/**
 * Checking whether the box has published a newer APK, and installing it.
 *
 * There is no Play Store in this loop and there does not need to be. The build
 * machine writes latest.json next to the APK; the phone reads it, compares one
 * integer, and hands the download to the system installer. Android still shows
 * its own confirmation screen, so nothing installs behind the user's back.
 *
 * Deliberately not automatic. A rewrite tool that replaces itself mid-sentence
 * because a build landed is worse than one you tap to update.
 */
object Updates {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** How the phone identifies the build it is already running. */
    val runningVersion: Int get() = BuildConfig.VERSION_CODE
    val runningName: String get() = BuildConfig.VERSION_NAME

    /**
     * @return the newer build, or null when this one is current. Throws nothing:
     *   a failed check is not worth interrupting anyone over, and the caller
     *   gets [Outcome.Unreachable] instead.
     */
    suspend fun check(base: String): Outcome = withContext(Dispatchers.IO) {
        val root = base.trim().trimEnd('/')
        if (root.isEmpty()) return@withContext Outcome.NotConfigured

        val raw = try {
            read("$root/latest.json")
        } catch (e: Exception) {
            return@withContext Outcome.Unreachable(e.message ?: "no answer from $root")
        }

        val parsed = try {
            json.parseToJsonElement(raw) as? JsonObject
                ?: return@withContext Outcome.Unreachable("latest.json was not an object")
        } catch (e: Exception) {
            return@withContext Outcome.Unreachable("latest.json did not parse")
        }

        fun str(key: String) = (parsed[key]?.jsonPrimitive?.contentOrNullSafe()).orEmpty()
        val code = str("versionCode").toIntOrNull()
            ?: return@withContext Outcome.Unreachable("latest.json had no versionCode")

        if (code <= runningVersion) return@withContext Outcome.UpToDate
        val url = str("url").ifEmpty { "$root/remix-debug.apk" }
        Outcome.Newer(
            Available(
                versionCode = code,
                versionName = str("versionName").ifEmpty { code.toString() },
                note = str("note"),
                built = str("built"),
                url = url,
            ),
        )
    }

    sealed interface Outcome {
        data object NotConfigured : Outcome
        data object UpToDate : Outcome
        data class Newer(val build: Available) : Outcome
        data class Unreachable(val why: String) : Outcome
    }

    /**
     * Download, then hand to the system installer when it lands.
     *
     * DownloadManager rather than a plain read, because it survives the app
     * being backgrounded, shows its own progress in the shade, and gives back a
     * content:// URI the installer can already read. Writing the APK ourselves
     * would mean a FileProvider and a grant for nothing.
     *
     * @param onFailed called on the main thread if the download did not finish.
     */
    fun download(context: Context, build: Available, onFailed: (String) -> Unit) {
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: return onFailed("This phone has no download manager.")

        val request = DownloadManager.Request(Uri.parse(build.url))
            .setTitle("Remix ${build.versionName}")
            .setDescription(build.note.ifEmpty { "New build" })
            .setMimeType(APK_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "remix-${build.versionCode}.apk")

        val id = try {
            manager.enqueue(request)
        } catch (e: Exception) {
            return onFailed(e.message ?: "Could not start the download.")
        }

        // Unregistered by its own first callback; there is exactly one download
        // in flight per tap and nothing else to clean up.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try {
                    context.unregisterReceiver(this)
                } catch (e: IllegalArgumentException) {
                    // Already gone. Nothing to undo.
                }
                val uri = manager.getUriForDownloadedFile(id)
                if (uri == null) {
                    onFailed("The download did not finish.")
                } else {
                    install(context, uri)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /**
     * Android's own installer takes it from here, including asking whether this
     * app is allowed to install others. Nothing here can skip that screen, and
     * [canInstall] exists only so the UI can warn first rather than after.
     */
    fun install(context: Context, apk: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apk, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private const val APK_TYPE = "application/vnd.android.package-archive"

    private fun read(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("server said ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }
}

/** versionCode may arrive as a number or a string; both are fine. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
