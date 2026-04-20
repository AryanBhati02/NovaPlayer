package com.example.mediaplayer.db.dao
import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.mediaplayer.db.entity.*

@Dao interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC") fun getAll(): LiveData<List<Playlist>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(p: Playlist): Long
    @Delete suspend fun delete(p: Playlist)
    @Query("SELECT * FROM playlist_items WHERE playlistId=:pid ORDER BY position ASC") fun getItems(pid: Long): LiveData<List<PlaylistItem>>
    @Query("SELECT * FROM playlist_items WHERE playlistId=:pid ORDER BY position ASC") suspend fun getItemsSync(pid: Long): List<PlaylistItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertItem(i: PlaylistItem)
    @Delete suspend fun deleteItem(i: PlaylistItem)
    @Query("DELETE FROM playlist_items WHERE playlistId = :pid") suspend fun deleteByPlaylistId(pid: Long)

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :pid")
    fun getItemCount(pid: Long): androidx.lifecycle.LiveData<Int>
}

data class TrackCount(val trackName: String, val cnt: Int)

@Dao interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE uriString=:uri ORDER BY positionMs ASC") fun forTrack(uri: String): LiveData<List<Bookmark>>
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC") fun getAll(): LiveData<List<Bookmark>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(b: Bookmark)
    @Delete suspend fun delete(b: Bookmark)
}

data class TrackCount2(val trackName: String, val cnt: Int)

@Dao interface StatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(s: PlayStat)
    @Query("SELECT * FROM play_stats ORDER BY playedAt DESC") fun getAll(): LiveData<List<PlayStat>>
    @Query("SELECT SUM(durationMs) FROM play_stats WHERE playedAt>:since") fun totalMs(since: Long): LiveData<Long?>
    @Query("SELECT trackName, COUNT(*) as cnt FROM play_stats GROUP BY trackName ORDER BY cnt DESC LIMIT 5") fun topTracks(): LiveData<List<TrackCount>>
    @Query("DELETE FROM play_stats") suspend fun clearAll()
}
