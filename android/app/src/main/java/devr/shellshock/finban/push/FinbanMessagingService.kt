package devr.shellshock.finban.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import devr.shellshock.finban.FinbanApp
import devr.shellshock.finban.R
import devr.shellshock.finban.data.Session
import devr.shellshock.finban.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Push data holds only type + alert_id. The visible text comes from the notification payload the server set. */
class FinbanMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { runCatching { Session.saveToken(token) } }
    }

    /** Called for every message while the app is in the foreground (background messages are shown by the system on the same channel). */
    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("alert_id", message.data["alert_id"])
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = NotificationCompat.Builder(this, FinbanApp.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title).setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build()
        try { getSystemService(NotificationManager::class.java).notify(message.messageId.hashCode(), notif) } catch (_: SecurityException) {}
    }
}
