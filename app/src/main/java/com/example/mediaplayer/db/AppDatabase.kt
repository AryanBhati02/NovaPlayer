package com.example.mediaplayer.db
import android.content.Context
import androidx.room.*
import com.example.mediaplayer.db.dao.*
import com.example.mediaplayer.db.entity.*

@Database(entities=[Playlist::class,PlaylistItem::class,Bookmark::class,PlayStat::class], version=1, exportSchema=false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun statsDao(): StatsDao
    companion object {
        @Volatile private var I: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = I ?: synchronized(this) {
            I ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "nova_db").build().also { I = it }
        }
    }
}
