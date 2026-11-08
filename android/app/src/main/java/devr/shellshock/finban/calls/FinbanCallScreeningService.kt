package devr.shellshock.finban.calls

import android.telecom.Call
import android.telecom.CallScreeningService
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType

/** Records the number in memory and ALWAYS lets the call through unchanged. Never blocks, rejects or silences. */
class FinbanCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart
        CallStateTracker.lastNumber = number
        CallStateTracker.lastNumberKnown = Contacts.isKnown(this, number)
        CallStateTracker.lastScreenedAtMs = System.currentTimeMillis()
        EventLog.log(EventType.CALL, "screen", "incoming=${details.callDirection == Call.Details.DIRECTION_INCOMING}")
        respondToCall(details, CallResponse.Builder().build())
    }
}
