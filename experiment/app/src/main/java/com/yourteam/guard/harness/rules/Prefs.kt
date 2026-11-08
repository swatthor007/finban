package com.yourteam.guard.harness.rules

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug-screen settings. SharedPreferences (not DataStore) on purpose: the service needs
 * synchronous reads on the event path, and this is a throwaway harness.
 */
object Prefs {
    val DEFAULT_WATCHLIST = setOf(
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
        "in.org.npci.upiapp", "com.whatsapp", "com.anydesk.anydeskandroid",
        "com.teamviewer.quicksupport.market",
    )
    private lateinit var sp: SharedPreferences
    private val _watchlist = MutableStateFlow<Set<String>>(emptySet())
    private val _seen = MutableStateFlow<Set<String>>(emptySet())
    val watchlist: StateFlow<Set<String>> = _watchlist
    val seen: StateFlow<Set<String>> = _seen

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("harness", Context.MODE_PRIVATE)
        _watchlist.value = sp.getStringSet("watchlist", null) ?: DEFAULT_WATCHLIST
        _seen.value = sp.getStringSet("seen", null) ?: emptySet()
        _sms.value = sp.getStringSet("smsPkgs", null) ?: DEFAULT_SMS_APPS
    }

    var bubbleEnabled: Boolean
        get() = sp.getBoolean("bubble", true)
        set(v) { sp.edit().putBoolean("bubble", v).apply() }

    val DEFAULT_SMS_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.oneplus.mms", "com.miui.mms")
    private val _sms = MutableStateFlow<Set<String>>(DEFAULT_SMS_APPS)
    val smsPackages: StateFlow<Set<String>> = _sms
    fun setSmsPackages(s: Set<String>) { _sms.value = s; sp.edit().putStringSet("smsPkgs", s).apply() }

    var windowMin: Int
        get() = sp.getInt("windowMin", 10)
        set(v) { sp.edit().putInt("windowMin", v).apply() }
    var cooldownMin: Int
        get() = sp.getInt("cooldownMin", 3)
        set(v) { sp.edit().putInt("cooldownMin", v).apply() }

    fun setWatchlist(s: Set<String>) { _watchlist.value = s; sp.edit().putStringSet("watchlist", s).apply() }
    fun addWatch(p: String) = setWatchlist(_watchlist.value + p.trim())
    fun removeWatch(p: String) = setWatchlist(_watchlist.value - p)
    fun resetWatchlist() = setWatchlist(DEFAULT_WATCHLIST)
    fun isWatched(p: String) = p in _watchlist.value

    /** @return true if this package had not been seen before. */
    fun markSeen(p: String): Boolean {
        if (p in _seen.value) return false
        _seen.value = _seen.value + p; sp.edit().putStringSet("seen", _seen.value).apply(); return true
    }
    fun clearSeen() { _seen.value = emptySet(); sp.edit().remove("seen").apply() }
}
