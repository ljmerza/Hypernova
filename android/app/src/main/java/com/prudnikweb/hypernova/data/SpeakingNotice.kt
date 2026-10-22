package com.prudnikweb.hypernova.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R

/**
 * While the phone speaks: a media notification with Stop (on the lock screen too, and the
 * headphones' own button stops it), so the voice can be stopped without opening the app. A media
 * session, so Android treats it as what plays now; gone with the voice.
 */
class SpeakingNotice private constructor(private val context: Context, private val session: MediaSession) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun close() {
        runCatching { nm?.cancel(ID) }
        runCatching {
            session.isActive = false
            session.release()
        }
    }

    companion object {
        private const val CHANNEL = "speaking"
        private const val ID = 0x5e1a

        /** Shown at once, on the main thread; [onStop] for the session's stop and pause (a headset's button). */
        fun show(context: Context, play: PlayInfo, label: String?, stopAction: String, onStop: () -> Unit): SpeakingNotice? = runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return null
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.speaking_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.speaking_channel_desc)
                    setShowBadge(false)
                    setSound(null, null)
                },
            )
            val title = context.getString(if (play.forGlasses) R.string.speaking_for_glasses else R.string.speaking_reading)
            val session = MediaSession(context, "Hypernova speaking").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onStop() = onStop()
                        override fun onPause() = onStop()
                    },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
                setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, label ?: context.getString(R.string.app_name))
                        .build(),
                )
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                        .build(),
                )
                isActive = true
            }
            if (!Core.notifier.canPost()) return SpeakingNotice(context, session)
            val stop = PendingIntent.getBroadcast(context, 11, Intent(stopAction).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(
                context, 12,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply {
                    play.sessionId?.let { setAction(MainActivity.ACTION_OPEN_SESSION).putExtra(MainActivity.EXTRA_SESSION, it) }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_speaking)
                .setColor(0xFF7FFFA0.toInt())
                .setContentTitle(title)
                .setContentText(label ?: context.getString(R.string.app_name))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_speech_stop), context.getString(R.string.speaking_stop), stop).build())
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
                .build()
            nm.notify(ID, n)
            SpeakingNotice(context, session)
        }.getOrNull()
    }
}
