package dev.zipshare.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val remoteId: String,
    val profileId: String,
    val localUri: String?,
    val remoteUrl: String,
    val name: String,
    val mime: String,
    val size: Long,
    val deletesAt: String?,
    val ts: Long,
    val pending: Boolean = false,
)

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY ts DESC")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE profileId = :profileId ORDER BY ts DESC")
    fun observeForProfile(profileId: String): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY ts DESC")
    suspend fun all(): List<HistoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Query("DELETE FROM history WHERE remoteId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = true)
abstract class ZipShareDb : RoomDatabase() {
    abstract fun history(): HistoryDao
}
