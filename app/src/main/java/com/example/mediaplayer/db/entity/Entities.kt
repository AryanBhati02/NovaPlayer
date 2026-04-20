package com.example.mediaplayer.db.entity
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class Playlist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "playlist_items")
data class PlaylistItem(@PrimaryKey(autoGenerate = true) val id: Long = 0, val playlistId: Long, val uriString: String, val name: String, val position: Int = 0)

@Entity(tableName = "bookmarks")
data class Bookmark(@PrimaryKey(autoGenerate = true) val id: Long = 0, val uriString: String, val trackName: String, val label: String, val positionMs: Int, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "play_stats")
data class PlayStat(@PrimaryKey(autoGenerate = true) val id: Long = 0, val trackName: String, val playedAt: Long = System.currentTimeMillis(), val durationMs: Long = 0)
