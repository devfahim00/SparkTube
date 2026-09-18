package com.sparktube.app.download

import android.content.Context
import android.widget.Toast
import com.sparktube.app.R
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.data.LocalStore
import com.sparktube.app.net.OkHttpDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Built-in download engine — no system DownloadManager.
 *
 * Every file is fetched with several parallel HTTP range connections (like
 * classic desktop accelerators), which makes full use of the available
 * bandwidth and finishes downloads several times faster than a single
 * stream, especially on high-latency links.
 *
 * Modes:
 *  - AUDIO  : a single audio stream file (m4a)
 *  - VIDEO  : a single video file (muxed mp4 when available, otherwise a
 *             higher-quality video-only mp4)
 *  - AV     : a video-only file + an audio file downloaded as a pair (both
 *             in parallel); they are played back together inside SparkTube
 */
object DownloadCenter {

    const val TYPE_AUDIO = "AUDIO"
    const val TYPE_VIDEO = "VIDEO"
    const val TYPE_AV = "AV"

    const val STATUS_PENDING = "PENDING"
    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_DONE = "DONE"
    const val STATUS_FAILED = "FAILED"

    /** Parallel connections per file (acceleration factor). */
    private const val THREADS_PER_FILE = 6

    /** Minimum file size (bytes) below which parallel splitting is pointless. */
    private const val MIN_SIZE_FOR_SPLIT = 2L * 1024L * 1024L

