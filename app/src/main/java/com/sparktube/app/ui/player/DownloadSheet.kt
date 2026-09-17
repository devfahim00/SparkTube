package com.sparktube.app.ui.player

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sparktube.app.R
import com.sparktube.app.download.DownloadCenter
import com.sparktube.app.playback.AudioTrackGroup
import com.sparktube.app.playback.QueueEntry
import com.sparktube.app.playback.StreamCatalog
import com.sparktube.app.playback.effectiveHeight
import com.sparktube.app.util.Themes

/**
 * Download picker: Audio only / Video only / Video + Audio, each with a
 * quality list. On the music player only the audio section is shown.
 * Options that were already downloaded appear marked and can't be started
 * again.
 */
class DownloadSheet(
    context: Context,
    private val entry: QueueEntry,
    private val catalog: StreamCatalog,
    private val audioOnly: Boolean = false
) : BottomSheetDialog(context) {

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

    /** Whether this exact type + quality was already downloaded successfully. */
    private fun downloaded(type: String, quality: String): Boolean =
        DownloadCenter.recordsFor(context, entry.url).any {
            it.type == type && it.quality == quality && it.status == DownloadCenter.STATUS_DONE
        }

    override fun show() {
        val scroll = android.widget.ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.action_download)
            textSize = 18f
            setTextColor(context.getColor(R.color.on_surface))
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(title)
        val songTitle = TextView(context).apply {
            text = entry.title
            textSize = 13f
            maxLines = 1
            setTextColor(context.getColor(R.color.on_surface_variant))
        }
        root.addView(songTitle)

        val musicOnly = audioOnly || !catalog.hasVideo

        // ----- Audio only -----
        val audioTrack = catalog.audioTracks.firstOrNull()
        if (audioTrack != null) {
            root.addView(sectionTitle(context.getString(R.string.download_audio_section)))
            audioTrack.streams
                .filter { it.isUrl }
                .sortedByDescending { it.averageBitrate }
                .forEach { stream ->
                    // NPE reports the audio average bitrate directly in kbps.
                    val kbps = if (stream.averageBitrate > 0) {
                        "${stream.averageBitrate} kbps"
                    } else {
                        "Audio"
                    }
                    val alreadyDone = downloaded(DownloadCenter.TYPE_AUDIO, kbps)
                    root.addView(
                        optionRow(
                            if (alreadyDone) "$kbps • ${context.getString(R.string.download_option_done)}" else kbps + suffix(audioTrack),
                            alreadyDone
                        ) {
                            if (alreadyDone) {
                                Toast.makeText(
                                    context, R.string.already_downloaded, Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                DownloadCenter.startSingle(
                                    context,
                                    entry.url,
                                    entry.title,
                                    entry.uploader,
                                    entry.thumbnailUrl,
                                    DownloadCenter.TYPE_AUDIO,
                                    kbps,
                                    stream.content,
                                    "m4a"
                                )
                                toastStarted()
                            }
                            dismiss()
                        }
                    )
                }
        }

        if (!musicOnly) {
            // ----- Video only -----
            root.addView(sectionTitle(context.getString(R.string.download_video_section)))
            val videoChoices = if (catalog.videoOnly.isNotEmpty()) {
                catalog.videoOnly
            } else {
                // No adaptive streams: fall back to the muxed list.
                catalog.muxed
            }
            videoChoices.forEach { v ->
                val label = if (v.isVideoOnly) {
                    "${v.effectiveHeight()}p (video only, no sound)"
                } else {
                    "${v.effectiveHeight()}p (muxed, video + sound)"
                }
                val quality = "${v.effectiveHeight()}p"
                val alreadyDone = downloaded(DownloadCenter.TYPE_VIDEO, quality)
                root.addView(
                    optionRow(
                        if (alreadyDone) "$label • ${context.getString(R.string.download_option_done)}" else label,
                        alreadyDone
                    ) {
                        if (alreadyDone) {
                            Toast.makeText(context, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
                        } else {
                            DownloadCenter.startSingle(
                                context,
                                entry.url,
                                entry.title,
                                entry.uploader,
                                entry.thumbnailUrl,
                                DownloadCenter.TYPE_VIDEO,
                                quality,
                                v.content,
                                "mp4"
                            )
                            toastStarted()
                        }
                        dismiss()
                    }
                )
            }

            // ----- Video + Audio -----
            if (catalog.videoOnly.isNotEmpty() && audioTrack?.best != null) {
                root.addView(sectionTitle(context.getString(R.string.download_av_section)))
                val note = TextView(context).apply {
                    text = context.getString(R.string.download_pair_note)
                    textSize = 12f
                    maxLines = 2
                    setTextColor(context.getColor(R.color.on_surface_variant))
                    layoutParams = marginParams()
                }
                root.addView(note)
                catalog.videoOnly.forEach { v ->
                    val quality = "${v.effectiveHeight()}p"
                    val alreadyDone = downloaded(DownloadCenter.TYPE_AV, quality)
                    root.addView(
                        optionRow(
                            if (alreadyDone) "$quality • ${context.getString(R.string.download_option_done)}" else quality,
                            alreadyDone
                        ) {
                            if (alreadyDone) {
                                Toast.makeText(context, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
                            } else {
                                DownloadCenter.startPair(
                                    context,
                                    entry.url,
                                    entry.title,
                                    entry.uploader,
                                    entry.thumbnailUrl,
                                    quality,
                                    v.content,
                                    audioTrack.best!!.content
                                )
                                toastStarted()
                            }
                            dismiss()
                        }
                    )
                }
            }
        }

        scroll.addView(root)
        setContentView(scroll)
        super.show()
    }

    private fun sectionTitle(label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(context.getColor(R.color.on_surface))
            setTypeface(null, Typeface.BOLD)
            layoutParams = marginParams(top = 18)
        }

    private fun marginParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(top) }

    private fun optionRow(label: String, done: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(
                if (done) Themes.onSurfaceVariantColor(context)
                else Themes.onSurfaceColor(context)
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Themes.elevatedColor(context))
            }
            layoutParams = marginParams(top = 8)
            setOnClickListener { onClick() }
        }

    private fun suffix(track: AudioTrackGroup): String {
        return if (track.label.isNotBlank() && track.label != "Default") {
            " • ${track.label}"
        } else {
            ""
        }
    }

    private fun toastStarted() {
        Toast.makeText(context, R.string.download_started, Toast.LENGTH_SHORT).show()
    }
}
