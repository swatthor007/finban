package devr.shellshock.finban

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class FinbanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        devr.shellshock.finban.data.Session.init(this)
        createAlertChannel()
    }

    private fun createAlertChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Id must match the server's FCM android.notification.channel_id.
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, devr.shellshock.finban.data.AppLanguage.context(this).getString(R.string.channel_scam_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object { const val ALERT_CHANNEL_ID = "scam_alerts" }
}