    /** Chunk retry attempts before a download fails. */
    private const val CHUNK_RETRIES = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Shared engine client: generous dispatcher + connection pool for parallel range requests. */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 24
            }
        )
        .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
        .build()

    private val jobIdSeq = AtomicLong(1L)

    /** Live jobs by internal job id — the replacement for DownloadManager ids. */
    private val jobs = ConcurrentHashMap<Long, DownloadJob>()

    private var appCtx: Context? = null

    /** All registry records belonging to one video page. */
    fun recordsFor(context: Context, videoUrl: String): List<DownloadRecord> {
        val id = videoIdOf(videoUrl)
        return LocalStore.downloads(context).filter { it.videoId == id }
    }

    /** Whether the video page should show "Downloaded" instead of the option. */
    fun isDownloaded(context: Context, videoUrl: String): Boolean =
        recordsFor(context, videoUrl).any { it.status == STATUS_DONE }

    fun hasActiveDownload(context: Context, videoUrl: String): Boolean =
        recordsFor(context, videoUrl).any {
            it.status == STATUS_PENDING || it.status == STATUS_RUNNING
        }

    /**
     * Aggregate progress (0..100) across all active records of one video.
     * Returns -1 when nothing is downloading.
     */
    fun downloadProgress(context: Context, videoUrl: String): Int {
        val active = recordsFor(context, videoUrl)
            .filter { it.status == STATUS_PENDING || it.status == STATUS_RUNNING }
        if (active.isEmpty()) return -1
        var done = 0L
        var total = 0L
        active.forEach { record ->
            record.downloadIds.forEach { id ->
                val job = jobs[id]
                if (job != null) {
                    done += job.progress.get()
                    total += job.size
                }
            }
        }
        if (total <= 0L) return 0
        return ((done * 100) / total).toInt().coerceIn(0, 100)
    }

    /** Deletes every download (files + registry). */
    fun clearAll(context: Context) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        LocalStore.downloads(context).forEach { record ->
            record.filePaths.forEach { path ->
                runCatching { File(path).delete() }
            }
        }
        LocalStore.clearDownloads(context)
    }

    /** Extracts a safe file-system friendly video id from a video URL. */
    fun videoIdOf(url: String): String {
        val fallback = url.replace(Regex("[^A-Za-z0-9_-]"), "").takeLast(20)
        val vParam = Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
        val shortLink = Regex("youtu\\.be/([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
        return (vParam ?: shortLink ?: fallback).take(20)
    }

    private fun dirOf(type: String): String = when (type) {
        TYPE_AUDIO -> android.os.Environment.DIRECTORY_MUSIC
        else -> android.os.Environment.DIRECTORY_MOVIES
    }

    /** @return the local file path of the destination. */
    private fun destinationPath(context: Context, fileName: String, type: String): String {
        val base = context.getExternalFilesDir(dirOf(type)) ?: context.filesDir
        return File(File(base, "SparkTube"), fileName).absolutePath
    }

    /** Starts a single-file download (AUDIO or VIDEO). */
    fun startSingle(
        context: Context,
        videoUrl: String,
        title: String,
        uploader: String,
        thumbUrl: String,
        type: String,
        quality: String,
        streamUrl: String,
        fileExt: String
    ) {
        appCtx = context.applicationContext
        val videoId = videoIdOf(videoUrl)
        val fileName = "${videoId}_${type}_${quality.replace(" ", "")}.$fileExt"
        val path = destinationPath(context, fileName, type)
        val job = DownloadJob(streamUrl, File(path))
        jobs[job.id] = job
        job.start()
        LocalStore.addDownload(
            context,
            DownloadRecord(
                videoId = videoId,
                title = title,
                uploader = uploader,
                thumbnailUrl = thumbUrl,
                type = type,
                quality = quality,
                status = STATUS_PENDING,
                filePaths = listOf(path),
                downloadIds = listOf(job.id)
            )
        )
    }

    /** Starts a video+audio pair download (AV) — both files download in parallel. */
    fun startPair(
        context: Context,
        videoUrl: String,
        title: String,
        uploader: String,
        thumbUrl: String,
        quality: String,
        videoStreamUrl: String,
        audioStreamUrl: String
    ) {
        appCtx = context.applicationContext
        val videoId = videoIdOf(videoUrl)
        val videoJob = DownloadJob(videoStreamUrl, File(destinationPath(context, "${videoId}_AV_${quality.replace(" ", "")}_v.mp4", TYPE_AV)))
        val audioJob = DownloadJob(audioStreamUrl, File(destinationPath(context, "${videoId}_AV_${quality.replace(" ", "")}_a.m4a", TYPE_AV)))
        jobs[videoJob.id] = videoJob
        jobs[audioJob.id] = audioJob
        videoJob.start()
        audioJob.start()
        LocalStore.addDownload(
            context,
            DownloadRecord(
                videoId = videoId,
                title = title,
                uploader = uploader,
                thumbnailUrl = thumbUrl,
                type = TYPE_AV,
                quality = quality,
                status = STATUS_PENDING,
                filePaths = listOf(videoJob.file.absolutePath, audioJob.file.absolutePath),
                downloadIds = listOf(videoJob.id, audioJob.id)
            )
        )
    }

    /** Deletes the files of a record and removes it from the registry. */
    fun delete(context: Context, record: DownloadRecord) {
        record.downloadIds.forEach { id ->
            jobs.remove(id)?.cancel()
        }
        record.filePaths.forEach { path ->
            runCatching { File(path).delete() }
        }
        LocalStore.removeDownload(context, record)
    }

    /**
     * Re-syncs statuses of unfinished downloads with the live jobs. A record
     * turns DONE when every job finished and FAILED when any job failed (or
     * when its jobs vanished — e.g. the process was killed mid-download).
     */
    fun refreshStatuses(context: Context) {
        LocalStore.downloads(context).filter { it.status != STATUS_DONE && it.status != STATUS_FAILED }
            .forEach { record ->
                val recordJobs = record.downloadIds.mapNotNull { jobs[it] }
                val newStatus = when {
                    recordJobs.isEmpty() -> STATUS_FAILED
                    recordJobs.any { it.failed.get() } -> STATUS_FAILED
                    recordJobs.all { it.finished.get() } -> STATUS_DONE
                    else -> STATUS_RUNNING
                }
                if (newStatus != record.status) {
                    LocalStore.updateDownloadStatus(context, record.downloadIds.first(), newStatus)
                    recordJobs.forEach { jobs.remove(it.id) }
                    if (newStatus == STATUS_DONE) {
                        announceDone(context)
                    }
                }
            }
    }

    private fun announceDone(context: Context) {
        val ctx = appCtx ?: context.applicationContext
        Toast.makeText(ctx, R.string.download_status_done, Toast.LENGTH_SHORT).show()
    }

    /**
     * One parallel-accelerated file download. Small files (or servers without
     * range support) gracefully fall back to a single connection.
     */
    private inner class DownloadJob(val url: String, val file: File) {
        val id = jobIdSeq.incrementAndGet()

        val progress = AtomicLong(0L)
        /** Total size, filled in once the first probe response arrives. */
        @Volatile
        var size: Long = 0L

        val finished = AtomicBoolean(false)
        val failed = AtomicBoolean(false)

        private var job: Job? = null
        private val cancelled = AtomicBoolean(false)

        fun start() {
            file.parentFile?.mkdirs()
            // Never resume a stale partial file: start clean.
            runCatching { if (file.exists()) file.delete() }
            job = scope.launch {
                val ok = runCatching { downloadInternal() }
                    .getOrElse { false }
                if (ok && !cancelled.get()) {
                    finished.set(true)
                } else {
                    failed.set(true)
                    runCatching { file.delete() }
                }
            }
        }

        fun cancel() {
            cancelled.set(true)
            job?.cancel()
            runCatching { file.delete() }
        }

        private suspend fun downloadInternal(): Boolean = withContext(Dispatchers.IO) {
            // 1. Probe: total size + range support with a 1-byte range request.
            val probe = newRequest()
                .header("Range", "bytes=0-0")
                .build()
            val totalSize: Long
            val rangesSupported: Boolean
            client.newCall(probe).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val contentRange = resp.header("Content-Range")
                val acceptRanges = resp.header("Accept-Ranges")
                totalSize = contentRange
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?: -1L
                rangesSupported = (contentRange != null) ||
                    (acceptRanges?.equals("bytes", ignoreCase = true) == true)
            }

            if (totalSize <= 0L) {
                // Unknown length: single plain stream copy.
                size = 0L
                return@withContext plainDownload()
            }

            size = totalSize
            if (!rangesSupported || totalSize < MIN_SIZE_FOR_SPLIT) {
                return@withContext segmentedDownload(0L, totalSize - 1L)
            }

            // 2. Parallel range downloads.
            val chunkCount = THREADS_PER_FILE
            val bounds = (0 until chunkCount).map { i ->
                val start = totalSize * i / chunkCount
                val end = totalSize * (i + 1) / chunkCount - 1
                start to end
            }
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(totalSize)
            }
            val results = bounds.map { (start, end) ->
                launchSegment(start, end)
            }
            // Join every segment in order; collect their success flags.
            results.map { segJob ->
                segJob.job.join()
                segJob.completed.get()
            }.all { it }
        }

        private fun newRequest(): Request.Builder =
            Request.Builder()
                .url(url)
                .header("User-Agent", OkHttpDownloader.USER_AGENT)

        /** Plain single-connection download (no size known / no ranges). */
        private suspend fun plainDownload(): Boolean = withContext(Dispatchers.IO) {
            try {
                val request = newRequest().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    val body = resp.body ?: return@withContext false
                    file.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled.get()) return@withContext false
                                val read = input.read(buf)
                                if (read < 0) break
                                out.write(buf, 0, read)
                                progress.addAndGet(read.toLong())
                            }
                            out.flush()
                        }
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        /** One contiguous byte range, written at its offset. Retries a few times. */
        private fun launchSegment(start: Long, end: Long): SegmentJob {
            val completedFlag = AtomicBoolean(false)
            val segJob = scope.launch(Dispatchers.IO) {
                var attempt = 0
                while (attempt <= CHUNK_RETRIES && !cancelled.get()) {
                    try {
                        val request = newRequest()
                            .header("Range", "bytes=$start-$end")
                            .build()
                        client.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                            val body = resp.body ?: throw IOException("Empty body")
                            RandomAccessFile(file, "rw").use { raf ->
                                raf.seek(start)
                                val input = body.byteStream()
                                val buf = ByteArray(64 * 1024)
                                var written = 0L
                                val expected = end - start + 1
                                while (written < expected) {
                                    if (cancelled.get()) return@launch
                                    val read = input.read(buf, 0, minOf(buf.size.toLong(), expected - written).toInt())
                                    if (read < 0) throw IOException("Unexpected end of stream")
                                    raf.write(buf, 0, read)
                                    written += read.toLong()
                                    progress.addAndGet(read.toLong())
                                }
                            }
                            completedFlag.set(true)
                            return@launch
                        }
                    } catch (e: Exception) {
                        attempt++
                        if (attempt > CHUNK_RETRIES) break
                        // Exponential-ish backoff between retries.
                        try { Thread.sleep(300L * attempt) } catch (_: InterruptedException) {}
                    }
                }
            }
            return SegmentJob(segJob, completedFlag)
        }

        /** Single-range convenience wrapper (small files). */
        private suspend fun segmentedDownload(start: Long, end: Long): Boolean {
            RandomAccessFile(file, "rw").use { raf -> raf.setLength(end + 1) }
            val seg = launchSegment(start, end)
            seg.job.join()
            return seg.completed.get()
        }
    }

    /** Small holder: a segment coroutine + its success flag. */
    private class SegmentJob(val job: Job, val completed: AtomicBoolean)
}
