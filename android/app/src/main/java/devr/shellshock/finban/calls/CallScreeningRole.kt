package devr.shellshock.finban.calls

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

/** The call-screening role is the only way Android gives an app the caller's number. Finban uses it to learn "saved contact or not"; it always lets the call through. */
object CallScreeningRole {
    fun held(ctx: Context): Boolean = ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    fun available(ctx: Context): Boolean = ctx.getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
    /** Intent for StartActivityForResult; the system shows its own "set as default caller ID app" dialog. */
    fun requestIntent(ctx: Context): Intent = ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
}
