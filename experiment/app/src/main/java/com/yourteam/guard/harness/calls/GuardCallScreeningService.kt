package com.yourteam.guard.harness.calls

import android.telecom.Call
import android.telecom.CallScreeningService
import android.os.Build
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType
import org.json.JSONObject

/** Logs the number and ALWAYS lets the call through unchanged. Never blocks, rejects or silences. */
class GuardCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        val verification = if (Build.VERSION.SDK_INT >= 30) details.callerNumberVerificationStatus else -1
        val known = Contacts.isKnown(this, number)
        CallStateTracker.lastNumber = number
        CallStateTracker.lastNumberKnown = known
        CallStateTracker.lastScreenedAtMs = System.currentTimeMillis()
        EventLog.log(EventType.CALL, "screen",
            "${if (incoming) "incoming" else "outgoing"} number=$number known=${known ?: "unknown(no READ_CONTACTS)"} verification=$verification",
            JSONObject().put("number", number).put("incoming", incoming).put("known", known).put("verification", verification))
        respondToCall(details, CallResponse.Builder().build()) // default response = allow
    }
}
