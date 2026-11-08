package com.yourteam.guard.harness.sms

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType
import com.yourteam.guard.harness.rules.Prefs
import org.json.JSONObject

/**
 * Method B: read SMS-app notifications. Observe only; never dismisses or replies to anything.
 * Note: Android 15+ may redact sensitive (e.g. OTP) notification content for listeners.
 */
class GuardNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { EventLog.log(EventType.NOTIF, "connected", "notification listener connected") }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in Prefs.smsPackages.value) return
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val summary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val parsed = SenderParser.parse(title)
        val redacted = text.isNullOrBlank() && big.isNullOrBlank()
        EventLog.log(EventType.NOTIF, if (summary) "sms-app-summary" else "sms-app", "pkg=$pkg title=\"$title\" -> $parsed | text=\"${(big ?: text)?.take(120)}\"" + if (redacted) " [EMPTY/REDACTED TEXT]" else "",
            JSONObject().put("method", "NotificationListener").put("pkg", pkg).put("title", title).put("text", text).put("bigText", big).put("parsed", parsed.toString()))
    }
}
