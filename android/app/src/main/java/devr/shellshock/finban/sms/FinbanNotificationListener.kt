package devr.shellshock.finban.sms

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType

/** Secondary SMS source and the WhatsApp source: reads SMS-app and WhatsApp notifications. Observe only. Phase C wires this to /analyze. */
class FinbanNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onListenerConnected() { EventLog.log(EventType.NOTIF, "connected", "notification listener connected") }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val whatsapp = sbn.packageName in WHATSAPP_APPS
        if (sbn.packageName !in SMS_APPS && !whatsapp) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return // empty group summaries
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        EventLog.log(EventType.NOTIF, if (whatsapp) "whatsapp" else "sms-app", "pkg=${sbn.packageName} sender=${SenderParser.parse(title).kind} textLen=${body?.length ?: 0}")
        // WhatsApp also posts "2 new messages" summaries and call notices; those say nothing the server could judge, so skip the obvious ones.
        if (whatsapp && body != null && Regex("^\\d+ (new )?messages?( from .*)?$", RegexOption.IGNORE_CASE).matches(body.trim())) return
        if (body != null) scope.launch { SmsCheck.check(applicationContext, title, body, fromNotification = true, source = if (whatsapp) "whatsapp" else "sms") }
    }

    companion object {
        // TODO(hackathon): harness defaults, unverified on the demo phones
        val WHATSAPP_APPS = setOf("com.whatsapp", "com.whatsapp.w4b")
        val SMS_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.oneplus.mms", "com.miui.mms")
    }
}
