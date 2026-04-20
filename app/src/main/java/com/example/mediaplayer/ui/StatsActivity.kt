package com.example.mediaplayer.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.example.mediaplayer.R
import com.example.mediaplayer.util.StatsManager

class StatsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        val stats = StatsManager(this)

        findViewById<CardView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tvTodayTime).text  = stats.formatMs(stats.getTodayMs())
        findViewById<TextView>(R.id.tvWeekTime).text   = stats.formatMs(stats.getWeekMs())
        findViewById<TextView>(R.id.tvTotalTime).text  = stats.formatMs(stats.getTotalMs())
        val streak = stats.getStreak()
        findViewById<TextView>(R.id.tvStreak).text     = "$streak day${if (streak != 1) "s" else ""}"

        val top = stats.getMostPlayed(5)
        val topText = if (top.isEmpty()) "No plays recorded yet.\nStart playing music!"
        else top.mapIndexed { i, p ->
            val bar = "█".repeat((p.second.coerceAtMost(20)))
            "${i + 1}.  ${p.first}\n    $bar  ${p.second}×"
        }.joinToString("\n\n")
        findViewById<TextView>(R.id.tvTopTracksLabel).text = topText
    }
}
