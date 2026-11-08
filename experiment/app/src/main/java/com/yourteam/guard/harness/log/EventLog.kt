package com.yourteam.guard.harness.log

import android.content.Context
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide event log. Backed by Room (survives process death between a call and the
 * moment the tester opens the app) and mirrored to Logcat under tag "GuardH".
 */
object EventLog {
    private const val KEEP = 5000
    private lateinit var dao: EventDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inserts = 0

    fun init(context: Context) {
        if (::dao.isInitialized) return
        dao = Room.databaseBuilder(context.applicationContext, EventDatabase::class.java, "events.db")
            .fallbackToDestructiveMigration(true)
            .build().dao()
    }

    fun log(type: String, tag: String, message: String, payload: JSONObject? = null) {
        Log.i("GuardH", "[$type/$tag] $message" + (payload?.let { " $it" } ?: ""))
        if (!::dao.isInitialized) return
        val e = Event(ts = System.currentTimeMillis(), type = type, tag = tag, message = message, payload = payload?.toString())
        scope.launch {
            dao.insert(e)
            if (++inserts % 100 == 0) dao.prune(KEEP)
        }
    }

    fun observe(limit: Int = 500): Flow<List<Event>> = dao.observeNewest(limit)

    fun clear() { scope.launch { dao.clear() } }

    suspend fun exportJson(): String {
        val arr = JSONArray()
        dao.all().forEach {
            arr.put(JSONObject().put("ts", it.ts).put("type", it.type).put("tag", it.tag)
                .put("message", it.message).put("payload", it.payload?.let(::JSONObject)))
        }
        return JSONObject().put("exportedAt", System.currentTimeMillis())
            .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            .put("android", android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")")
            .put("events", arr).toString(2)
    }
}
