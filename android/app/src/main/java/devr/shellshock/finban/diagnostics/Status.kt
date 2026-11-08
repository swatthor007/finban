package devr.shellshock.finban.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Status {
    fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}
