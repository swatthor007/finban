package com.yourteam.guard.harness.calls

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import com.yourteam.guard.harness.diagnostics.Status

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
}
