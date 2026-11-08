package devr.shellshock.finban.calls

import android.telephony.TelephonyManager

/** Shared call state read by the trigger rule (step 8). Written by CallMonitor / FinbanCallScreeningService. */
object CallStateTracker {
    @Volatile var cellularState = TelephonyManager.CALL_STATE_IDLE
    @Volatile var lastCellularEndMs = 0L
    @Volatile var audioMode = android.media.AudioManager.MODE_NORMAL
    @Volatile var lastVoipEndMs = 0L

    /** Last number seen by the call-screening service, and whether it was in contacts. null = no screening info. */
    @Volatile var lastNumber: String? = null
    @Volatile var lastNumberKnown: Boolean? = null
    @Volatile var lastScreenedAtMs = 0L

    val offHook get() = cellularState == TelephonyManager.CALL_STATE_OFFHOOK
    val voipActive get() = audioMode == android.media.AudioManager.MODE_IN_COMMUNICATION

    /** Cellular off-hook, VoIP mode active, or either ended within [windowMs]. */
    fun callActiveOrRecent(now: Long, windowMs: Long): Boolean =
        offHook || voipActive ||
            (lastCellularEndMs > 0 && now - lastCellularEndMs <= windowMs) ||
            (lastVoipEndMs > 0 && now - lastVoipEndMs <= windowMs)

    fun describe() = "cellular=${stateName(cellularState)} audioMode=${modeName(audioMode)} number=$lastNumber known=$lastNumberKnown"

    fun stateName(s: Int) = when (s) { 0 -> "IDLE"; 1 -> "RINGING"; 2 -> "OFFHOOK"; else -> "?$s" }
    fun modeName(m: Int) = when (m) { 0 -> "NORMAL"; 1 -> "RINGTONE"; 2 -> "IN_CALL"; 3 -> "IN_COMMUNICATION"; 4 -> "CALL_SCREENING"; 5 -> "COMMUNICATION_REDIRECT"; else -> "?$m" }
}
