package com.example.mediaplayer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.mediaplayer.databinding.ActivityMainBinding
import com.example.mediaplayer.databinding.DialogUrlBinding
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * MainActivity - Media Player App
 * Supports:
 * (a) Audio playback from local disk using MediaPlayer
 * (b) Video streaming from a URL using VideoView
 *
 * Controls: Open File, Open URL, Play, Pause, Stop, Restart
 */
class MainActivity : AppCompatActivity() {

    // ──────────────────────────────────────────────────────────────
    // View Binding
    // ──────────────────────────────────────────────────────────────
    private lateinit var binding: ActivityMainBinding

    // ──────────────────────────────────────────────────────────────
    // MediaPlayer (used for AUDIO playback from local disk)
    // ──────────────────────────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null

    // ──────────────────────────────────────────────────────────────
    // ExoPlayer (used for VIDEO streaming from URL)
    // ──────────────────────────────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null

    // ──────────────────────────────────────────────────────────────
    // YouTube Player
    // ──────────────────────────────────────────────────────────────
    private var youtubePlayer: YouTubePlayer? = null
    private var currentYoutubeVideoId: String? = null

    // ──────────────────────────────────────────────────────────────
    // State tracking
    // ──────────────────────────────────────────────────────────────
    private enum class MediaMode { NONE, AUDIO, VIDEO, YOUTUBE }
    private var currentMode = MediaMode.NONE
    private var currentAudioUri: Uri? = null
    private var isAudioPrepared = false

    // Handler + Runnable to update SeekBar every 500ms while playing
    private val seekHandler = Handler(Looper.getMainLooper())
    private val seekRunnable = object : Runnable {
        override fun run() {
            updateSeekBar()
            seekHandler.postDelayed(this, 500)
        }
    }

    // Audio wave animation
    private var waveAnimator: ValueAnimator? = null

