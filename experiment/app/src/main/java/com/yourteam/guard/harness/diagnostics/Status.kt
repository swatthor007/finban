package com.yourteam.guard.harness.diagnostics

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.yourteam.guard.harness.a11y.GuardAccessibilityService

object Status {
    fun a11yEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, GuardAccessibilityService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun screeningRoleHeld(ctx: Context) =
        ctx.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)

    fun notifListenerEnabled(ctx: Context) =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun a11yConnected() = GuardAccessibilityService.instance != null

    fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    val runtimePerms = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.POST_NOTIFICATIONS,
    )
}
