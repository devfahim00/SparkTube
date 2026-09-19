package com.sparktube.app.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.sparktube.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local, Firebase-free crash reporting.
 *
 * Why this exists: the app used to ship Firebase Crashlytics, which required
 * app/google-services.json in the repo — a file that contains the Firebase
 * web API key. In an open-source repo that key can never be kept secret, so
 * GitHub secret scanning flags it on every push. Instead of fighting that,
 * crash reporting now works entirely on-device:
 *
 *  - When the app crashes, an uncaught-exception handler writes a plain-text
 *    report (stack trace + device info + recent breadcrumbs) into a folder
 *    named "SparkTube" on the user's device.
 *  - Android 10+ (API 29+): the folder is Download/SparkTube, created through
 *    MediaStore — visible in any file manager, no permissions needed.
 *  - Android 9 and below: the app-specific external dir
 *    (Android/data/com.sparktube.app/files/SparkTube), with the internal
 *    files dir as the last-resort fallback. Both always work without any
 *    permission, and logs can be shared via Settings → Crash logs.
 *  - Users can view / share / delete logs from Settings → Crash logs, so a
 *    report can simply be sent (e.g. via Telegram) whenever something breaks.
 *
 * Everything here is best-effort: the crash handler itself must NEVER throw,
 * otherwise it would crash the crash reporter and eat the original exception.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    /** Folder name used on the user's device (Download/SparkTube on API 29+). */
    const val FOLDER_NAME = "SparkTube"

    private const val FILE_PREFIX = "sparktube-crash-"
    private const val FILE_SUFFIX = ".log"

    /** FileProvider authority (see AndroidManifest + res/xml/file_paths.xml). */
    private const val FILE_PROVIDER_AUTHORITY = "com.sparktube.app.fileprovider"

    /** Keep at most this many crash files per storage location. */
    private const val MAX_FILES = 20

    /** Hard cap on report size — stack traces can explode with deep recursion. */
    private const val MAX_REPORT_CHARS = 64_000

    /** Number of recent breadcrumb lines kept for the next crash report. */
    private const val MAX_BREADCRUMBS = 30

    /** Elapsed-realtime of [install] — used to report process uptime. */
    @Volatile
    private var startedAt = 0L

    /** Lightweight "what just happened" lines, included in the next report. */
    private val breadcrumbs = ArrayDeque<String>()

    /** A crash log file found on the device, ready to be shared. */
    data class CrashLogFile(
        val displayName: String,
        val uri: Uri,
        val timeMs: Long,
        val sizeBytes: Long,
        val location: String
    )

    /**
     * Installs the uncaught-exception handler and prunes old logs. Call this
     * as the FIRST thing in Application.onCreate so that even a crash during
     * later init (prefs, players, extractor) still produces a report.
     */
    fun install(context: Context) {
        startedAt = SystemClock.elapsedRealtime()
        val appCtx = context.applicationContext

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(appCtx, thread, throwable)
            } catch (t: Throwable) {
                // Swallow on purpose — never let the reporter kill the crash.
                Log.e(TAG, "Failed to write crash log", t)
            }
            // Hand control back to the platform handler so the normal crash
            // dialog / process death behavior is preserved.
            previous?.uncaughtException(thread, throwable)
        }

        pruneOldLogs(appCtx)
    }

    /**
     * Records a short breadcrumb line (like Crashlytics "logs") that will be
     * attached to the next crash report — handy context for reproduction.
     */
    fun logBreadcrumb(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(breadcrumbs) {
            breadcrumbs.addFirst("[$stamp] $message")
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeLast()
        }
    }

    // ------------------------------------------------------------------ //
    //  Crash log writing
    // ------------------------------------------------------------------ //

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val report = buildReport(context, thread, throwable)
        val fileName = FILE_PREFIX +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + FILE_SUFFIX

        val written: String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Preferred: user-visible Download/SparkTube via MediaStore.
                writeViaMediaStore(context, fileName, report)
                    ?: writeToFileDir(context, fileName, report)
            } else {
                writeToFileDir(context, fileName, report)
            }

        if (written != null) {
            Log.e(TAG, "Crash log saved to $written")
        } else {
            Log.e(TAG, "Crash log could not be written anywhere")
        }
    }

    /** The plain-text report body for one crash. */
    private fun buildReport(context: Context, thread: Thread, throwable: Throwable): String {
        val now = Date()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(now)

        // AppPrefs may not be initialized yet (crash during Application init),
        // so read it defensively — a missing value must not lose the report.
        val country = try {
            AppPrefs.countryOrDefault
        } catch (t: Throwable) {
            "<unavailable>"
        }

        val rt = SystemClock.elapsedRealtime() - startedAt
        val uptime = String.format(
            Locale.US, "%02d:%02d:%02d",
            rt / 3_600_000, (rt / 60_000) % 60, (rt / 1_000) % 60
        )

        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val threadStack = thread.stackTrace.joinToString("\n") { "    at $it" }

        val runtime = Runtime.getRuntime()

        val sb = StringBuilder()
        sb.append("SparkTube crash report\n")
        sb.append("========================================\n")
        sb.append("Time:    ").append(stamp).append('\n')
        sb.append("App:     ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(") ")
            .append(if (BuildConfig.DEBUG) "debug" else "release").append('\n')
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Device:  ").append(Build.MANUFACTURER).append(' ')
            .append(Build.MODEL).append('\n')
        sb.append("Country: ").append(country).append('\n')
        sb.append("Thread:  ").append(thread.name)
            .append(" (id ").append(thread.id)
            .append(", priority ").append(thread.priority).append(")\n")
        sb.append("Uptime:  ").append(uptime).append('\n')
        sb.append("Memory:  free ").append(runtime.freeMemory() / 1024).append(" KB / total ")
            .append(runtime.totalMemory() / 1024).append(" KB (max ")
            .append(runtime.maxMemory() / 1024).append(" KB)\n")

        synchronized(breadcrumbs) {
            if (breadcrumbs.isNotEmpty()) {
                sb.append("\nRecent activity:\n")
                breadcrumbs.forEach { sb.append("  ").append(it).append('\n') }
            }
        }

        sb.append("\n--- Exception ---\n")
        sb.append(trace).append('\n')
        sb.append("\n--- Crashing thread stack ---\n")
        sb.append(threadStack).append('\n')

        if (sb.length > MAX_REPORT_CHARS) sb.setLength(MAX_REPORT_CHARS)
        return sb.toString()
    }

    /**
     * API 29+ path: MediaStore.Downloads with RELATIVE_PATH Download/SparkTube.
     * Creates the folder on demand and needs no permission — the app owns the
     * files it inserts. Returns a human-readable location, or null on failure.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(context: Context, fileName: String, report: String): String? {
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + FOLDER_NAME
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val stream = resolver.openOutputStream(uri, "w")
            if (stream == null) {
                resolver.delete(uri, null, null)
                return null
            }
            stream.use { it.write(report.toByteArray(Charsets.UTF_8)) }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            "Download/$FOLDER_NAME/$fileName"
        } catch (t: Throwable) {
            Log.e(TAG, "MediaStore write failed", t)
            null
        }
    }

    /**
     * File-system path used below Android 10 (or when MediaStore fails):
     * app-specific external dir first, internal files dir as last resort.
     * Both are always writable without permissions.
     */
    private fun writeToFileDir(context: Context, fileName: String, report: String): String? {
        val dirs = ArrayList<File>(2)
        context.getExternalFilesDir(null)?.let { dirs.add(File(it, FOLDER_NAME)) }
        dirs.add(File(context.filesDir, FOLDER_NAME))

        for (dir in dirs) {
            try {
                if (dir.isDirectory || dir.mkdirs()) {
                    val target = File(dir, fileName)
                    target.writeText(report, Charsets.UTF_8)
                    return target.absolutePath
                }
            } catch (t: Throwable) {
                Log.e(TAG, "File write failed at ${dir.absolutePath}", t)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ //
    //  Listing / sharing / deletion (Settings → Crash logs)
    // ------------------------------------------------------------------ //

    /** All crash log files on the device, newest first. */
    fun listLogs(context: Context): List<CrashLogFile> {
        val result = ArrayList<CrashLogFile>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result.addAll(listMediaStore(context))
        }

        val dirs = ArrayList<File>(2)
        context.getExternalFilesDir(null)?.let { dirs.add(File(it, FOLDER_NAME)) }
        dirs.add(File(context.filesDir, FOLDER_NAME))
        for (dir in dirs) {
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
            } ?: continue
            for (f in files) {
                result.add(
                    CrashLogFile(
                        displayName = f.name,
                        uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, f),
                        timeMs = f.lastModified(),
                        sizeBytes = f.length(),
                        location = dir.absolutePath
                    )
                )
            }
        }
        return result.sortedByDescending { it.timeMs }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listMediaStore(context: Context): List<CrashLogFile> {
        val out = ArrayList<CrashLogFile>()
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE
            )
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                logSelection(),
                logSelectionArgs(),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out.add(
                        CrashLogFile(
                            displayName = name,
                            uri = ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                            ),
                            // DATE_MODIFIED is second-granularity.
                            timeMs = c.getLong(2) * 1000L,
                            sizeBytes = c.getLong(3),
                            location = "Download/$FOLDER_NAME"
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "MediaStore list failed", t)
        }
        return out
    }

    /** Deletes every crash log from all locations; returns how many went away. */
    fun deleteAllLogs(context: Context): Int {
        var deleted = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                deleted += context.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    logSelection(),
                    logSelectionArgs()
                )
            } catch (t: Throwable) {
                Log.e(TAG, "MediaStore delete failed", t)
            }
        }

        val dirs = ArrayList<File>(2)
        context.getExternalFilesDir(null)?.let { dirs.add(File(it, FOLDER_NAME)) }
        dirs.add(File(context.filesDir, FOLDER_NAME))
        for (dir in dirs) {
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
            } ?: continue
            for (f in files) {
                try {
                    if (f.delete()) deleted++
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to delete ${f.absolutePath}", t)
                }
            }
        }
        return deleted
    }

    // ------------------------------------------------------------------ //
    //  Retention
    // ------------------------------------------------------------------ //

    /** Keeps only the newest [MAX_FILES] crash files per location. */
    private fun pruneOldLogs(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    logSelection(),
                    logSelectionArgs(),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { c ->
                    val excess = ArrayList<Uri>()
                    var seen = 0
                    while (c.moveToNext()) {
                        if (seen++ < MAX_FILES) continue
                        excess.add(
                            ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                            )
                        )
                    }
                    excess.forEach { uri ->
                        try {
                            context.contentResolver.delete(uri, null, null)
                        } catch (t: Throwable) {
                            Log.e(TAG, "MediaStore prune failed", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "MediaStore prune query failed", t)
            }
        }

        val dirs = ArrayList<File>(2)
        context.getExternalFilesDir(null)?.let { dirs.add(File(it, FOLDER_NAME)) }
        dirs.add(File(context.filesDir, FOLDER_NAME))
        for (dir in dirs) {
            try {
                val files = dir.listFiles { f ->
                    f.isFile && f.name.startsWith(FILE_PREFIX)
                } ?: continue
                files.sortByDescending { it.lastModified() }
                files.drop(MAX_FILES).forEach { it.delete() }
            } catch (t: Throwable) {
                Log.e(TAG, "Prune failed for ${dir.absolutePath}", t)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    private fun logSelection(): String =
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"

    private fun logSelectionArgs(): Array<String> =
        arrayOf(
            Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME + "%",
            "$FILE_PREFIX%"
        )
}