    // ──────────────────────────────────────────────────────────────
    // Request codes
    // ──────────────────────────────────────────────────────────────
    private val PICK_AUDIO_REQUEST = 101
    private val PERMISSION_REQUEST = 102

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        setupSeekBar()
        setControlsEnabled(false)
        showIdleState()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
        releaseExoPlayer()
        seekHandler.removeCallbacks(seekRunnable)
        waveAnimator?.cancel()
    }

    // ──────────────────────────────────────────────────────────────
    // Button setup
    // ──────────────────────────────────────────────────────────────
    private fun setupButtons() {

        // Open File → pick audio from storage
        binding.btnOpenFile.setOnClickListener {
            requestAudioPermissionAndPick()
        }

        // Open URL → show dialog to enter video URL
        binding.btnOpenUrl.setOnClickListener {
            showUrlDialog()
        }

        lifecycle.addObserver(binding.youtubePlayerView)

        // Play / Pause toggle
        binding.btnPlayPause.setOnClickListener {
            when (currentMode) {
                MediaMode.AUDIO -> toggleAudioPlayPause()
                MediaMode.VIDEO -> toggleVideoPlayPause()
                MediaMode.YOUTUBE -> toggleYoutubePlayPause()
                MediaMode.NONE  -> { /* nothing loaded */ }
            }
        }

        // Stop
        binding.btnStop.setOnClickListener {
            stopMedia(release = false)
        }

        // Restart
        binding.btnRestart.setOnClickListener {
            restartMedia()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // SeekBar setup & updates
    // ──────────────────────────────────────────────────────────────
    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (currentMode) {
                    MediaMode.AUDIO -> mediaPlayer?.seekTo(progress)
                    MediaMode.VIDEO -> exoPlayer?.seekTo(progress.toLong())
                    MediaMode.YOUTUBE -> youtubePlayer?.seekTo(progress.toFloat())
                    MediaMode.NONE  -> {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun updateSeekBar() {
        when (currentMode) {
            MediaMode.AUDIO -> {
                val mp = mediaPlayer ?: return
                if (isAudioPrepared) {
                    binding.seekBar.max = mp.duration
                    binding.seekBar.progress = mp.currentPosition
                    binding.tvCurrentTime.text = formatTime(mp.currentPosition)
                    binding.tvTotalTime.text   = formatTime(mp.duration)
                }
            }
            MediaMode.VIDEO -> {
                exoPlayer?.let { player ->
                    val duration = player.duration
                    val position = player.currentPosition
                    if (duration > 0) {
                        binding.seekBar.max = duration.toInt()
                        binding.seekBar.progress = position.toInt()
                        binding.tvCurrentTime.text = formatTime(position.toInt())
                        binding.tvTotalTime.text   = formatTime(duration.toInt())
                    }
                }
            }
            MediaMode.YOUTUBE -> {
                // YouTube player has its own UI usually, but we keep this for consistency
            }
            MediaMode.NONE -> {}
        }
    }

    private fun startSeekUpdates() {
        seekHandler.removeCallbacks(seekRunnable)
        seekHandler.post(seekRunnable)
    }

    private fun stopSeekUpdates() {
        seekHandler.removeCallbacks(seekRunnable)
    }

    // ──────────────────────────────────────────────────────────────
    // ─── AUDIO ───────────────────────────────────────────────────
    // ──────────────────────────────────────────────────────────────

    /** Ask for storage permission then open the file picker. */
    private fun requestAudioPermissionAndPick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            openAudioFilePicker()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), PERMISSION_REQUEST)
        }
    }

    private fun openAudioFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select Audio File"), PICK_AUDIO_REQUEST)
    }

    /** Initialise and start MediaPlayer for the given audio URI. */
    private fun playAudio(uri: Uri) {
        stopMedia(release = true)
        currentMode = MediaMode.AUDIO
        currentAudioUri = uri
        isAudioPrepared = false

        showAudioState()
        binding.tvMediaTitle.text = getFileName(uri)
        showLoading(true)

        mediaPlayer = MediaPlayer().apply {
            // Feed the URI to MediaPlayer using the ContentResolver
            setDataSource(applicationContext, uri)


            setOnPreparedListener { mp ->
                isAudioPrepared = true
                showLoading(false)
                setControlsEnabled(true)
                mp.start()
                setPlayPauseIcon(playing = true)
                startSeekUpdates()
                startWaveAnimation()
                binding.tvStatus.text = "Playing audio"
            }

            setOnCompletionListener {
                setPlayPauseIcon(playing = false)
                stopSeekUpdates()
                stopWaveAnimation()
                binding.tvStatus.text = "Playback complete"
                binding.seekBar.progress = binding.seekBar.max
            }

            setOnErrorListener { _, what, extra ->
                showLoading(false)
                showError("Audio error (what=$what extra=$extra)")
                true
            }


            prepareAsync()
        }
    }

    private fun toggleAudioPlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            setPlayPauseIcon(playing = false)
            stopSeekUpdates()
            stopWaveAnimation()
            binding.tvStatus.text = "Paused"
        } else {
            mp.start()
            setPlayPauseIcon(playing = true)
            startSeekUpdates()
            startWaveAnimation()
            binding.tvStatus.text = "Playing audio"
        }
    }

    // ──────────────────────────────────────────────────────────────
    // ─── VIDEO ───────────────────────────────────────────────────
    // ──────────────────────────────────────────────────────────────

    /** Show URL input dialog to stream video. */
    private fun showUrlDialog() {
        val dialogBinding = DialogUrlBinding.inflate(LayoutInflater.from(this))
        
        // Pre-fill with a sample URL
        dialogBinding.etUrl.setText("https://vjs.zencdn.net/v/oceans.mp4")

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Stream Video from URL")
            .setView(dialogBinding.root)
            .setPositiveButton("Stream") { _, _ ->
                val url = dialogBinding.etUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    streamVideo(url)
                } else {
                    Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @OptIn(UnstableApi::class)
    private fun streamVideo(url: String) {
        val youtubeId = extractYoutubeId(url)
        if (youtubeId != null) {
            playYoutubeVideo(youtubeId)
            return
        }

        stopMedia(release = true)
        currentMode = MediaMode.VIDEO

        showVideoState()
        showLoading(true)
        binding.tvMediaTitle.text = Uri.parse(url).lastPathSegment ?: "Video Stream"
        binding.tvStatus.text = "Buffering…"

        if (exoPlayer == null) {
            // Use a comprehensive User-Agent to mimic a real browser and avoid 403 Forbidden errors
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)

            val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
            val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)

            exoPlayer = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build().apply {
                binding.playerView.player = this
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                showLoading(false)
                                setControlsEnabled(true)
                                setPlayPauseIcon(playing = true)
                                startSeekUpdates()
                                binding.tvStatus.text = "Streaming video"
                            }
                            Player.STATE_ENDED -> {
                                setPlayPauseIcon(playing = false)
                                stopSeekUpdates()
                                binding.tvStatus.text = "Playback complete"
                            }
                            Player.STATE_BUFFERING -> {
                                showLoading(true)
                                binding.tvStatus.text = "Buffering…"
                            }
                            Player.STATE_IDLE -> {
                                // IDLE
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        showLoading(false)
                        val cause = error.cause
                        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) {
                            showError("Server denied access (403). Try a different URL.")
                        } else {
                            showError("Video error: ${error.localizedMessage}")
                        }
                    }
                })
            }
        }

        try {
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(url)

            // Explicitly set MIME type for adaptive streams if the URL contains keywords
            // This helps ExoPlayer choose the right MediaSource when the URL has no extension.
            when {
                url.contains(".m3u8") || url.contains("m3u8") -> {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                url.contains(".mpd") || url.contains("mpd") -> {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                }
                url.contains(".ism") || url.contains("Manifest") -> {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_SS)
                }
                url.startsWith("rtsp://") -> {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_RTSP)
                }
            }

            val mediaItem = mediaItemBuilder.build()
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        } catch (e: Exception) {
            showLoading(false)
            showError("Invalid URL: ${e.localizedMessage}")
        }
    }

    private fun toggleVideoPlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                setPlayPauseIcon(playing = false)
                stopSeekUpdates()
                binding.tvStatus.text = "Paused"
            } else {
                player.play()
                setPlayPauseIcon(playing = true)
                startSeekUpdates()
                binding.tvStatus.text = "Streaming video"
            }
        }
    }

    private var isYoutubePlaying = false

    private fun playYoutubeVideo(videoId: String) {
        stopMedia(release = true)
        currentMode = MediaMode.YOUTUBE
        currentYoutubeVideoId = videoId

        showYoutubeState()
        binding.tvMediaTitle.text = "YouTube Video"
        binding.tvStatus.text = "Loading YouTube..."

        val listener = object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                this@MainActivity.youtubePlayer = youTubePlayer
                youTubePlayer.loadVideo(videoId, 0f)
                isYoutubePlaying = true
                setPlayPauseIcon(playing = true)
                setControlsEnabled(true)
                binding.tvStatus.text = "Playing YouTube"
            }

            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                binding.seekBar.max = duration.toInt()
                binding.tvTotalTime.text = formatTime((duration * 1000).toInt())
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                binding.seekBar.progress = second.toInt()
                binding.tvCurrentTime.text = formatTime((second * 1000).toInt())
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState) {
                when(state) {
                    com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.PLAYING -> {
                        isYoutubePlaying = true
                        setPlayPauseIcon(playing = true)
                        binding.tvStatus.text = "Playing YouTube"
                    }
                    com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.PAUSED -> {
                        isYoutubePlaying = false
                        setPlayPauseIcon(playing = false)
                        binding.tvStatus.text = "Paused"
                    }
                    com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.ENDED -> {
                        isYoutubePlaying = false
                        setPlayPauseIcon(playing = false)
                        binding.tvStatus.text = "Finished"
                    }
                    else -> {}
                }
            }
        }

        // If player is already initialized, just load the new video
        youtubePlayer?.let {
            it.loadVideo(videoId, 0f)
            setPlayPauseIcon(playing = true)
            setControlsEnabled(true)
            binding.tvStatus.text = "Playing YouTube"
            return
        }

        binding.youtubePlayerView.initialize(listener)
    }

    private fun toggleYoutubePlayPause() {
        if (isYoutubePlaying) {
            youtubePlayer?.pause()
        } else {
            youtubePlayer?.play()
        }
    }

    private fun extractYoutubeId(url: String): String? {
        val patterns = listOf(
            "watch\\?v=([\\w-]{11})",
            "embed/([\\w-]{11})",
            "youtu\\.be/([\\w-]{11})",
            "v/([\\w-]{11})"
        )
        for (p in patterns) {
            val regex = Regex(p, RegexOption.IGNORE_CASE)
            val match = regex.find(url)
            if (match != null) return match.groups[1]?.value
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────
    // ─── SHARED CONTROLS ─────────────────────────────────────────
    // ──────────────────────────────────────────────────────────────

    private fun stopMedia(release: Boolean = false) {
        when (currentMode) {
            MediaMode.AUDIO -> {
                if (release) {
                    releaseMediaPlayer()
                } else {
                    mediaPlayer?.let {
                        if (it.isPlaying) it.pause()
                        it.seekTo(0)
                    }
                }
            }
            MediaMode.VIDEO -> {
                if (release) {
                    releaseExoPlayer()
                } else {
                    exoPlayer?.let {
                        it.pause()
                        it.seekTo(0)
                    }
                }
            }
            MediaMode.YOUTUBE -> {
                // YouTube library doesn't have a clear 'release' that we want to call here
                // as it's bound to lifecycle. We just pause and seek.
                youtubePlayer?.let {
                    it.pause()
                    it.seekTo(0f)
                }
            }
            MediaMode.NONE -> {}
        }
        stopSeekUpdates()
        stopWaveAnimation()
        setPlayPauseIcon(playing = false)
        
        // If we're not releasing, keep controls enabled so user can press Play again.
        // If we are releasing, we might be about to load something else or go to idle.
        if (currentMode == MediaMode.NONE) {
            setControlsEnabled(false)
        }

        binding.seekBar.progress = 0
        binding.tvCurrentTime.text = "0:00"
        binding.tvStatus.text = "Stopped"
    }

    private fun restartMedia() {
        // ... (rest of restartMedia is fine)
        when (currentMode) {
            MediaMode.AUDIO -> {
                val uri = currentAudioUri
                if (uri != null) {
                    playAudio(uri)
                }
            }
            MediaMode.VIDEO -> {
                exoPlayer?.seekTo(0)
                exoPlayer?.playWhenReady = true
                setPlayPauseIcon(playing = true)
                startSeekUpdates()
                binding.tvStatus.text = "Streaming video"
            }
            MediaMode.YOUTUBE -> {
                youtubePlayer?.seekTo(0f)
                youtubePlayer?.play()
            }
            MediaMode.NONE -> {}
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            reset()
            release()
        }
        mediaPlayer = null
        isAudioPrepared = false
    }

    private fun releaseExoPlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    // ──────────────────────────────────────────────────────────────
    // ─── UI HELPERS ──────────────────────────────────────────────
    // ──────────────────────────────────────────────────────────────

    private fun showIdleState() {
        binding.playerView.visibility  = View.GONE
        binding.youtubePlayerView.visibility = View.GONE
        binding.layoutAudioViz.visibility = View.VISIBLE
        binding.tvMediaTitle.text      = "No media loaded"
        binding.tvStatus.text          = "Open a file or enter a URL to begin"
    }

    private fun showAudioState() {
        binding.playerView.visibility     = View.GONE
        binding.youtubePlayerView.visibility = View.GONE
        binding.layoutAudioViz.visibility = View.VISIBLE
    }

    private fun showVideoState() {
        binding.layoutAudioViz.visibility = View.GONE
        binding.youtubePlayerView.visibility = View.GONE
        binding.playerView.visibility     = View.VISIBLE
    }

    private fun showYoutubeState() {
        binding.layoutAudioViz.visibility = View.GONE
        binding.playerView.visibility     = View.GONE
        binding.youtubePlayerView.visibility = View.VISIBLE
    }

    private fun showLoading(show: Boolean) {
        binding.progressLoading.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnPlayPause.isEnabled = enabled
        binding.btnStop.isEnabled      = enabled
        binding.btnRestart.isEnabled   = enabled
        binding.seekBar.isEnabled      = enabled
    }

    private fun setPlayPauseIcon(playing: Boolean) {
        if (playing) {
            binding.btnPlayPause.setIconResource(R.drawable.ic_pause)
            binding.btnPlayPause.text = "PAUSE"
        } else {
            binding.btnPlayPause.setIconResource(R.drawable.ic_play)
            binding.btnPlayPause.text = "PLAY"
        }
    }

    private fun showError(message: String) {
        currentMode = MediaMode.NONE
        binding.tvStatus.text = "Error"
        setControlsEnabled(false)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ──────────────────────────────────────────────────────────────
    // ─── AUDIO WAVE ANIMATION ─────────────────────────────────────
    // ──────────────────────────────────────────────────────────────

    private fun startWaveAnimation() {
        waveAnimator?.cancel()
        val bars = listOf(
            binding.bar1, binding.bar2, binding.bar3,
            binding.bar4, binding.bar5, binding.bar6, binding.bar7
        )
        bars.forEachIndexed { index, view ->
            ObjectAnimator.ofFloat(view, "scaleY", 0.2f, 1f, 0.2f).apply {
                duration = 600
                repeatCount = ValueAnimator.INFINITE
                repeatMode  = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                startDelay = (index * 80).toLong()
                start()
            }
        }
    }

    private fun stopWaveAnimation() {
        val bars = listOf(
            binding.bar1, binding.bar2, binding.bar3,
            binding.bar4, binding.bar5, binding.bar6, binding.bar7
        )
        bars.forEach { view ->
            view.animate().scaleY(0.15f).setDuration(300).start()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // ─── UTILITY ─────────────────────────────────────────────────
    // ──────────────────────────────────────────────────────────────

    private fun getFileName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                if (idx >= 0) cursor.getString(idx) else uri.lastPathSegment ?: "Audio"
            } ?: (uri.lastPathSegment ?: "Audio")
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Audio"
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    // ──────────────────────────────────────────────────────────────
    // ─── RESULT / PERMISSION CALLBACKS ───────────────────────────
    // ──────────────────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_AUDIO_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> playAudio(uri) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openAudioFilePicker()
            } else {
                Toast.makeText(this, "Storage permission is required to open audio files.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
