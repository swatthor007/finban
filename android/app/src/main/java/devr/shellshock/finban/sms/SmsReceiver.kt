package devr.shellshock.finban.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType

/** Primary SMS source: SMS_RECEIVED broadcast (RECEIVE_SMS, no READ_SMS). Phase C wires this to /analyze. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        msgs.groupBy { it.originatingAddress }.forEach { (addr, parts) ->
            val body = parts.joinToString("") { it.messageBody ?: "" }
            EventLog.log(EventType.SMS, "receiver", "sender=${SenderParser.parse(addr).kind} textLen=${body.length}")
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { SmsCheck.check(context.applicationContext, addr, body, fromNotification = false) } finally { pending.finish() }
            }
        }
    }
}
