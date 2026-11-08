package devr.shellshock.finban.a11y

import android.content.Context
import android.view.inputmethod.InputMethodManager
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType

/** Tracks the foreground app from TYPE_WINDOW_STATE_CHANGED. Observe only. */
object ForegroundTracker {
    @Volatile var pkg: String? = null; private set
    @Volatile var cls: String? = null; private set
    @Volatile var sinceMs: Long = 0; private set

    /** Listeners (the trigger rule, step 8) get (pkg, cls, isWatched) on each real app change. */
    val listeners = mutableListOf<(String, String, Boolean) -> Unit>()

    /** TODO(hackathon): harness defaults, unverified on the demo phones. */
    val WATCHLIST = setOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "com.whatsapp", "com.anydesk.anydeskandroid", "com.teamviewer.quicksupport.market")

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
        val changed = p != pkg
        pkg = p; cls = cn; sinceMs = System.currentTimeMillis()
        val watched = p in WATCHLIST
        if (changed) EventLog.log(EventType.FOREGROUND, "app", "$p" + (if (watched) " [WATCHLISTED]" else ""))
        if (changed) listeners.toList().forEach { it(p, cn, watched) }
    }
}
