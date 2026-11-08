package devr.shellshock.finban.log

import android.util.Log

object EventType {
    const val SERVICE = "service"; const val FOREGROUND = "foreground"; const val CAPTURE = "capture"
    const val OVERLAY = "overlay"; const val SMS = "sms"; const val NOTIF = "notif"
    const val CALL = "call"; const val VOIP = "voip"; const val TTS = "tts"
}

/**
 * Logcat-only replacement for the harness's Room event log.
 * PRIVACY: never pass message text, sender, numbers or links in [msg]; metadata only (counts, package names, states).
 */
object EventLog {
    fun log(type: String, tag: String, msg: String, @Suppress("UNUSED_PARAMETER") extra: Any? = null) {
        Log.d("Finban/$type", "$tag: $msg")
    }
}
