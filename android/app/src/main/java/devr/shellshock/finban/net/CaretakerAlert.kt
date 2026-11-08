package devr.shellshock.finban.net

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore
import com.google.firebase.Firebase
import devr.shellshock.finban.data.Session
import kotlinx.coroutines.tasks.await

/** What the sheet's "Tell my trusted person" button is doing. */
sealed interface AlertState {
    data object Idle : AlertState
    data object Sending : AlertState
    data class Sent(val pushed: Int) : AlertState
    data object NoCaretaker : AlertState
    data object Failed : AlertState
}

/**
 * Elder taps the button -> alerts/{id} (who/when/pattern/severity only, never text, numbers or links) -> POST /alert for the push.
 * Trigger: "manual" (the sheet button), "pause_yes" (a Yes in the payment pause sheet) or "already_paid" (Paid screen button); all allowed by the Firestore rules.
 */
object CaretakerAlert {
    suspend fun send(severity: String, patternId: String?, trigger: String = "manual"): AlertState {
        return try {
            val uid = Session.ensureSignedIn()
            val db = Firebase.firestore
            val links = db.collection("links").whereEqualTo("elder_uid", uid).get().await()
            val caretakers = links.documents.mapNotNull { it.getString("caretaker_uid") }.distinct().take(3)
            if (caretakers.isEmpty()) return AlertState.NoCaretaker
            val ref = db.collection("alerts").document()
            ref.set(
                mapOf(
                    "elder_uid" to uid, "caretaker_uids" to caretakers, "pattern_id" to patternId, "severity" to severity,
                    "trigger" to trigger, "created_at" to FieldValue.serverTimestamp(), "status" to "new",
                )
            ).await()
            AlertState.Sent(Api.client.alert(AlertRequest(ref.id)).sent)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            devr.shellshock.finban.log.EventLog.log(devr.shellshock.finban.log.EventType.SERVICE, "alert", "failed: ${e.javaClass.simpleName}")
            AlertState.Failed
        }
    }
}
