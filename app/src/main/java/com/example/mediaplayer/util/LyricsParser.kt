package com.example.mediaplayer.util

data class LyricLine(val timeMs: Long, val text: String)

object LyricsParser {
    private val TS = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\]""")
    fun parse(content: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        content.lines().forEach { raw ->
            val matches = TS.findAll(raw)
            val text = TS.replace(raw, "").trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val ms  = m.groupValues[3].let { s ->
                    val v = s.toLongOrNull() ?: 0L; if (s.length == 2) v * 10 else v
                }
                lines.add(LyricLine(min * 60_000 + sec * 1_000 + ms, text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }
    fun currentIndex(lines: List<LyricLine>, posMs: Long): Int {
        if (lines.isEmpty()) return -1
        var idx = 0
        for (i in lines.indices) { if (lines[i].timeMs <= posMs) idx = i else break }
        return idx
    }
}
