package com.yourteam.guard.harness.a11y

import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType
import com.yourteam.guard.harness.rules.Prefs
import org.json.JSONObject

/** Tracks the foreground app from TYPE_WINDOW_STATE_CHANGED. Observe only. */
object ForegroundTracker {
    @Volatile var pkg: String? = null; private set
    @Volatile var cls: String? = null; private set
    @Volatile var sinceMs: Long = 0; private set

    /** Listeners (the trigger rule, step 8) get (pkg, cls, isWatched) on each real app change. */
    val listeners = mutableListOf<(String, String, Boolean) -> Unit>()

    private val alwaysIgnored = setOf("com.android.systemui", "android")

    private fun ignored(ctx: Context, p: String): Boolean {
        if (p == ctx.packageName || p in alwaysIgnored) return true
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        return imm?.enabledInputMethodList?.any { it.packageName == p } == true
    }

    fun onWindowState(ctx: Context, p: String?, c: String?) {
        if (p == null) return
        // Our own UI really is the foreground app: record it (so returning to another app counts as a change) but never fire.
        if (p == ctx.packageName) { if (pkg != p) { pkg = p; cls = c; sinceMs = System.currentTimeMillis() }; return }
        if (ignored(ctx, p)) return
        val cn = c ?: "?"
        val newSeen = Prefs.markSeen(p)
        val changed = p != pkg
        pkg = p; cls = cn; sinceMs = System.currentTimeMillis()
        val watched = Prefs.isWatched(p)
        if (changed || newSeen) {
            EventLog.log(EventType.FOREGROUND, if (watched) "watched" else "app",
                "$p / $cn" + (if (watched) " [WATCHLISTED]" else "") + (if (newSeen) " (first time seen)" else ""),
                JSONObject().put("pkg", p).put("class", cn).put("watched", watched).put("firstSeen", newSeen))
        }
        if (changed) listeners.toList().forEach { it(p, cn, watched) }
    }
}
