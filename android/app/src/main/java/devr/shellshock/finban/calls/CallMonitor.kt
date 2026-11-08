package devr.shellshock.finban.calls

import android.app.role.RoleManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import devr.shellshock.finban.a11y.ForegroundTracker
import devr.shellshock.finban.diagnostics.Status
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import org.json.JSONObject

/**
 * Cellular call state + VoIP (audio mode) detection. Lives as long as the accessibility service.
 *
 * Known VoIP-mode false positives to watch for (see README): voice notes / voice-message recording,
 * mode stuck at IN_COMMUNICATION after a call, other apps that open a communication audio session
 * (video calls, recorders, some games), Bluetooth SCO.
 */
class CallMonitor(private val ctx: Context) {
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var telephonyRegistered = false
    private var modeRegistered = false

    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var poll: Runnable? = null
    private var telCb: Any? = null

    fun start() { ensureTelephony(); startAudioMode() }

    /** Safe to call repeatedly (e.g. after the user grants READ_PHONE_STATE). */
    fun ensureTelephony() {
        if (telephonyRegistered) return
        if (!Status.granted(ctx, android.Manifest.permission.READ_PHONE_STATE)) {
            EventLog.log(EventType.CALL, "perm", "READ_PHONE_STATE not granted: cellular call state unavailable"); return
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onCellular(state)
                }
                telCb = cb
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(ctx), cb)
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("legacy") override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCellular(state)
                }
                legacyListener = l
                @Suppress("DEPRECATION") tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            }
            telephonyRegistered = true
            EventLog.log(EventType.CALL, "register", "call-state listener registered (api ${Build.VERSION.SDK_INT}), screeningRoleHeld=${roleHeld()}")
        } catch (t: Throwable) { EventLog.log(EventType.CALL, "register-error", "$t") }
    }

    private fun roleHeld() = ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

    private fun onCellular(state: Int) {
        val prev = CallStateTracker.cellularState
        if (state == prev) return
        CallStateTracker.cellularState = state
        if (state == TelephonyManager.CALL_STATE_IDLE && prev != TelephonyManager.CALL_STATE_IDLE) CallStateTracker.lastCellularEndMs = System.currentTimeMillis()
        if (state == TelephonyManager.CALL_STATE_IDLE && prev == TelephonyManager.CALL_STATE_RINGING) EventLog.log(EventType.CALL, "missed", "ringing -> idle (missed/rejected)")
        EventLog.log(EventType.CALL, "state", "${CallStateTracker.stateName(prev)} -> ${CallStateTracker.stateName(state)}",
            JSONObject().put("screeningRoleHeld", roleHeld()).put("lastScreenedNumber", CallStateTracker.lastNumber)
                .put("lastScreenedAgoMs", if (CallStateTracker.lastScreenedAtMs == 0L) JSONObject.NULL else System.currentTimeMillis() - CallStateTracker.lastScreenedAtMs))
    }

    private fun startAudioMode() {
        if (modeRegistered) return
        modeRegistered = true
        CallStateTracker.audioMode = am.mode
        EventLog.log(EventType.VOIP, "initial", "audio mode at start = ${CallStateTracker.modeName(am.mode)}")
        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnModeChangedListener { m -> onMode(m) }
            modeListener = l
            am.addOnModeChangedListener(ContextCompat.getMainExecutor(ctx), l)
        } else {
            val r = object : Runnable { override fun run() { onMode(am.mode); main.postDelayed(this, 1000) } }
            poll = r; main.postDelayed(r, 1000)
        }
    }

    private fun onMode(m: Int) {
        val prev = CallStateTracker.audioMode
        if (m == prev) return
        CallStateTracker.audioMode = m
        if (prev == AudioManager.MODE_IN_COMMUNICATION) CallStateTracker.lastVoipEndMs = System.currentTimeMillis()
        val flag = if (m == AudioManager.MODE_IN_COMMUNICATION) " <-- VoIP candidate (check it is really a call, not a voice note)" else ""
        EventLog.log(EventType.VOIP, "mode", "${CallStateTracker.modeName(prev)} -> ${CallStateTracker.modeName(m)} fg=${ForegroundTracker.pkg}$flag",
            JSONObject().put("from", prev).put("to", m).put("fg", ForegroundTracker.pkg).put("cellular", CallStateTracker.stateName(CallStateTracker.cellularState)))
    }

    fun stop() {
        try {
            if (Build.VERSION.SDK_INT >= 31) { (telCb as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }; modeListener?.let { am.removeOnModeChangedListener(it) } }
            else { @Suppress("DEPRECATION") legacyListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }; poll?.let { main.removeCallbacks(it) } }
        } catch (_: Throwable) {}
        telephonyRegistered = false; modeRegistered = false
    }
}
