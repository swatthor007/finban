package devr.shellshock.finban.sms

import devr.shellshock.finban.net.explanation
import devr.shellshock.finban.net.text
import devr.shellshock.finban.net.title
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import devr.shellshock.finban.FinbanApp
import devr.shellshock.finban.R
import devr.shellshock.finban.a11y.Extractors
import devr.shellshock.finban.a11y.FinbanAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import devr.shellshock.finban.calls.Contacts
import devr.shellshock.finban.data.RecentChecks
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import devr.shellshock.finban.net.AnalyzeRequest
import devr.shellshock.finban.net.Api
import devr.shellshock.finban.ui.share.ShareReceiverActivity

/**
 * SMS -> /analyze -> heads-up notification (phase C). Two sources feed it (SMS_RECEIVED and the SMS-app
 * notification), so the same message is de-duplicated here. Prototype policy: every SMS from an unsaved
 * sender is sent as-is (no allowlist, no redaction). Text and sender are never logged.
 */
object SmsCheck {
    private const val DEDUPE_MS = 2 * 60_000L
    private val seen = LinkedHashMap<Int, Long>()

    /**
     * Saved contacts are skipped. In a notification the sender is a display name: a saved contact's name, but also a business sender's name
     * (Messages shows "SBI" for VM-SBIBNK) or a WhatsApp group's name. So a name only counts as saved if a contact really has that name.
     */
    private fun unsaved(ctx: Context, sender: String?, fromNotification: Boolean): Boolean {
        val kind = SenderParser.parse(sender).kind
        if (fromNotification && kind == SenderParser.Parsed.Kind.OTHER) return Contacts.isKnownName(ctx, sender) != true
        if (kind == SenderParser.Parsed.Kind.PLAIN_MOBILE) return Contacts.isKnown(ctx, sender) != true // null = no permission: treat as unsaved
        return true
    }

    private fun firstSighting(sender: String?, body: String): Boolean = synchronized(seen) {
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value > DEDUPE_MS }
        val key = (sender.orEmpty().filter { it.isLetterOrDigit() }.takeLast(10) + "|" + body.trim()).hashCode()
        if (seen.containsKey(key)) false else { seen[key] = now; true }
    }

    /** Suspends until the check and notification are done (so the receiver can finish its goAsync result). */
    suspend fun check(ctx: Context, sender: String?, body: String, fromNotification: Boolean, source: String = "sms") {
        if (body.isBlank() || !unsaved(ctx, sender, fromNotification)) return
        if (!firstSighting(sender, body)) return
        val text = body.take(4000)
        val links = Extractors.find(text).filter { it.kind == Extractors.Kind.URL }.map { it.text }.distinct().take(10)
        val r = try {
            // Nobody is waiting on a background check, so allow longer than the on-screen 3.5 s (a slow tunnel used to drop texts silently).
            Api.client.analyze(AnalyzeRequest(source = if (source == "sms") "sms" else "share", sender = sender, text = text, links = links), timeoutMs = 15_000)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // A timeout is a CancellationException too: it must not be rethrown as if the receiver was cancelled, or the text is dropped without a trace.
            EventLog.log(EventType.SMS, "analyze", "failed: timeout"); return
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { EventLog.log(EventType.SMS, "analyze", "failed: ${e.javaClass.simpleName}"); return }
        RecentChecks.add(r.severity, r.title, source, sender, r.patternId, r.reasons.map { it.text }, r.explanation, body = text)
        EventLog.log(EventType.SMS, "result", "severity=${r.severity} pattern=${r.patternId}")
        if (r.severity == "none") return // nothing to say; never "safe"
        // Popup first: a bottom sheet over the front app (accessibility overlay), since Messages' own notification
        // is already on screen and a second notification gets lost. Notification only if the service is off or a sheet is up.
        val shown = try {
            withContext(Dispatchers.Main) { FinbanAccessibilityService.instance?.screenCheck?.showResult(r, text) == true }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { EventLog.log(EventType.SMS, "popup", "error: $e"); false }
        EventLog.log(EventType.SMS, "popup", "shown=$shown service=${FinbanAccessibilityService.instance != null}")
        if (!shown) notify(ctx, sender, text, r.severity, r.title)
    }

    private fun notify(ctx: Context, sender: String?, text: String, severity: String, title: String) {
        // Tap re-checks the same text in the share sheet (so the link button works). The text rides in this
        // PendingIntent only until it is tapped or dismissed; TODO(hackathon): keep the verdict in memory instead.
        val open = Intent(ctx, ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val id = (sender.orEmpty() + text).hashCode()
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val high = severity == "high"
        val n = NotificationCompat.Builder(ctx, FinbanApp.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(devr.shellshock.finban.data.AppLanguage.context(ctx).getString(if (high) R.string.sms_alert_high else R.string.sms_alert_careful))
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) {}
    }
}
