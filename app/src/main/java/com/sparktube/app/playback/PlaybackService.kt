package com.sparktube.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sparktube.app.R
import com.sparktube.app.ui.MainActivity
import com.sparktube.app.ui.music.NowPlayingActivity

/**
 * Keeps playback alive in the background and shows the media notification:
 * seek bar + previous / play-pause / next controls (native, straight from
 * the player playlist) plus a favorite custom button. Tapping the
 * notification opens the Now Playing screen for music and the main app
 * otherwise.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        PlaybackCenter.init(this)
        mediaSession = MediaSession.Builder(this, PlaybackCenter.player)
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity())
            .build()
        updateCustomLayout()
        PlaybackCenter.addListener(object : PlaybackCenter.Listener {
            override fun onFavoriteChanged(url: String, isFavorite: Boolean) {
                updateCustomLayout()
            }

            override fun onItemChanged(entry: QueueEntry?) {
                updateCustomLayout()
                updateSessionActivity()
            }
        })
    }

    private fun updateCustomLayout() {
        val session = mediaSession ?: return
        val isFavorite = PlaybackCenter.isCurrentFavorite()
        // Previous / next / seekbar come from the player itself (real
        // playlist), so only the favourite toggle is a custom button.
        val buttons = listOf(
            CommandButton.Builder()
                .setDisplayName(if (isFavorite) "Unfavorite" else "Favorite")
                .setSessionCommand(SessionCommand(ACTION_FAV, Bundle.EMPTY))
                .setIconResId(
                    if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                )
                .build()
        )
        session.setCustomLayout(buttons)
    }

    /**
     * What tapping the notification / system media controls opens: the Now
     * Playing screen while music is active, the main app otherwise.
     */
    private fun sessionActivity(): PendingIntent {
        val target = if (PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO) {
            NowPlayingActivity::class.java
        } else {
            MainActivity::class.java
        }
        val intent = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updateSessionActivity() {
        mediaSession?.setSessionActivity(sessionActivity())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_FAV, Bundle.EMPTY))
                .add(SessionCommand(ACTION_PREV, Bundle.EMPTY))
                .add(SessionCommand(ACTION_NEXT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                ACTION_FAV -> {
                    PlaybackCenter.toggleFavoriteCurrent()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_PREV -> {
                    PlaybackCenter.previous()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_NEXT -> {
                    PlaybackCenter.next()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
        }
    }

    companion object {
        const val ACTION_FAV = "com.sparktube.app.action.FAVORITE"
        const val ACTION_PREV = "com.sparktube.app.action.PREVIOUS"
        const val ACTION_NEXT = "com.sparktube.app.action.NEXT"
    }
}
