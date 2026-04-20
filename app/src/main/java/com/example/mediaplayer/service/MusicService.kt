package com.example.mediaplayer.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.mediaplayer.MainActivity
import com.example.mediaplayer.R

class MusicService : Service() {


    inner class MusicBinder : Binder() {
        fun get(): MusicService = this@MusicService
    }
    private val binder = MusicBinder()
    override fun onBind(intent: Intent?): IBinder = binder


    private var player: MediaPlayer? = null
    var isAudioPrepared = false


    var onPrepared:         (() -> Unit)?         = null
    var onCompletion:       (() -> Unit)?         = null
    var onError:            ((String) -> Unit)?   = null
    var onPlayStateChanged: ((Boolean) -> Unit)?  = null
    var onSkipNext:         (() -> Unit)?         = null
    var onSkipPrev:         (() -> Unit)?         = null


    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    var equalizerEnabled   = true
    var bassBoostStrength: Short = 0


    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var wasPlayingBeforeLoss = false


    private lateinit var mediaSession: MediaSessionCompat


    var currentTrackName = ""
    var currentTrackUri: Uri? = null

    companion object {
        const val CHANNEL_ID   = "nova_music_ch"
        const val NOTIF_ID     = 1001
        const val ACTION_PLAY  = "nova.PLAY"
        const val ACTION_PAUSE = "nova.PAUSE"
        const val ACTION_NEXT  = "nova.NEXT"
        const val ACTION_PREV  = "nova.PREV"
        const val ACTION_STOP  = "nova.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY  -> play()
            ACTION_PAUSE -> pause()
            ACTION_NEXT  -> onSkipNext?.invoke()
            ACTION_PREV  -> onSkipPrev?.invoke()
            ACTION_STOP  -> { stopPb(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer(); releaseEq(); mediaSession.release(); abandonAudioFocus()
    }




    fun playAudio(uri: Uri, name: String) {
        if (!requestAudioFocus()) return
        releasePlayer()
        isAudioPrepared = false
        currentTrackName = name
        currentTrackUri = uri

        player = MediaPlayer().apply {
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            try {
                setDataSource(applicationContext, uri)
            } catch (e: SecurityException) {
                e.printStackTrace()
                onError?.invoke("Permission denied to access this file. Please re-select it.")
                return
            } catch (e: Exception) {
                e.printStackTrace()
                onError?.invoke("Failed to load audio: ${e.message}")
                return
            }

            setOnPreparedListener { mp ->
                isAudioPrepared = true
                mp.start()
                initEqualizer()
                onPrepared?.invoke()
                onPlayStateChanged?.invoke(true)
                updateSession(true)
                startForeground(NOTIF_ID, buildNotification(name, true))
            }

            setOnCompletionListener {
                isAudioPrepared = false
                onCompletion?.invoke()
                onPlayStateChanged?.invoke(false)
                updateSession(false)
            }

            setOnErrorListener { _, what, extra ->
                isAudioPrepared = false
                onError?.invoke("Playback error ($what/$extra)")
                true
            }

            prepareAsync()
        }

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, name)
                .build()
        )
    }

    fun play() {
        player?.takeIf { !it.isPlaying && isAudioPrepared }?.let {
            it.start()
            onPlayStateChanged?.invoke(true)
            updateSession(true)
            updateNotif(currentTrackName, true)
        }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.let {
            it.pause()
            onPlayStateChanged?.invoke(false)
            updateSession(false)
            updateNotif(currentTrackName, false)
        }
    }

    fun stopPb() {
        player?.let {
            if (it.isPlaying) it.pause()
            // Remove seekTo(0) to allow resuming
        }
        onPlayStateChanged?.invoke(false)
        updateSession(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun seekTo(ms: Int) { if (isAudioPrepared) player?.seekTo(ms) }

    fun posMs(): Int = if (isAudioPrepared) player?.currentPosition ?: 0 else 0
    fun durMs(): Int = if (isAudioPrepared) player?.duration ?: 0 else 0
    fun isPlaying(): Boolean = player?.isPlaying == true
    fun sessionId(): Int = player?.audioSessionId ?: 0

    fun setSpeed(speed: Float) {
        if (!isAudioPrepared) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                player?.playbackParams = (player?.playbackParams ?: PlaybackParams()).setSpeed(speed)
            }
        } catch (_: Exception) {}
    }



    fun initEqualizer() {
        val sid = sessionId(); if (sid == 0) return
        try {
            releaseEq()
            equalizer = Equalizer(0, sid).apply { enabled = true }
            bassBoost = BassBoost(0, sid).apply { enabled = true }
        } catch (_: Exception) {}
    }

    private fun releaseEq() {
        try { equalizer?.release(); bassBoost?.release() } catch (_: Exception) {}
        equalizer = null; bassBoost = null
    }

    fun numBands(): Int     = try { equalizer?.numberOfBands?.toInt() ?: 0 } catch (_: Exception) { 0 }
    fun bandMin(): Short    = try { equalizer?.bandLevelRange?.get(0) ?: 0 } catch (_: Exception) { 0 }
    fun bandMax(): Short    = try { equalizer?.bandLevelRange?.get(1) ?: 0 } catch (_: Exception) { 0 }
    fun bandHz(b: Short)    = try { equalizer?.getCenterFreq(b)?.div(1000) ?: 0 } catch (_: Exception) { 0 }
    fun bandLevel(b: Short) = try { equalizer?.getBandLevel(b) ?: 0 } catch (_: Exception) { 0 }
    fun setBand(b: Short, level: Short) { try { equalizer?.setBandLevel(b, level) } catch (_: Exception) {} }
    fun setBass(strength: Short) {
        bassBoostStrength = strength
        try { bassBoost?.setStrength(strength) } catch (_: Exception) {}
    }
    fun setEqEnabled(on: Boolean) {
        equalizerEnabled = on
        try { equalizer?.enabled = on } catch (_: Exception) {}
    }



    private val focusListener = AudioManager.OnAudioFocusChangeListener { f ->
        when (f) {
            AudioManager.AUDIOFOCUS_LOSS            -> { wasPlayingBeforeLoss = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT  -> { wasPlayingBeforeLoss = isPlaying(); pause() }
            AudioManager.AUDIOFOCUS_GAIN            -> { if (wasPlayingBeforeLoss) play() }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
    }



    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "NovaPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()           { play() }
                override fun onPause()          { pause() }
                override fun onStop()           { stopPb() }
                override fun onSeekTo(p: Long)  { seekTo(p.toInt()) }
                override fun onSkipToNext()     { onSkipNext?.invoke() }
                override fun onSkipToPrevious() { onSkipPrev?.invoke() }
            })
            isActive = true
        }
    }

    private fun updateSession(playing: Boolean) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    posMs().toLong(), 1f
                )
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP
                ).build()
        )
    }



    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(name: String, playing: Boolean): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun pi(action: String) = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, MusicService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NOVA Player").setContentText(name)
            .setSmallIcon(R.drawable.ic_play).setContentIntent(tap)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setSilent(true)
            .addAction(R.drawable.ic_restart, "Prev", pi(ACTION_PREV))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                pi(if (playing) ACTION_PAUSE else ACTION_PLAY)
            )
            .addAction(R.drawable.ic_stop, "Next", pi(ACTION_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            ).build()
    }

    private fun updateNotif(name: String, playing: Boolean) {
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(name, playing)) }
        catch (_: SecurityException) {}
    }

    private fun releasePlayer() {
        try { player?.apply { if (isPlaying) stop(); reset(); release() } } catch (_: Exception) {}
        player = null; isAudioPrepared = false
    }
}
