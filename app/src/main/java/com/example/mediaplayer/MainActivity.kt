package com.example.mediaplayer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app. PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Rational
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.database.ContentObserver
import android.provider.Settings
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope
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
import com.example.mediaplayer.db.AppDatabase
import com.example.mediaplayer.db.entity.Bookmark
import com.example.mediaplayer.db.entity.PlayStat
import com.example.mediaplayer.db.entity.PlaylistItem
import com.example.mediaplayer.service.MusicService
import com.example.mediaplayer.ui.FullscreenVideoActivity
import com.example.mediaplayer.ui.HistoryActivity
import com.example.mediaplayer.ui.PlaylistActivity
import com.example.mediaplayer.ui.StatsActivity
import com.example.mediaplayer.util.HapticHelper
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import com.example.mediaplayer.util.LyricLine
import com.example.mediaplayer.util.LyricsParser
import com.example.mediaplayer.util.ShakeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var musicService: MusicService? = null
    private var serviceBound = false
    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            musicService = (b as MusicService.MusicBinder).get()
            serviceBound = true
            attachServiceCallbacks()
            syncUIWithService()
        }
        override fun onServiceDisconnected(n: ComponentName?) { serviceBound = false }
    }

    private enum class Mode { NONE, AUDIO, VIDEO, YOUTUBE }
    private var mode = Mode.NONE
    private var currentUri: Uri? = null
    private var currentName = ""
    private var currentVideoUrl = ""
    private var isBookmarked = false

    private var exoPlayer: ExoPlayer? = null
    private var youtubePlayer: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer? = null
    private var ytPlaying = false

    private val seekHandler = Handler(Looper.getMainLooper())
    private val seekRunnable = object : Runnable {
        override fun run() {
            updateSeekBar()
            updateLyricsHighlight()
            syncVolumeSlider()
            seekHandler.postDelayed(this, 250)
        }
    }

    private val waveAnimators = mutableListOf<ObjectAnimator>()

    private var speed = 1.0f
    private val PREFS = "nova_prefs"
    private val speedOptions = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var sleepTimer: android.os.CountDownTimer? = null
    private val HIST_KEY = "history"
    private lateinit var audioManager: AudioManager

    private var visualizer: Visualizer? = null
    private var vizEnabled = false
    private var vizSessionId = 0

    private var lyrics: List<LyricLine> = emptyList()
    private var lastLyricIdx = -1

    private val queue = mutableListOf<PlaylistItem>()
    private var queueIdx = -1

    private lateinit var db: AppDatabase
    private lateinit var statsManager: com.example.mediaplayer.util.StatsManager

    private var loopA = -1; private var loopB = -1; private var abLoopEnabled = false
    private val abHandler = Handler(Looper.getMainLooper())
    private val abRunnable = object : Runnable {
        override fun run() {
            if (abLoopEnabled && loopA >= 0 && loopB > loopA) {
                val pos = musicService?.posMs() ?: 0
                if (pos >= loopB) musicService?.seekTo(loopA)
            }
            abHandler.postDelayed(this, 200)
        }
    }

    private enum class RepeatMode { OFF, ONE, ALL }
    private var repeatMode = RepeatMode.OFF

    private lateinit var shakeDetector: ShakeDetector
    private lateinit var sensorManager: SensorManager
    private lateinit var gestureDetector: GestureDetector

    private lateinit var drawerLayout: DrawerLayout

    private val REQ_AUDIO      = 101; private val REQ_PERM     = 102
    private val REQ_LYRICS     = 103; private val REQ_PLAYLIST = 105
    private val REQ_HISTORY    = 106; private val REQ_FULLSCREEN = 107
    private val REQ_VIS_PERM   = 108

    private var volumeObserver: ContentObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_theme", true)
        
        // Apply theme mode before super.onCreate
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        savedInstanceState?.let {
            mode = Mode.valueOf(it.getString("mode", Mode.NONE.name))
            currentName = it.getString("currentName", "")
            currentUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelable("currentUri", Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelable("currentUri")
            }
            currentVideoUrl = it.getString("currentVideoUrl", "")
            speed = it.getFloat("speed", 1.0f)
        }

        db = AppDatabase.get(this)
        statsManager = com.example.mediaplayer.util.StatsManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        drawerLayout = binding.drawerLayout

        Intent(this, MusicService::class.java).also {
            startService(it)
            bindService(it, serviceConn, Context.BIND_AUTO_CREATE)
        }

        setupAll()
        runEntrance()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mode", mode.name)
        outState.putString("currentName", currentName)
        outState.putParcelable("currentUri", currentUri)
        outState.putString("currentVideoUrl", currentVideoUrl)
        outState.putFloat("speed", speed)
    }

    private fun syncUIWithService() {
        val svc = musicService ?: return
        if (svc.isPlaying() || svc.isAudioPrepared) {
            mode = Mode.AUDIO
            currentName = svc.currentTrackName
            currentUri = svc.currentTrackUri
            
            binding.tvMediaTitle.text = currentName
            setPlayPause(svc.isPlaying())
            setControlsEnabled(true)
            showAudio()
            
            if (svc.isPlaying()) {
                startSeek()
                startWave()
                startVis()
                binding.tvStatus.text = "Playing"
            } else {
                binding.tvStatus.text = "Paused"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(
            shakeDetector,
            sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_UI
        )
        syncVolumeSlider()
    }

    override fun onPause() { super.onPause(); sensorManager.unregisterListener(shakeDetector) }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (mode == Mode.VIDEO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build()) }
            catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(pip: Boolean, cfg: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(pip, cfg)
        binding.cardControls.visibility = if (pip) View.GONE else View.VISIBLE
        binding.topBar.visibility       = if (pip) View.GONE else View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) drawerLayout.closeDrawer(GravityCompat.END)
        else super.onBackPressed()
    }


    private fun setupAll() {
        setupButtons(); setupDrawer(); setupSeekBar()
        setupVolumeSlider(); setupShake(); setupGestures()
        setControlsEnabled(false); showIdle()
    }

    private fun setupButtons() {
        lifecycle.addObserver(binding.youtubePlayerView)
        binding.btnOpenFile.setOnClickListener   { tap { requestAudioPermissionAndPick() } }
        binding.btnOpenUrl.setOnClickListener    { tap { showUrlDialog() } }
        binding.btnPlayPause.setOnClickListener  { tap { togglePlayPause() } }
        binding.btnStop.setOnClickListener       { tap { stopMedia() } }
        binding.btnRestart.setOnClickListener    { tap { restartMedia() } }
        binding.btnSpeed.setOnClickListener      { tap { showSpeedDialog() } }
        binding.btnSleepTimer.setOnClickListener { tap { showSleepTimerDialog() } }
        binding.btnRepeat.setOnClickListener     { tap { cycleRepeat() } }
        binding.btnAbLoop.setOnClickListener     { tap { toggleAbLoop() } }
        binding.btnLyrics.setOnClickListener     { tap { pickLyricsFile() } }
        binding.btnMenu.setOnClickListener       { drawerLayout.openDrawer(GravityCompat.END) }
        binding.btnQueue.setOnClickListener      { tap { showQueueDialog() } }
        binding.btnBookmarkStar.setOnClickListener { tap { toggleBookmarkStar() } }
        binding.btnShare.setOnClickListener      { tap { shareTrack() } }
        binding.btnFullscreen.setOnClickListener { tap { openFullscreen() } }
    }

    private fun setupDrawer() {
        binding.btnCloseDrawer.setOnClickListener { drawerLayout.closeDrawer(GravityCompat.END) }

        val vizSwitch = binding.switchVisualizer
        vizEnabled = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("viz_enabled", false)
        vizSwitch.isChecked = vizEnabled
        binding.drawerItemVisualizer.setOnClickListener { vizSwitch.toggle() }
        vizSwitch.setOnCheckedChangeListener { _, checked ->
            vizEnabled = checked
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("viz_enabled", checked).apply()
            if (checked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startVis()
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VIS_PERM)
                }
            } else {
                stopVis(immediate = true)
            }
        }

        binding.drawerItemEq.setOnClickListener { 
            drawerLayout.closeDrawer(GravityCompat.END)
            val sessionId = musicService?.sessionId() ?: 0
            if (sessionId == 0) {
                showEqualizerDialog()
            } else {
                startActivity(Intent(this, com.example.mediaplayer.ui.EqualizerActivity::class.java).apply {
                    putExtra(com.example.mediaplayer.ui.EqualizerActivity.EXTRA_SESSION_ID, sessionId)
                })
            }
        }
        binding.drawerItemPlaylist.setOnClickListener { drawerLayout.closeDrawer(GravityCompat.END); openPlaylist() }
        binding.drawerItemBookmarks.setOnClickListener{ drawerLayout.closeDrawer(GravityCompat.END); showBookmarksPanel() }

        // History — open full page
        binding.drawerItemHistory.setOnClickListener  {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivityForResult(Intent(this, HistoryActivity::class.java), REQ_HISTORY)
        }

        binding.drawerItemStats.setOnClickListener    { drawerLayout.closeDrawer(GravityCompat.END); startActivity(Intent(this, StatsActivity::class.java)) }

        val themeSwitch = binding.switchTheme
        themeSwitch.isChecked = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dark_theme", true)
        
        binding.drawerItemTheme.setOnClickListener { themeSwitch.toggle() }
        
        themeSwitch.setOnCheckedChangeListener { _, checked ->
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (checked == prefs.getBoolean("dark_theme", true)) return@setOnCheckedChangeListener
            
            prefs.edit().putBoolean("dark_theme", checked).apply()
            
            // Set mode and recreate
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )

            recreate()
        }
    }

    private fun tap(action: () -> Unit) { HapticHelper.tap(this); action() }

    private fun attachServiceCallbacks() {
        val svc = musicService ?: return
        svc.onPrepared = {
            runOnUiThread {
                showLoading(false); setControlsEnabled(true)
                setPlayPause(true); startSeek(); startWave()
                startVis()
                binding.tvStatus.text = "Playing"
                lifecycleScope.launch { db.statsDao().insert(PlayStat(trackName = currentName)) }
                statsManager.onPlaybackStarted()
                statsManager.recordTrackPlay(currentName)
                saveHistory(currentUri, currentName)
            }
        }
        svc.onCompletion    = { runOnUiThread { handleCompletion() } }
        svc.onError         = { msg -> runOnUiThread { showError(msg) } }
        svc.onPlayStateChanged = { playing ->
            runOnUiThread {
                setPlayPause(playing)
                if (playing) {
                    startSeek()
                    startWave()
                    resumeVis()
                } else {
                    stopSeek()
                    stopWave()
                    pauseVis()
                }
            }
        }
        svc.onSkipPrev = { runOnUiThread { skipQueue(-1) } }
        svc.onSkipNext = { runOnUiThread { skipQueue(1) } }
    }

    private fun requestAudioPermissionAndPick() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
            openAudioPicker()
        else ActivityCompat.requestPermissions(this, arrayOf(perm), REQ_PERM)
    }

    private fun openAudioPicker() {
        startActivityForResult(
            Intent.createChooser(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply { 
                    type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Select Audio"
            ), REQ_AUDIO
        )
    }

    private fun playAudio(uri: Uri) {
        stopMedia(full = (mode == Mode.VIDEO || mode == Mode.YOUTUBE))
        mode = Mode.AUDIO; currentUri = uri; currentName = getName(uri)
        showAudio(); showLoading(true)
        binding.tvMediaTitle.text = currentName
        binding.layoutVideoOverlay.visibility = View.GONE
        isBookmarked = false; updateBookmarkStar()
        lyrics = emptyList(); lastLyricIdx = -1
        binding.tvLyrics.visibility = View.GONE
        fetchLyricsAuto(currentName)

        musicService?.playAudio(uri, currentName) ?: run { showError("Service not ready"); return }

        val existsInQueue = queue.any { it.uriString == uri.toString() }
        if (!existsInQueue) {
            queue.clear()
            queue.add(PlaylistItem(playlistId = 0, uriString = uri.toString(), name = currentName))
            queueIdx = 0
        } else {
            queueIdx = queue.indexOfFirst { it.uriString == uri.toString() }.coerceAtLeast(0)
        }
    }

    private fun togglePlayPause() {
        when (mode) {
            Mode.AUDIO   -> musicService?.let { if (it.isPlaying()) it.pause() else it.play() }
            Mode.VIDEO   -> exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
            Mode.YOUTUBE -> if (ytPlaying) youtubePlayer?.pause() else youtubePlayer?.play()
            Mode.NONE    -> Unit
        }
    }

    private fun handleCompletion() {
        statsManager.onPlaybackStopped()
        setPlayPause(false); stopSeek(); stopWave(); stopVis(immediate = true)
        binding.tvStatus.text = "Complete"
        binding.seekBar.progress = binding.seekBar.max
        cancelSleepTimer()
        when (repeatMode) {
            RepeatMode.ONE -> currentUri?.let { playAudio(it) }
            RepeatMode.ALL -> skipQueue(1)
            RepeatMode.OFF -> if (queueIdx < queue.size - 1) skipQueue(1)
        }
    }

    private fun showUrlDialog() {
        val db2 = DialogUrlBinding.inflate(layoutInflater)
        db2.etUrl.setText("https://vjs.zencdn.net/v/oceans.mp4")
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Stream URL").setView(db2.root)
            .setPositiveButton("Stream") { _, _ ->
                val url = db2.etUrl.text.toString().trim()
                if (url.isNotEmpty()) streamVideo(url) else toast("Enter a URL")
            }.setNegativeButton("Cancel", null).show()
    }

    @OptIn(UnstableApi::class)
    private fun streamVideo(url: String) {
        val ytId = ytId(url); if (ytId != null) { playYt(ytId); return }
        stopMedia(full = true)
        mode = Mode.VIDEO; currentVideoUrl = url
        showVideo(); showLoading(true)
        binding.tvMediaTitle.text = Uri.parse(url).lastPathSegment ?: "Video"
        binding.tvStatus.text = "Buffering…"
        binding.layoutVideoOverlay.visibility = View.VISIBLE

        val hf = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        val mf = DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, hf))

        exoPlayer = ExoPlayer.Builder(this).setMediaSourceFactory(mf).build().also { p ->
            binding.playerView.player = p
            p.setPlaybackSpeed(speed)
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    setPlayPause(isPlaying)
                    if (isPlaying) startSeek() else stopSeek()
                }
                override fun onPlaybackStateChanged(s: Int) {
                    when (s) {
                        Player.STATE_READY     -> { 
                            showLoading(false); setControlsEnabled(true)
                            setPlayPause(p.isPlaying)
                            if (p.isPlaying) startSeek()
                            binding.tvStatus.text = "Streaming" 
                        }
                        Player.STATE_ENDED     -> { setPlayPause(false); stopSeek(); binding.tvStatus.text = "Complete"; cancelSleepTimer() }
                        Player.STATE_BUFFERING -> { showLoading(true); binding.tvStatus.text = "Buffering…" }
                        else -> Unit
                    }
                }
                override fun onPlayerError(e: PlaybackException) { showLoading(false); showError(e.localizedMessage ?: "Error") }
            })
        }
        val mb = MediaItem.Builder().setUri(url)
        when { url.contains(".m3u8") -> mb.setMimeType(MimeTypes.APPLICATION_M3U8); url.contains(".mpd") -> mb.setMimeType(MimeTypes.APPLICATION_MPD) }
        exoPlayer?.setMediaItem(mb.build()); exoPlayer?.prepare(); exoPlayer?.playWhenReady = true
    }

    private fun openFullscreen() {
        if (mode != Mode.VIDEO || currentVideoUrl.isEmpty()) return
        val pos = exoPlayer?.currentPosition ?: 0L
        exoPlayer?.pause()
        startActivityForResult(
            Intent(this, FullscreenVideoActivity::class.java).apply {
                putExtra(FullscreenVideoActivity.EXTRA_URL, currentVideoUrl)
                putExtra(FullscreenVideoActivity.EXTRA_POSITION, pos)
            }, REQ_FULLSCREEN
        )
    }

    private fun playYt(id: String) {
        stopMedia(full = true); mode = Mode.YOUTUBE
        showYoutube(); binding.tvMediaTitle.text = "YouTube"
        binding.layoutVideoOverlay.visibility = View.GONE
        val l = object : com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener() {
            override fun onReady(p: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer) {
                youtubePlayer = p; p.loadVideo(id, 0f); ytPlaying = true
                setPlayPause(true); setControlsEnabled(true)
            }
            override fun onVideoDuration(p: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer, d: Float) {
                binding.seekBar.max = d.toInt(); binding.tvTotalTime.text = fmt(d.toInt() * 1000)
            }
            override fun onCurrentSecond(p: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer, s: Float) {
                binding.seekBar.progress = s.toInt(); binding.tvCurrentTime.text = fmt(s.toInt() * 1000)
            }
            override fun onStateChange(p: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer, s: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState) {
                ytPlaying = s == com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.PLAYING
                setPlayPause(ytPlaying)
            }
        }
        youtubePlayer?.let { it.loadVideo(id, 0f); setPlayPause(true); setControlsEnabled(true); return }
        binding.youtubePlayerView.initialize(l)
    }

    private fun ytId(url: String): String? {
        listOf("watch\\?v=([\\w-]{11})", "youtu\\.be/([\\w-]{11})", "embed/([\\w-]{11})")
            .forEach { Regex(it, RegexOption.IGNORE_CASE).find(url)?.groups?.get(1)?.value?.let { return it } }
        return null
    }

    private fun stopMedia(full: Boolean = false) {
        if (mode == Mode.AUDIO) statsManager.onPlaybackStopped()

        musicService?.pause() 
        exoPlayer?.pause()    
        youtubePlayer?.pause()
        
        if (full) {
            musicService?.stopPb()
            exoPlayer?.release()
            exoPlayer = null
            mode = Mode.NONE
            binding.seekBar.progress = 0; binding.tvCurrentTime.text = "0:00"
            binding.tvStatus.text = "Stopped"
        } else {
            binding.tvStatus.text = "Paused"
        }
        
        stopSeek(); stopWave(); stopVis(immediate = true); setPlayPause(false)
    }

    private fun restartMedia() {
        when (mode) {
            Mode.AUDIO   -> currentUri?.let { playAudio(it) }
            Mode.VIDEO   -> exoPlayer?.let { it.seekTo(0); it.playWhenReady = true; setPlayPause(true); startSeek() }
            Mode.YOUTUBE -> youtubePlayer?.let { it.seekTo(0f); it.play() }
            Mode.NONE    -> Unit
        }
    }

    private fun openPlaylist() {
        startActivityForResult(Intent(this, PlaylistActivity::class.java), REQ_PLAYLIST)
    }

    private fun skipQueue(delta: Int) {
        if (queue.isEmpty()) return
        queueIdx = (queueIdx + delta).coerceIn(0, queue.size - 1)
        playAudio(Uri.parse(queue[queueIdx].uriString))
    }

    private fun showQueueDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_queue, null)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvQueue)
        val btnClear = dialogView.findViewById<Button>(R.id.btnQueueClear)
        val btnClose = dialogView.findViewById<Button>(R.id.btnQueueClose)

        val dialog = AlertDialog.Builder(this, R.style.DialogTheme)
            .setView(dialogView)
            .create()

        fun updateList() {
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = QueueAdapter(queue, queueIdx,
                onPlay = { idx ->
                    queueIdx = idx
                    playAudio(Uri.parse(queue[idx].uriString))
                    dialog.dismiss()
                },
                onDelete = { idx ->
                    queue.removeAt(idx)
                    if (queueIdx >= queue.size) queueIdx = (queue.size - 1).coerceAtLeast(0)
                    updateList() // Refresh UI immediately
                }
            )
        }

        updateList()

        btnClear.setOnClickListener {
            queue.clear()
            queueIdx = 0
            dialog.dismiss()
            toast("Queue cleared")
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    class QueueAdapter(
        private val items: List<PlaylistItem>,
        private val currentIdx: Int,
        private val onPlay: (Int) -> Unit,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<QueueAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvTrackName)
            val index: TextView = v.findViewById(R.id.tvTrackIndex)
            val artist: TextView = v.findViewById(R.id.tvTrackArtist)
            val more: ImageButton = v.findViewById(R.id.btnDeleteTrack)
            val root: View = v
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.index.text = (position + 1).toString()
            holder.title.text = item.name
            holder.artist.text = if (position == currentIdx) "Now Playing" else "In Queue"
            holder.title.setTextColor(if (position == currentIdx) 0xFF00E5FF.toInt() else 0xFFF2F2FF.toInt())
            
            holder.root.setOnClickListener { onPlay(position) }
            holder.more.setOnClickListener {
                val p = PopupMenu(holder.more.context, holder.more)
                p.menu.add("Remove from Queue")
                p.setOnMenuItemClickListener { onDelete(position); true }
                p.show()
            }
        }
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.5×", "0.75×", "1.0× (Normal)", "1.25×", "1.5×", "2.0×")
        val cur = speedOptions.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }.coerceAtMost(speedOptions.size - 1)
        
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(labels, cur) { d, i -> 
                setSpeed(speedOptions[i])
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setSpeed(s: Float) {
        speed = s; musicService?.setSpeed(s); exoPlayer?.setPlaybackSpeed(s)
        binding.tvSpeedBadge.text = "${s}×"
        binding.tvSpeedBadge.visibility = if (s == 1.0f) View.GONE else View.VISIBLE
    }

    private fun showSleepTimerDialog() {
        val opts = arrayOf("5 min","10 min","15 min","30 min","60 min","Cancel Timer")
        val mins = intArrayOf(5, 10, 15, 30, 60, 0)
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("⏱ Sleep Timer")
            .setItems(opts) { _, i -> if (i == 5) cancelSleepTimer() else startSleepTimer(mins[i].toLong() * 60_000) }
            .show()
    }

    private fun startSleepTimer(ms: Long) {
        cancelSleepTimer()
        binding.tvSleepTimer.visibility = View.VISIBLE
        sleepTimer = object : android.os.CountDownTimer(ms, 1000) {
            override fun onTick(left: Long) {
                binding.tvSleepTimer.text = "⏱ %d:%02d".format(left / 60_000, (left % 60_000) / 1000)
                if (left < 60_000) binding.tvSleepTimer.setTextColor(getColor(R.color.accent_red))
            }
            override fun onFinish() { stopMedia(); binding.tvSleepTimer.visibility = View.GONE; toast("😴 Sleep timer ended") }
        }.start()
    }

    private fun cancelSleepTimer() {
        sleepTimer?.cancel(); sleepTimer = null
        binding.tvSleepTimer.visibility = View.GONE
        binding.tvSleepTimer.setTextColor(getColor(R.color.accent_cyan))
    }

    private fun saveHistory(uri: Uri?, name: String) {
        uri ?: return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = prefs.getString(HIST_KEY, "")!!.split("\n").filter { it.contains("|||") }.toMutableList()
        list.removeAll { it.startsWith(uri.toString()) }
        list.add(0, "${uri}|||${name}")
        if (list.size > 20) list.subList(20, list.size).clear()
        prefs.edit().putString(HIST_KEY, list.joinToString("\n")).apply()
    }

    private fun toggleBookmarkStar() {
        if (mode != Mode.AUDIO || currentUri == null) { toast("Play audio first"); return }
        isBookmarked = !isBookmarked; updateBookmarkStar()
        if (isBookmarked) {
            lifecycleScope.launch {
                db.bookmarkDao().insert(Bookmark(uriString = currentUri.toString(),
                    trackName = currentName, label = currentName, positionMs = musicService?.posMs() ?: 0))
            }
            HapticHelper.success(this); toast("⭐ Bookmarked!")
        } else {
            toast("Bookmark removed")
        }
    }

    private fun updateBookmarkStar() {
        if (isBookmarked) {
            binding.btnBookmarkStar.setImageResource(R.drawable.ic_star_filled)
            binding.btnBookmarkStar.setColorFilter(getColor(R.color.accent_red))
        } else {
            binding.btnBookmarkStar.setImageResource(R.drawable.ic_star_outline)
            binding.btnBookmarkStar.setColorFilter(getColor(R.color.text_secondary))
        }
    }

    private fun showBookmarksPanel() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_bookmarks, null)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvBookmarks)
        val btnClear = dialogView.findViewById<Button>(R.id.btnClearAll)
        val btnClose = dialogView.findViewById<Button>(R.id.btnClose)
        val emptyLayout = dialogView.findViewById<View>(R.id.layoutEmptyBookmarks)

        val dialog = AlertDialog.Builder(this, R.style.DialogTheme)
            .setView(dialogView)
            .create()

        db.bookmarkDao().getAll().observe(this) { marks ->
            if (marks.isEmpty()) {
                emptyLayout.visibility = View.VISIBLE
                rv.visibility = View.GONE
                btnClear.visibility = View.GONE
            } else {
                emptyLayout.visibility = View.GONE
                rv.visibility = View.VISIBLE
                btnClear.visibility = View.VISIBLE
                rv.layoutManager = LinearLayoutManager(this)
                rv.adapter = BookmarkAdapter(marks, 
                    onPlay = { m ->
                        if (currentUri?.toString() == m.uriString) musicService?.seekTo(m.positionMs)
                        else try { playAudio(Uri.parse(m.uriString)) } catch (_: Exception) { toast("Cannot open") }
                        dialog.dismiss()
                    },
                    onDelete = { m ->
                        lifecycleScope.launch { db.bookmarkDao().delete(m) }
                    }
                )
            }
        }

        btnClear.setOnClickListener {
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Clear All Bookmarks?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        val marks = db.bookmarkDao().getAll().value ?: return@launch
                        marks.forEach { db.bookmarkDao().delete(it) }
                        dialog.dismiss()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    class BookmarkAdapter(
        private val items: List<Bookmark>,
        private val onPlay: (Bookmark) -> Unit,
        private val onDelete: (Bookmark) -> Unit
    ) : RecyclerView.Adapter<BookmarkAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvBookmarkTitle)
            val time: TextView = v.findViewById(R.id.tvBookmarkTime)
            val delete: ImageView = v.findViewById(R.id.btnDeleteBookmark)
            val btnPlay: View = v.findViewById(R.id.btnPlayBookmark)
            val root: View = v
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false))
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            holder.title.text = m.trackName
            val s = m.positionMs / 1000
            holder.time.text = "at %d:%02d".format(s / 60, s % 60)
            holder.delete.setOnClickListener { onDelete(m) }
            holder.btnPlay.setOnClickListener { onPlay(m) }
            holder.root.setOnClickListener { onPlay(m) }
        }
    }

    private fun shareTrack() {
        val uri = currentUri ?: run { toast("Nothing playing"); return }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, currentName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share via"
        ))
    }

    private fun startVis() {
        if (!vizEnabled || mode != Mode.AUDIO) return
        val sid = musicService?.sessionId() ?: return
        if (sid == 0) return

        if (visualizer != null && vizSessionId == sid) {
            try {
                visualizer?.enabled = true
                binding.visualizerView.visibility = View.VISIBLE
                binding.layoutAudioBars.visibility = View.GONE
            } catch (_: Exception) { buildVisualizer(sid) }
            return
        }

        // New session — build from scratch
        buildVisualizer(sid)
    }

    private fun buildVisualizer(sid: Int) {
        releaseVisualizer()
        try {
            visualizer = Visualizer(sid).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, w: ByteArray, sr: Int) {}
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, sr: Int) {
                        if (!isFinishing) runOnUiThread { binding.visualizerView.update(fft) }
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
            vizSessionId = sid
            binding.visualizerView.visibility = View.VISIBLE
            binding.layoutAudioBars.visibility = View.GONE
        } catch (_: Exception) {
            releaseVisualizer()
            binding.visualizerView.visibility = View.GONE
            binding.layoutAudioBars.visibility = View.VISIBLE
        }
    }

    private fun pauseVis() {
        try { visualizer?.enabled = false } catch (_: Exception) {}
        if (!isFinishing) {
            binding.visualizerView.clear()
            binding.visualizerView.visibility = View.GONE
            binding.layoutAudioBars.visibility = View.VISIBLE
        }
    }

    private fun resumeVis() {
        if (!vizEnabled || mode != Mode.AUDIO) return
        val sid = musicService?.sessionId() ?: return
        if (sid == 0) return

        if (visualizer != null && vizSessionId == sid) {
            // Same session — just flip the switch back on
            try {
                visualizer?.enabled = true
                binding.visualizerView.visibility = View.VISIBLE
                binding.layoutAudioBars.visibility = View.GONE
                return
            } catch (_: Exception) { /* fall through to rebuild */ }
        }

        buildVisualizer(sid)
    }

    private fun stopVis(immediate: Boolean = false) {
        releaseVisualizer()
        if (immediate && !isFinishing) runOnUiThread {
            binding.visualizerView.clear()
            binding.visualizerView.visibility = View.GONE
            binding.layoutAudioBars.visibility = View.VISIBLE
        }
    }

    private fun releaseVisualizer() {
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        vizSessionId = 0
    }

    private fun pickLyricsFile() {
        startActivityForResult(
            Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, "Select LRC file"),
            REQ_LYRICS
        )
    }

    private fun loadLyricsFromFile(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            applyLyrics(LyricsParser.parse(content))
            toast("Lyrics loaded from file")
        } catch (_: Exception) { toast("Could not load lyrics file") }
    }

    private fun fetchLyricsAuto(trackName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val parts = trackName.split(" - ", limit = 2)
                val artist = if (parts.size == 2) parts[0].trim() else ""
                val title  = if (parts.size == 2) parts[1].trim() else trackName.trim()
                    .replace(Regex("\\.[a-zA-Z0-9]{2,5}$"), "")   // remove extension

                val query = if (artist.isNotEmpty()) "artist_name=${encode(artist)}&track_name=${encode(title)}"
                            else                     "track_name=${encode(title)}"
                val json = URL("https://lrclib.net/api/search?$query").readText()
                val arr  = org.json.JSONArray(json)
                if (arr.length() == 0) return@launch

                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val syncedLrc = item.optString("syncedLyrics", "")
                    if (syncedLrc.isNotEmpty()) {
                        val parsed = LyricsParser.parse(syncedLrc)
                        if (parsed.isNotEmpty()) {
                            withContext(Dispatchers.Main) { applyLyrics(parsed) }
                            return@launch
                        }
                    }
                }
                val plain = arr.getJSONObject(0).optString("plainLyrics", "")
                if (plain.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        binding.tvLyrics.text = plain.lines().firstOrNull() ?: ""
                        binding.tvLyrics.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) { /* Network not available or not found — silent fail */ }
        }
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun applyLyrics(parsed: List<LyricLine>) {
        lyrics = parsed; lastLyricIdx = -1
        if (lyrics.isNotEmpty()) {
            binding.tvLyrics.text = lyrics[0].text
            binding.tvLyrics.visibility = View.VISIBLE
        }
    }

    private fun updateLyricsHighlight() {
        if (lyrics.isEmpty() || mode != Mode.AUDIO) return
        val pos = musicService?.posMs()?.toLong() ?: return
        val idx = LyricsParser.currentIndex(lyrics, pos)
        if (idx != lastLyricIdx && idx >= 0) {
            lastLyricIdx = idx
            binding.tvLyrics.animate().alpha(0f).setDuration(100).withEndAction {
                binding.tvLyrics.text = lyrics[idx].text
                binding.tvLyrics.animate().alpha(1f).setDuration(150).start()
            }.start()
        }
    }

    private fun toggleAbLoop() {
        if (mode != Mode.AUDIO) { toast("Play audio first"); return }
        when {
            loopA < 0 -> { loopA = musicService?.posMs() ?: 0; binding.btnAbLoop.text = "Set B" }
            loopB < 0 -> {
                loopB = musicService?.posMs() ?: 0
                if (loopB <= loopA) { loopB = -1; toast("B must be after A"); return }
                abLoopEnabled = true; abHandler.post(abRunnable)
                binding.btnAbLoop.text = "A-B ✓"
                binding.btnAbLoop.setTextColor(getColor(R.color.accent_cyan))
            }
            else -> {
                loopA = -1; loopB = -1; abLoopEnabled = false
                abHandler.removeCallbacks(abRunnable)
                binding.btnAbLoop.text = "A→B Loop"
                binding.btnAbLoop.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    private fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.OFF
        }
        val (label, color) = when (repeatMode) {
            RepeatMode.OFF -> "Repeat"  to getColor(R.color.text_secondary)
            RepeatMode.ONE -> "Rpt ×1"  to getColor(R.color.accent_cyan)
            RepeatMode.ALL -> "Rpt All" to getColor(R.color.accent_purple)
        }
        binding.btnRepeat.text = label; binding.btnRepeat.setTextColor(color)
    }

    private fun showEqualizerDialog() {
        val svc = musicService
        if (svc == null || !svc.isAudioPrepared) { toast("Play audio first"); return }
        if (svc.numBands() == 0) { toast("EQ not available on this device"); return }
        val view = layoutInflater.inflate(R.layout.dialog_equalizer, null)
        val llBands = view.findViewById<android.widget.LinearLayout>(R.id.llEqBands)
        val swEq    = view.findViewById<android.widget.Switch>(R.id.swEqEnabled)
        val swBass  = view.findViewById<android.widget.Switch>(R.id.swBassBoost)
        val skBass  = view.findViewById<SeekBar>(R.id.seekBass)
        swEq.isChecked = svc.equalizerEnabled; skBass.max = 1000; skBass.progress = svc.bassBoostStrength.toInt()
        val min = svc.bandMin(); val max = svc.bandMax(); val range = max - min
        for (b in 0 until svc.numBands()) {
            val band = b.toShort()
            llBands.addView(android.widget.TextView(this).apply {
                text = "${svc.bandHz(band)} Hz"; setTextColor(0xFF6B6B88.toInt()); textSize = 10f; gravity = android.view.Gravity.CENTER
            })
            llBands.addView(SeekBar(this).apply {
                this.max = range.toInt(); progress = (svc.bandLevel(band) - min).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_cyan))
                thumbTintList    = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_cyan))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) svc.setBand(band, (min + p).toShort()) }
                    override fun onStartTrackingTouch(s: SeekBar) {}; override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
        }
        swEq.setOnCheckedChangeListener { _, c -> svc.setEqEnabled(c) }
        skBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) svc.setBass(p.toShort()) }
            override fun onStartTrackingTouch(s: SeekBar) {}; override fun onStopTrackingTouch(s: SeekBar) {}
        })
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("🎛 Equalizer").setView(view).setPositiveButton("Done", null).show()
    }

    private fun setupShake() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shakeDetector = ShakeDetector { runOnUiThread { HapticHelper.warning(this); skipQueue(1); toast("Shake → Next") } }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val cardW = binding.cardMedia.width
                val isLeftSide = e.x < cardW / 2f
                val seekMs = if (isLeftSide) -5000 else 5000
                when (mode) {
                    Mode.AUDIO -> {
                        val newPos = ((musicService?.posMs() ?: 0) + seekMs).coerceAtLeast(0)
                        musicService?.seekTo(newPos)
                        toast(if (isLeftSide) "⏪ -5s" else "⏩ +5s")
                    }
                    Mode.VIDEO -> exoPlayer?.let {
                        it.seekTo((it.currentPosition + seekMs).coerceAtLeast(0))
                        toast(if (isLeftSide) "⏪ -5s" else "⏩ +5s")
                    }
                    else -> Unit
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                val dX = e2.x - (e1?.x ?: 0f); val dY = e2.y - (e1?.y ?: 0f)
                return when {
                    abs(dX) > 80 && abs(vX) > 80 && abs(dX) > abs(dY) -> { if (dX < 0) skipQueue(1) else skipQueue(-1); true }
                    dY > 80 && abs(dY) > abs(dX) -> { stopMedia(); true }
                    else -> false
                }
            }
        })
        binding.cardMedia.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); false }
    }

    private fun setupVolumeSlider() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        binding.seekVolume.max = max
        binding.seekVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                binding.seekVolume.progress = currentVol
            }
        }
        volumeObserver?.let {
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
        stopVis(immediate = true)
        seekHandler.removeCallbacksAndMessages(null)
        abHandler.removeCallbacksAndMessages(null)
        waveAnimators.forEach { it.cancel() }
        sleepTimer?.cancel()
        exoPlayer?.release(); exoPlayer = null
        if (serviceBound) { unbindService(serviceConn); serviceBound = false }
    }

    private fun syncVolumeSlider() {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (binding.seekVolume.progress != cur) binding.seekVolume.progress = cur
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (mode) {
                    Mode.AUDIO   -> musicService?.seekTo(p)
                    Mode.VIDEO   -> exoPlayer?.seekTo(p.toLong())
                    Mode.YOUTUBE -> youtubePlayer?.seekTo(p.toFloat())
                    Mode.NONE    -> Unit
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun updateSeekBar() {
        when (mode) {
            Mode.AUDIO -> musicService?.let { svc ->
                if (svc.isAudioPrepared) {
                    binding.seekBar.max = svc.durMs(); binding.seekBar.progress = svc.posMs()
                    binding.tvCurrentTime.text = fmt(svc.posMs()); binding.tvTotalTime.text = fmt(svc.durMs())
                }
            }
            Mode.VIDEO -> exoPlayer?.let { p ->
                val dur = p.duration; val pos = p.currentPosition
                if (dur > 0) { binding.seekBar.max = dur.toInt(); binding.seekBar.progress = pos.toInt()
                    binding.tvCurrentTime.text = fmt(pos.toInt()); binding.tvTotalTime.text = fmt(dur.toInt()) }
            }
            else -> Unit
        }
    }

    private fun startSeek() { seekHandler.removeCallbacks(seekRunnable); seekHandler.post(seekRunnable) }
    private fun stopSeek()  { seekHandler.removeCallbacks(seekRunnable) }

    private fun startWave() {
        stopWave()
        val bars = listOf(binding.bar1,binding.bar2,binding.bar3,binding.bar4,binding.bar5,binding.bar6,binding.bar7,
                          binding.bar8,binding.bar9,binding.bar10,binding.bar11,binding.bar12,binding.bar13,binding.bar14)
        val maxS = floatArrayOf(0.9f,0.6f,1.0f,0.55f,0.85f,0.7f,1.0f,0.65f,0.9f,0.5f,0.8f,0.6f,0.95f,0.7f)
        bars.forEachIndexed { i, v ->
            ObjectAnimator.ofFloat(v, "scaleY", 0.12f, maxS[i]).apply {
                duration = (500 + (i%5)*80).toLong(); repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE; interpolator = AccelerateDecelerateInterpolator()
                startDelay = (i*55).toLong(); start()
            }.also { waveAnimators.add(it) }
        }
    }

    private fun stopWave() {
        waveAnimators.forEach { it.cancel() }; waveAnimators.clear()
        listOf(binding.bar1,binding.bar2,binding.bar3,binding.bar4,binding.bar5,binding.bar6,binding.bar7,
               binding.bar8,binding.bar9,binding.bar10,binding.bar11,binding.bar12,binding.bar13,binding.bar14)
            .forEach { it.animate().scaleY(0.12f).setDuration(300).start() }
    }

    private fun showIdle() {
        binding.playerView.visibility = View.GONE; binding.youtubePlayerView.visibility = View.GONE
        binding.layoutAudioViz.visibility = View.VISIBLE; binding.layoutVideoOverlay.visibility = View.GONE
        binding.tvMediaTitle.text = "No media loaded"; binding.tvStatus.text = "Open a file or stream a URL"
    }
    private fun showAudio() {
        binding.playerView.visibility = View.GONE; binding.youtubePlayerView.visibility = View.GONE
        binding.layoutAudioViz.visibility = View.VISIBLE; binding.layoutVideoOverlay.visibility = View.GONE
    }
    private fun showVideo() {
        binding.layoutAudioViz.visibility = View.GONE; binding.youtubePlayerView.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
    }
    private fun showYoutube() {
        binding.layoutAudioViz.visibility = View.GONE; binding.playerView.visibility = View.GONE
        binding.youtubePlayerView.visibility = View.VISIBLE; binding.layoutVideoOverlay.visibility = View.GONE
    }
    private fun showLoading(show: Boolean) { binding.progressLoading.visibility = if (show) View.VISIBLE else View.GONE }
    private fun setControlsEnabled(on: Boolean) {
        binding.btnPlayPause.isEnabled = on; binding.btnStop.isEnabled = on
        binding.btnRestart.isEnabled = on; binding.seekBar.isEnabled = on
    }
    private fun setPlayPause(playing: Boolean) {
        if (playing) { binding.btnPlayPause.setIconResource(R.drawable.ic_pause); binding.btnPlayPause.text = "PAUSE" }
        else         { binding.btnPlayPause.setIconResource(R.drawable.ic_play);  binding.btnPlayPause.text = "PLAY"  }
    }
    private fun showError(msg: String) { mode = Mode.NONE; binding.tvStatus.text = "⚠ Error"; setControlsEnabled(false); toast(msg) }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun runEntrance() {
        binding.cardMedia.also    { it.translationY = 80f;  it.alpha = 0f }
            .animate().translationY(0f).alpha(1f).setDuration(480).setStartDelay(80).setInterpolator(DecelerateInterpolator(2f)).start()
        binding.cardControls.also { it.translationY = 140f; it.alpha = 0f }
            .animate().translationY(0f).alpha(1f).setDuration(480).setStartDelay(180).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun getName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            c.moveToFirst(); if (idx >= 0) c.getString(idx) else uri.lastPathSegment ?: "Audio"
        } ?: (uri.lastPathSegment ?: "Audio")
    } catch (_: Exception) { uri.lastPathSegment ?: "Audio" }

    private fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(reqCode: Int, resCode: Int, data: Intent?) {
        super.onActivityResult(reqCode, resCode, data)
        when {
            reqCode == REQ_AUDIO && resCode == Activity.RESULT_OK ->
                data?.data?.let { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                    playAudio(uri)
                }
            reqCode == REQ_LYRICS && resCode == Activity.RESULT_OK ->
                data?.data?.let { loadLyricsFromFile(it) }
            reqCode == REQ_HISTORY && resCode == HistoryActivity.RESULT_PLAY -> {
                val uriStr = data?.getStringExtra(HistoryActivity.EXTRA_URI) ?: return
                val name   = data.getStringExtra(HistoryActivity.EXTRA_NAME) ?: "Audio"
                try { playAudio(Uri.parse(uriStr)) } catch (_: Exception) { toast("Cannot open file") }
            }
            reqCode == REQ_PLAYLIST && resCode == PlaylistActivity.RESULT_PLAY -> {
                val uris  = data?.getStringArrayListExtra(PlaylistActivity.EXTRA_URIS)  ?: return
                val names = data.getStringArrayListExtra(PlaylistActivity.EXTRA_NAMES) ?: return
                val start = data.getIntExtra(PlaylistActivity.EXTRA_START, 0)
                queue.clear()
                uris.forEachIndexed { i, u -> queue.add(PlaylistItem(playlistId = 0, uriString = u, name = names.getOrElse(i) { "Track" })) }
                queueIdx = start.coerceIn(0, queue.size - 1)
                playAudio(Uri.parse(queue[queueIdx].uriString))
            }
            reqCode == REQ_FULLSCREEN -> {
                // Resume from fullscreen at position returned
                val pos = data?.getLongExtra(FullscreenVideoActivity.EXTRA_POSITION, 0L) ?: 0L
                exoPlayer?.let { it.seekTo(pos); it.playWhenReady = true; setPlayPause(true); startSeek() }
            }
        }
    }

    override fun onRequestPermissionsResult(reqCode: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(reqCode, perms, results)
        val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        when (reqCode) {
            REQ_PERM -> {
                if (granted) openAudioPicker()
                else toast("Storage permission needed")
            }
            REQ_VIS_PERM -> {
                if (granted) startVis()
                else {
                    vizEnabled = false
                    binding.switchVisualizer.isChecked = false
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("viz_enabled", false).apply()
                    toast("Audio Record permission needed for Visualizer")
                }
            }
        }
    }
}
