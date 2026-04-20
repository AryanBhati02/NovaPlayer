package com.example.mediaplayer.ui

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.mediaplayer.R

class EqualizerActivity : AppCompatActivity() {

    private var equalizer:  Equalizer?   = null
    private var bassBoost:  BassBoost?   = null
    private var virtualizer: Virtualizer? = null

    companion object {
        const val EXTRA_SESSION_ID = "audio_session_id"

        private val PRESETS = arrayOf(
            "Flat",  "Bass Boost", "Treble Boost", "Rock", "Pop", "Jazz"
        )

        private val PRESET_LEVELS = arrayOf(
            intArrayOf(0,    0,    0,    0,    0   ),  // Flat
            intArrayOf(600,  400,  0,   -200, -400 ),  // Bass Boost
            intArrayOf(-400,-200,  0,   400,  600  ),  // Treble Boost
            intArrayOf(400,  200,  0,   200,  400  ),  // Rock
            intArrayOf(-200, 100,  300,  200,  0   ),  // Pop
            intArrayOf(300,  100, -100,  200,  300  )   // Jazz
        )
    }

    private val bandSeekBars = mutableListOf<SeekBar>()
    private var minLevel = 0.toShort()
    private var spinnerInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_equalizer)

        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, 0)

        val btnBack         = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEqBack)
        val spinnerPresets  = findViewById<Spinner>(R.id.spinnerPresets)
        val llBands         = findViewById<LinearLayout>(R.id.llEqBands)
        val switchBass      = findViewById<SwitchCompat>(R.id.switchBassBoost)
        val switchVirt      = findViewById<SwitchCompat>(R.id.switchVirtualizer)

        btnBack.setOnClickListener { finish() }

        if (sessionId == 0) {
            Toast.makeText(this, "Play audio first to use the equalizer", Toast.LENGTH_LONG).show()
            finish(); return
        }

        try {
            equalizer   = Equalizer(0, sessionId).apply { enabled = true }
            bassBoost   = BassBoost(0, sessionId).apply { enabled = false }
            virtualizer = Virtualizer(0, sessionId).apply { enabled = false }
        } catch (e: Exception) {
            Toast.makeText(this, "Equalizer not supported: ${e.message}", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val eq       = equalizer!!
        val numBands = eq.numberOfBands.toInt()
        minLevel     = eq.bandLevelRange[0]
        val maxLevel = eq.bandLevelRange[1]
        val range    = (maxLevel - minLevel).toInt()

        // Build band seekbars dynamically
        val barColors = listOf("#00E5FF","#4BA8F0","#7090EE","#9078EB","#9B5CF6")
        for (b in 0 until numBands) {
            val band  = b.toShort()
            val freqHz = eq.getCenterFreq(band) / 1000

            val label = TextView(this).apply {
                text = "${freqHz} Hz"
                setTextColor(0xFF6B6B88.toInt()); textSize = 11f
                setPadding(0, 16, 0, 2)
            }

            val seek = SeekBar(this).apply {
                max      = range
                progress = (eq.getBandLevel(band) - minLevel).toInt()
                val col  = android.graphics.Color.parseColor(barColors.getOrElse(b) { "#00E5FF" })
                progressTintList = android.content.res.ColorStateList.valueOf(col)
                thumbTintList    = android.content.res.ColorStateList.valueOf(col)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) eq.setBandLevel(band, (minLevel + p).toShort())
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }

            llBands.addView(label); llBands.addView(seek)
            bandSeekBars.add(seek)
        }

        // Preset spinner
        spinnerPresets.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, PRESETS)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                // Skip the automatic first trigger on adapter set — only apply user-chosen presets
                if (!spinnerInitialized) { spinnerInitialized = true; return }
                applyPreset(pos)
            }
        }

        // Bass Boost
        switchBass.setOnCheckedChangeListener { _, on ->
            try { bassBoost?.setStrength(if (on) 700 else 0); bassBoost?.enabled = on }
            catch (_: Exception) {}
        }

        // Virtualizer
        switchVirt.setOnCheckedChangeListener { _, on ->
            try { virtualizer?.setStrength(if (on) 700 else 0); virtualizer?.enabled = on }
            catch (_: Exception) {}
        }
    }

    private fun applyPreset(presetIdx: Int) {
        val eq = equalizer ?: return
        val levels = PRESET_LEVELS.getOrNull(presetIdx) ?: return
        val numBands = eq.numberOfBands.toInt()
        val center = ((eq.bandLevelRange[0] + eq.bandLevelRange[1]) / 2).toInt()

        for (b in 0 until numBands.coerceAtMost(levels.size)) {
            val newLevel = (center + levels[b]).toShort()
                .coerceIn(eq.bandLevelRange[0], eq.bandLevelRange[1])
            eq.setBandLevel(b.toShort(), newLevel)
            bandSeekBars.getOrNull(b)?.progress = (newLevel - minLevel).toInt()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

    }
}
