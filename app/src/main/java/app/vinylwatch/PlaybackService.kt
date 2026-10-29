package app.vinylwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Держит процесс живым, пока играет музыка (экран погас), и показывает MediaSession системе
 * (кнопки наушников, медиа-плитка часов). Сам звук делает ScratchEngine внутри Deck.
 * Если пауза длится дольше PAUSE_STOP_MS — Deck.stop() освобождает память и сервис завершается.
 */
class PlaybackService : Service() {
    private lateinit var session: MediaSession
    private lateinit var nm: NotificationManager
    private lateinit var deck: Deck
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private var stopJob: Job? = null
    private var foreground = false
    private var notifiedTrack: Track? = null
    private var metadataTrack: Track? = null
    private var metadataDuration = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        deck = (application as VinylApp).deck
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Воспроизведение", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "VinylWatch").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = deck.play()
                override fun onPause() = deck.pause()
                override fun onSkipToNext() = deck.next()
                override fun onSeekTo(pos: Long) = deck.seekMs(pos)
            })
            setSessionActivity(openApp())
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() обязывает вызвать startForeground в течение ~5 с — делаем сразу
        startForeground(
            NOTIF_ID, notification(deck.current),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        foreground = true
        notifiedTrack = deck.current
        if (collectJob == null) collectJob = scope.launch { deck.snap.collect { render(it) } }
        return START_NOT_STICKY
    }

    private fun render(s: Snap) {
        val t = s.track
        if (t == null) {
            collectJob?.cancel(); collectJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            return
        }
        val posMs = ((deck.engine?.positionSeconds ?: 0.0) * 1000).toLong()
        val durMs = ((deck.engine?.durationSeconds ?: 0.0) * 1000).toLong()
        if (metadataTrack != t || metadataDuration != durMs) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist ?: "")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs)
                    .build()
            )
            metadataTrack = t; metadataDuration = durMs
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO
                )
                .setState(
                    if (s.loading) PlaybackState.STATE_BUFFERING
                    else if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    posMs, if (s.playing) 1f else 0f,
                )
                .build()
        )
        if (s.playing || s.loading) {
            stopJob?.cancel(); stopJob = null
            // значок на циферблате живёт, пока играет (так велит гайд Wear OS для медиа)
            if (!foreground || notifiedTrack != t) {
                startForeground(NOTIF_ID, notification(t), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                foreground = true; notifiedTrack = t
            }
        } else {
            // пауза — значок и уведомление пропадают; сервис ждёт возобновления и через PAUSE_STOP_MS освобождает память
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                nm.cancel(NOTIF_ID)
                foreground = false
            }
            if (stopJob == null) stopJob = scope.launch { delay(PAUSE_STOP_MS); deck.stop() }
        }
    }

    /** Приложение закрыли из списка недавних: останавливаем музыку и убираем значок/сессию. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        deck.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_PLAYER, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Ongoing Activity: значок внизу циферблата. Касание = наше приложение (экран с пластинкой), а не системный плеер.
     * Системный плеер открывается свайпом влево с правого ободка пластинки.
     */
    private fun notification(t: Track?): Notification {
        val open = openApp()
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_vinyl)
            .setContentTitle(t?.artist ?: "Vinyl")
            .setContentText(t?.title ?: "")
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        OngoingActivity.Builder(applicationContext, NOTIF_ID, b)
            .setStaticIcon(R.drawable.ic_stat_vinyl)
            .setTouchIntent(open)
            .build()
            .apply(applicationContext)
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private companion object {
        const val CH = "playback"
        const val NOTIF_ID = 1
        const val PAUSE_STOP_MS = 5 * 60_000L
    }
}
