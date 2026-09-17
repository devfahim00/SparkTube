package com.sparktube.app.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.sparktube.app.R
import com.sparktube.app.data.DownloadRecord
import com.sparktube.app.data.LocalStore
import java.io.File

/**
 * Download engine built on the system DownloadManager.
 *
 * Modes:
 *  - AUDIO  : a single audio stream file (m4a)
 *  - VIDEO  : a single video file (muxed mp4 when available, otherwise a
 *             higher-quality video-only mp4)
 *  - AV     : a video-only file + an audio file downloaded as a pair; they are
 *             played back together inside SparkTube
 */
object DownloadCenter {

    const val TYPE_AUDIO = "AUDIO"
    const val TYPE_VIDEO = "VIDEO"
    const val TYPE_AV = "AV"

    const val STATUS_PENDING = "PENDING"
    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_DONE = "DONE"
    const val STATUS_FAILED = "FAILED"

    private fun downloadManager(context: Context): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Extracts a safe file-system friendly video id from a video URL. */
    fun videoIdOf(url: String): String {
        val fallback = url.replace(Regex("[^A-Za-z0-9_-]"), "").takeLast(20)
        val vParam = Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
        val shortLink = Regex("youtu\\.be/([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
        return (vParam ?: shortLink ?: fallback).take(20)
    }

    private fun dirOf(type: String): String = when (type) {
        TYPE_AUDIO -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_MOVIES
    }

    private fun subPathOf(fileName: String): String = "SparkTube/$fileName"

    /** @return the local file path of the destination. */
    private fun destinationPath(context: Context, fileName: String, type: String): String {
        val base = context.getExternalFilesDir(dirOf(type)) ?: context.filesDir
        return File(File(base, "SparkTube"), fileName).absolutePath
    }

    private fun enqueue(
        context: Context,
        streamUrl: String,
        fileName: String,
        type: String,
        title: String
    ): Pair<Long, String> {
        val path = destinationPath(context, fileName, type)
        val request = DownloadManager.Request(Uri.parse(streamUrl))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setTitle(title)
            .setDestinationInExternalFilesDir(context, dirOf(type), subPathOf(fileName))
        val id = downloadManager(context).enqueue(request)
        return id to path
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
        val videoId = videoIdOf(videoUrl)
        val fileName = "${videoId}_${type}_${quality.replace(" ", "")}.$fileExt"
        val (id, path) = enqueue(context, streamUrl, fileName, type, title)
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
                downloadIds = listOf(id)
            )
        )
    }

    /** Starts a video+audio pair download (AV). */
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
        val videoId = videoIdOf(videoUrl)
        val (videoIdDl, videoPath) =
            enqueue(context, videoStreamUrl, "${videoId}_AV_${quality.replace(" ", "")}_v.mp4", TYPE_AV, title)
        val (audioIdDl, audioPath) =
            enqueue(context, audioStreamUrl, "${videoId}_AV_${quality.replace(" ", "")}_a.m4a", TYPE_AV, title)
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
                filePaths = listOf(videoPath, audioPath),
                downloadIds = listOf(videoIdDl, audioIdDl)
            )
        )
    }

    /** Deletes the files of a record and removes it from the registry. */
    fun delete(context: Context, record: DownloadRecord) {
        record.downloadIds.forEach { id ->
            runCatching { downloadManager(context).remove(id) }
        }
        record.filePaths.forEach { path ->
            runCatching { File(path).delete() }
        }
        LocalStore.removeDownload(context, record)
    }

    /** Re-syncs statuses of unfinished downloads (missed broadcasts etc). */
    fun refreshStatuses(context: Context) {
        val dm = downloadManager(context)
        LocalStore.downloads(context).filter { it.status != STATUS_DONE && it.status != STATUS_FAILED }
            .forEach { record ->
                val query = DownloadManager.Query().setFilterById(*record.downloadIds.toLongArray())
                dm.query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val newStatus = when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> STATUS_DONE
                            DownloadManager.STATUS_FAILED -> STATUS_FAILED
                            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> STATUS_RUNNING
                            else -> STATUS_PENDING
                        }
                        if (newStatus != record.status) {
                            record.downloadIds.forEach { id ->
                                LocalStore.updateDownloadStatus(context, id, newStatus)
                            }
                        }
                    } else {
                        // Download vanished from the manager (app data cleared etc.)
                        record.downloadIds.forEach { id ->
                            LocalStore.updateDownloadStatus(context, id, STATUS_FAILED)
                        }
                    }
                }
            }
    }
}

/** Listens for finished system downloads and updates the registry. */
class DownloadCompletionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return
        val query = DownloadManager.Query().setFilterById(id)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var succeeded = false
        dm.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                succeeded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                    DownloadManager.STATUS_SUCCESSFUL
            }
        }
        LocalStore.updateDownloadStatus(
            context,
            id,
            if (succeeded) DownloadCenter.STATUS_DONE else DownloadCenter.STATUS_FAILED
        )
        if (succeeded) {
            Toast.makeText(context, R.string.download_status_done, Toast.LENGTH_SHORT).show()
        }
    }
}
