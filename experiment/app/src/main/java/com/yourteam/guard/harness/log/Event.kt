package com.yourteam.guard.harness.log

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** type is one of [EventType]. payload is an optional JSON string with structured detail. */
@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val type: String,
    val tag: String,
    val message: String,
    val payload: String? = null,
)

object EventType {
    const val SERVICE = "service"
    const val FOREGROUND = "foreground"
    const val CAPTURE = "capture"
    const val OVERLAY = "overlay"
    const val CALL = "call"
    const val VOIP = "voip"
    const val SMS = "sms"
    const val NOTIF = "notification"
    const val RULE = "rule"
    const val TTS = "tts"
    const val DIAG = "diag"
    val ALL = listOf(SERVICE, FOREGROUND, CAPTURE, OVERLAY, CALL, VOIP, SMS, NOTIF, RULE, TTS, DIAG)
}

@Dao
interface EventDao {
    @Insert suspend fun insert(e: Event)

    @Query("SELECT * FROM events ORDER BY id DESC LIMIT :limit")
    fun observeNewest(limit: Int): Flow<List<Event>>

    @Query("SELECT * FROM events ORDER BY id ASC")
    suspend fun all(): List<Event>

    @Query("DELETE FROM events WHERE id <= (SELECT MAX(id) FROM events) - :keep")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM events")
    suspend fun clear()
}

@Database(entities = [Event::class], version = 1, exportSchema = true)
abstract class EventDatabase : RoomDatabase() {
    abstract fun dao(): EventDao
}
