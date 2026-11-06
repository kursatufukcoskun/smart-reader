package com.kursat.reader.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.kursat.reader.MainActivity

/**
 * Seslendirme sürerken uygulamanın arka planda canlı kalmasını sağlayan ön plan hizmeti.
 * Bildirimde oynat/duraklat, ileri/geri ve kapat düğmeleri ile kilit ekranı denetimi sunar.
 */
class SpeechService : Service() {
    private var session: MediaSession? = null

    companion object {
        private const val CHANNEL = "speech"
        private const val NOTIF_ID = 4711
        private const val ACTION_TOGGLE = "com.kursat.reader.speech.TOGGLE"
        private const val ACTION_NEXT = "com.kursat.reader.speech.NEXT"
        private const val ACTION_PREV = "com.kursat.reader.speech.PREV"
        private const val ACTION_STOP = "com.kursat.reader.speech.STOP"
        @Volatile private var instance: SpeechService? = null

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, SpeechService::class.java)) }
        }

        fun stop(context: Context) {
            instance?.stopSelf() ?: runCatching { context.stopService(Intent(context, SpeechService::class.java)) }
        }

        fun refresh() { instance?.update() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Seslendirme", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "ReaderSpeech").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { SpeechController.active?.resume() }
                override fun onPause() { SpeechController.active?.pause() }
                override fun onSkipToNext() { SpeechController.active?.skip(1) }
                override fun onSkipToPrevious() { SpeechController.active?.skip(-1) }
                override fun onStop() { SpeechController.active?.stop() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = SpeechController.active
        when (intent?.action) {
            ACTION_TOGGLE -> c?.toggle()
            ACTION_NEXT -> c?.skip(1)
            ACTION_PREV -> c?.skip(-1)
            ACTION_STOP -> c?.stop()
        }
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun update() {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_ID, build()) }
    }

    private fun action(act: String, icon: Int, title: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(), Intent(this, SpeechService::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun build(): Notification {
        val c = SpeechController.active
        val playing = c?.state?.value == SpeechController.State.playing
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(c?.title?.ifBlank { null } ?: "Sesli okuma")
            .setContentText(c?.subtitle.orEmpty())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action(ACTION_PREV, android.R.drawable.ic_media_previous, "Geri"))
            .addAction(
                if (playing) action(ACTION_TOGGLE, android.R.drawable.ic_media_pause, "Duraklat")
                else action(ACTION_TOGGLE, android.R.drawable.ic_media_play, "Oynat")
            )
            .addAction(action(ACTION_NEXT, android.R.drawable.ic_media_next, "İleri"))
            .addAction(action(ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, "Kapat"))
        session?.sessionToken?.let {
            b.setStyle(Notification.MediaStyle().setMediaSession(it).setShowActionsInCompactView(0, 1, 2))
        }
        return b.build()
    }
}
