package devr.shellshock.finban.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import devr.shellshock.finban.diagnostics.Status
import devr.shellshock.finban.ui.onboarding.accessibilityOn
import devr.shellshock.finban.ui.theme.Finban

/** Runtime permissions the SMS check needs. Without RECEIVE_SMS Android never delivers incoming texts to the app. */
val SMS_PERMISSIONS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)

fun smsPermissionsGranted(ctx: Context) = SMS_PERMISSIONS.all { Status.granted(ctx, it) }
fun notificationAccessOn(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

/** Everything Finban needs switched on to watch texts and screens. */
fun protectionComplete(ctx: Context) = accessibilityOn(ctx) && smsPermissionsGranted(ctx)

/** Bumps whenever the screen comes back to the front (e.g. after the person returned from a Settings page), so ticks refresh. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

fun openNotificationAccess(ctx: Context) =
    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** One setup line: tick or cross, what it is for, and a button while it is missing. Never colour alone. */
@Composable
fun PermRow(label: String, ok: Boolean, action: String, onAction: () -> Unit) {
    val tone = if (ok) Finban.colors.none else Finban.colors.high
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (ok) AppIcons.Check else AppIcons.Close, androidx.compose.ui.res.stringResource(if (ok) devr.shellshock.finban.R.string.done else devr.shellshock.finban.R.string.not_done), tint = tone.icon, modifier = Modifier.size(24.dp))
        Text(label, style = Finban.type.body, color = Finban.colors.ink, modifier = Modifier.weight(1f))
        if (!ok) Box(Modifier.width(120.dp).size(width = 120.dp, height = 56.dp)) { SecondaryButton(action, onAction) }
    }
}
