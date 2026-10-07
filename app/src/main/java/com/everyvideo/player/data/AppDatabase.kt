package com.everyvideo.player.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 동영상의 특정 시점 즐겨찾기. videoKey 는 자격 증명을 뺀 동영상 주소. */
@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoKey: String,
    val videoUri: String,
    val videoTitle: String,
    val positionMs: Long,
    val label: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "recents")
data class Recent(
    @PrimaryKey val videoKey: String,
    val videoUri: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayed: Long = System.currentTimeMillis()
)

@Entity(tableName = "servers")
data class RemoteServer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // "smb" | "ftp"
    val name: String,
    val host: String,
    val port: Int,
    val share: String,
    val path: String,
    val user: String,
    val password: String,
    val domain: String
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE videoKey = :key ORDER BY positionMs")
    fun observeFor(key: String): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY videoTitle, positionMs")
    fun observeAll(): Flow<List<Bookmark>>

    @Insert
    suspend fun insert(b: Bookmark): Long

    @Delete
    suspend fun delete(b: Bookmark)

    @Update
    suspend fun update(b: Bookmark)
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recents ORDER BY lastPlayed DESC LIMIT 100")
    fun observe(): Flow<List<Recent>>

    @Query("SELECT * FROM recents WHERE videoKey = :key")
    suspend fun get(key: String): Recent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: Recent)

    @Query("DELETE FROM recents WHERE videoKey = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM recents")
    suspend fun clear()
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY name")
    fun observe(): Flow<List<RemoteServer>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun get(id: Long): RemoteServer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: RemoteServer): Long

    @Delete
    suspend fun delete(s: RemoteServer)
}

@Database(entities = [Bookmark::class, Recent::class, RemoteServer::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao
    abstract fun recents(): RecentDao
    abstract fun servers(): ServerDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "everyvideo.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
