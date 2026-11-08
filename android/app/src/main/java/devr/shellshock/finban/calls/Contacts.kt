package devr.shellshock.finban.calls

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import devr.shellshock.finban.diagnostics.Status

object Contacts {
    /** @return true/false, or null if READ_CONTACTS is missing (lookup impossible). */
    fun isKnown(ctx: Context, number: String?): Boolean? {
        if (number.isNullOrBlank()) return false
        if (!Status.granted(ctx, android.Manifest.permission.READ_CONTACTS)) return null
        return try {
            ctx.contentResolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
        } catch (_: Throwable) { null }
    }

    /** True if a contact has exactly this display name. Notifications show a saved contact's name, but also a business sender's name or a group's name. null = no permission. */
    fun isKnownName(ctx: Context, name: String?): Boolean? {
        if (name.isNullOrBlank()) return false
        if (!Status.granted(ctx, android.Manifest.permission.READ_CONTACTS)) return null
        return try {
            ctx.contentResolver.query(android.provider.ContactsContract.Contacts.CONTENT_URI, arrayOf(android.provider.ContactsContract.Contacts._ID),
                "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} = ?", arrayOf(name.trim()), null)?.use { it.count > 0 } ?: false
        } catch (_: Throwable) { null }
    }
}
