package com.yourteam.guard.harness.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType
import org.json.JSONObject

/** Method A: SMS_RECEIVED broadcast (RECEIVE_SMS). Logs the raw originatingAddress and body. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        msgs.groupBy { it.originatingAddress }.forEach { (addr, parts) ->
            val body = parts.joinToString("") { it.messageBody ?: "" }
            val parsed = SenderParser.parse(addr)
            EventLog.log(EventType.SMS, "receiver", "from=\"$addr\" -> $parsed | body=\"${body.take(120)}\"",
                JSONObject().put("method", "SMS_RECEIVED").put("originatingAddress", addr).put("body", body).put("parsed", parsed.toString()))
        }
    }
}
