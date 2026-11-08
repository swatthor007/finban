package com.yourteam.guard.harness.a11y

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.yourteam.guard.harness.R
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType

class CaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { CaptureBus.request("notification") }
}

/** Quick Settings tile. The shade is still open when tapped; we wait so the app window is what we read. */
class CaptureTileService : TileService() {
    override fun onClick() {
        EventLog.log(EventType.CAPTURE, "tile", "QS tile tapped (capturing in 1.5s, pull the shade up)")
        Handler(Looper.getMainLooper()).postDelayed({ CaptureBus.request("tile") }, 1500)
    }
}

object CaptureNotification {
    private const val CH = "capture"
    fun post(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Capture", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, CaptureReceiver::class.java).setPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_launcher).setContentTitle("Guard Harness running")
            .setContentText("Tap \"Capture\" to read the screen").setOngoing(true)
            .addAction(0, "Capture", pi).build()
        try { nm.notify(1, n) } catch (t: Throwable) { EventLog.log(EventType.SERVICE, "notif", "notify failed: $t") }
    }
    fun cancel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(1)
}
