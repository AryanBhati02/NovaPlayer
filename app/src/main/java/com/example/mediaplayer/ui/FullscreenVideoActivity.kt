package com.example.mediaplayer.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.mediaplayer.R

class FullscreenVideoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL      = "video_url"
        const val EXTRA_POSITION = "video_position"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen_video)


        enterFullscreen()

        playerView = findViewById(R.id.fsPlayerView)
        findViewById<android.widget.ImageButton>(R.id.btnExitFullscreen).setOnClickListener { finish() }

        val url      = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val position = intent.getLongExtra(EXTRA_POSITION, 0L)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this)
                .setDataSourceFactory(DefaultDataSource.Factory(this, httpFactory)))
            .build().also { p ->
                playerView.player = p
                val mb = MediaItem.Builder().setUri(url)
                when {
                    url.contains(".m3u8") -> mb.setMimeType(MimeTypes.APPLICATION_M3U8)
                    url.contains(".mpd")  -> mb.setMimeType(MimeTypes.APPLICATION_MPD)
                }
                p.setMediaItem(mb.build())
                p.prepare()
                p.seekTo(position)
                p.playWhenReady = true
            }
    }

    override fun onPause()   { super.onPause();   player?.pause() }
    override fun onResume()  { super.onResume();  enterFullscreen() }
    override fun onDestroy() {

        setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_POSITION, player?.currentPosition ?: 0L))
        player?.release(); player = null
        super.onDestroy()
    }

    private fun enterFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}
