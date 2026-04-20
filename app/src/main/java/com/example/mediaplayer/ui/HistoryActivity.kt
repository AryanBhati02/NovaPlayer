package com.example.mediaplayer.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mediaplayer.R

class HistoryActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PLAY    = Activity.RESULT_FIRST_USER + 10
        const val EXTRA_URI      = "play_uri"
        const val EXTRA_NAME     = "play_name"
        private const val PREFS  = "nova_prefs"
        private const val HIST   = "history"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val btnBack  = findViewById<android.widget.ImageButton>(R.id.btnHistoryBack)
        val btnClear = findViewById<android.widget.ImageButton>(R.id.btnHistoryClear)
        val rv       = findViewById<RecyclerView>(R.id.rvHistory)
        val tvEmpty  = findViewById<TextView>(R.id.tvHistoryEmpty)

        btnBack.setOnClickListener { finish() }
        btnClear.setOnClickListener {
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Clear History?")
                .setPositiveButton("Clear") { _, _ ->
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(HIST).apply()
                    finish()
                }
                .setNegativeButton("Cancel", null).show()
        }

        val history = loadHistory()
        if (history.isEmpty()) {
            tvEmpty.visibility = android.view.View.VISIBLE
            rv.visibility      = android.view.View.GONE
        } else {
            tvEmpty.visibility = android.view.View.GONE
            rv.visibility      = android.view.View.VISIBLE
            rv.layoutManager   = LinearLayoutManager(this)
            rv.adapter         = HistoryAdapter(history) { item ->
                setResult(RESULT_PLAY, Intent().apply {
                    putExtra(EXTRA_URI, item.first)
                    putExtra(EXTRA_NAME, item.second)
                })
                finish()
            }
        }
    }

    private fun loadHistory(): List<Pair<String, String>> {
        val raw = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(HIST, "") ?: ""
        return raw.split("\n")
            .filter { it.contains("|||") }
            .map { val p = it.split("|||", limit = 2); Pair(p[0], if (p.size > 1) p[1] else "Unknown") }
    }
}

class HistoryAdapter(
    private val items: List<Pair<String, String>>,
    private val onClick: (Pair<String, String>) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvHistoryName)
        val meta: TextView = v.findViewById(R.id.tvHistoryMeta)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_history, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        h.name.text = items[i].second
        h.meta.text = "Tap to play"
        h.itemView.setOnClickListener { onClick(items[i]) }
    }
}
