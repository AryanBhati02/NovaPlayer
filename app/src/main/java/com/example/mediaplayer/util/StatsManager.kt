package com.example.mediaplayer.util

import android.content.Context

class StatsManager(context: Context) {

    private val prefs = context.getSharedPreferences("nova_stats", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TOTAL_MS      = "total_ms"
        private const val KEY_TODAY_MS      = "today_ms"
        private const val KEY_TODAY_DATE    = "today_date"
        private const val KEY_WEEK_MS       = "week_ms"
        private const val KEY_WEEK_START    = "week_start"
        private const val KEY_TRACK_COUNTS  = "track_counts"
        private const val KEY_STREAK        = "streak_days"
        private const val KEY_LAST_PLAY_DAY = "last_play_day"
    }

    private var sessionStartMs = 0L

    fun onPlaybackStarted() { sessionStartMs = System.currentTimeMillis() }

    fun onPlaybackStopped() {
        if (sessionStartMs == 0L) return
        val elapsed = System.currentTimeMillis() - sessionStartMs
        sessionStartMs = 0L
        if (elapsed < 1000) return   // ignore < 1 second

        val todayKey = todayKey()
        val weekKey  = weekKey()

        prefs.edit().apply {
            putLong(KEY_TOTAL_MS, getTotalMs() + elapsed)
            // Today
            val storedDay = prefs.getString(KEY_TODAY_DATE, "") ?: ""
            putString(KEY_TODAY_DATE, todayKey)
            putLong(KEY_TODAY_MS, if (storedDay == todayKey) getTodayMs() + elapsed else elapsed)
            // Week
            val storedWeek = prefs.getString(KEY_WEEK_START, "") ?: ""
            putString(KEY_WEEK_START, weekKey)
            putLong(KEY_WEEK_MS, if (storedWeek == weekKey) getWeekMs() + elapsed else elapsed)
            // Streak
            updateStreak(this, todayKey)
            apply()
        }
    }

    fun recordTrackPlay(name: String) {
        val counts = getTrackCounts().toMutableMap()
        counts[name] = (counts[name] ?: 0) + 1
        val encoded = counts.entries.joinToString("|") { "${it.key}::${it.value}" }
        prefs.edit().putString(KEY_TRACK_COUNTS, encoded).apply()
    }

    fun getTotalMs(): Long      = prefs.getLong(KEY_TOTAL_MS, 0)
    fun getTodayMs(): Long      = prefs.getLong(KEY_TODAY_MS, 0)
    fun getWeekMs(): Long       = prefs.getLong(KEY_WEEK_MS, 0)
    fun getStreak(): Int        = prefs.getInt(KEY_STREAK, 0)

    fun getMostPlayed(top: Int = 5): List<Pair<String, Int>> =
        getTrackCounts().entries
            .sortedByDescending { it.value }
            .take(top)
            .map { Pair(it.key, it.value) }

    private fun getTrackCounts(): Map<String, Int> {
        val raw = prefs.getString(KEY_TRACK_COUNTS, "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        return raw.split("|").mapNotNull {
            val parts = it.split("::")
            if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: 0) else null
        }.toMap()
    }

    private fun updateStreak(editor: android.content.SharedPreferences.Editor, todayKey: String) {
        val lastDay = prefs.getString(KEY_LAST_PLAY_DAY, "") ?: ""
        val currentStreak = prefs.getInt(KEY_STREAK, 0)
        if (lastDay == todayKey) return
        val streak = if (isYesterday(lastDay, todayKey)) currentStreak + 1 else 1
        editor.putInt(KEY_STREAK, streak).putString(KEY_LAST_PLAY_DAY, todayKey)
    }

    private fun todayKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}_${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    private fun weekKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}_${cal.get(java.util.Calendar.WEEK_OF_YEAR)}"
    }

    private fun isYesterday(prev: String, today: String): Boolean {
        return try {
            val p = prev.split("_"); val t = today.split("_")
            val py = p[0].toInt(); val pd = p[1].toInt()
            val ty = t[0].toInt(); val td = t[1].toInt()
            (ty == py && td == pd + 1) || (ty == py + 1 && pd >= 365 && td == 1)
        } catch (_: Exception) { false }
    }

    fun formatMs(ms: Long): String {
        val hours   = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        return when {
            hours > 0   -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else        -> "< 1m"
        }
    }
}
