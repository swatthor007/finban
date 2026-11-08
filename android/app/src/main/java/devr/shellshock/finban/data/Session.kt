package devr.shellshock.finban.data

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore
import com.google.firebase.messaging.FirebaseMessaging
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import kotlinx.coroutines.tasks.await

enum class Role(val wire: String) {
    ELDER("elder"), CARETAKER("caretaker");
    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } }
}

/** Anonymous Firebase identity + the role chosen on first launch + the users/{uid} doc. No secrets here. */
object Session {
    private const val PREFS = "finban"
    private lateinit var app: Context
    private val sp get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    internal val prefs get() = sp

    private val langState = androidx.compose.runtime.mutableStateOf("ta")

    fun init(ctx: Context) {
        app = ctx.applicationContext
        langState.value = sp.getString("lang", "ta") ?: "ta"
    }

    var role: Role?
        get() = Role.fromWire(sp.getString("role", null))
        private set(v) { sp.edit().putString("role", v?.wire).apply() }

    /** Small on-device settings. Nothing here leaves the phone. */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) { sp.edit().putBoolean("onboarded", v).apply() }
    /** Compose state, so every open screen and sheet changes language at once. */
    var lang: String
        get() = langState.value
        set(v) {
            sp.edit().putString("lang", v).apply()
            langState.value = v
            // The server picks the caretaker's push language from this. Firestore retries it when back online.
            if (role != null) Firebase.auth.currentUser?.uid?.let { uid ->
                Firebase.firestore.collection("users").document(uid).set(mapOf("lang" to v), com.google.firebase.firestore.SetOptions.merge())
            }
        }
    var voice: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) { sp.edit().putBoolean("voice", v).apply() }
    /** Bubble check may send a picture of the screen (only when text cannot be read or a big picture is on screen). */
    var pictureCheck: Boolean
        get() = sp.getBoolean("picture_check", true)
        set(v) { sp.edit().putBoolean("picture_check", v).apply() }
    var bubbleOn: Boolean
        get() = sp.getBoolean("bubble", true)
        set(v) { sp.edit().putBoolean("bubble", v).apply() }
    /** Payment-app pause: how long after a call ends it still counts (minutes), and the quiet time after one pause sheet. */
    var callWindowMin: Int
        get() = sp.getInt("call_window_min", 10)
        set(v) { sp.edit().putInt("call_window_min", v.coerceIn(1, 60)).apply() }
    var pauseCooldownMin: Int
        get() = sp.getInt("pause_cooldown_min", 3)
        set(v) { sp.edit().putInt("pause_cooldown_min", v.coerceIn(0, 30)).apply() }
    /** Debug builds only: extra package names treated as a payment app (so the pause sheet can be tried on the emulator). */
    var debugPaymentApps: Set<String>
        get() = sp.getStringSet("debug_payment_apps", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("debug_payment_apps", v).apply() }
    /** Trusted person's name and number, kept on this phone only (powers the Call button). */
    var trustedName: String
        get() = sp.getString("trusted_name", "") ?: ""
        set(v) { sp.edit().putString("trusted_name", v).apply() }
    var trustedPhone: String
        get() = sp.getString("trusted_phone", "") ?: ""
        set(v) { sp.edit().putString("trusted_phone", v).apply() }

    /** Caretaker side: names of the people linked on this phone (the server returns the name once, at link time). */
    var linkedElders: List<String>
        get() = runCatching { org.json.JSONArray(sp.getString("linked_elders", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        set(v) { sp.edit().putString("linked_elders", org.json.JSONArray(v.distinct()).toString()).apply() }

    val displayName: String get() = sp.getString("name", "") ?: ""

    /** Signs in anonymously if needed and returns the uid. */
    suspend fun ensureSignedIn(): String {
        val auth = Firebase.auth
        return (auth.currentUser ?: auth.signInAnonymously().await().user!!).uid
    }

    /** Fresh-enough ID token for the Bearer header (Firebase caches and refreshes it). */
    suspend fun idToken(forceRefresh: Boolean = false): String {
        ensureSignedIn()
        return Firebase.auth.currentUser!!.getIdToken(forceRefresh).await().token!!
    }

    /** First-launch role choice: remembers it, writes users/{uid} and registers this phone's FCM token. */
    suspend fun chooseRole(role: Role, name: String) {
        val uid = ensureSignedIn()
        sp.edit().putString("name", name).apply()
        this.role = role
        Firebase.firestore.collection("users").document(uid).set(
            mapOf(
                "role" to role.wire,
                "display_name" to name,
                "lang" to lang,
                "created_at" to FieldValue.serverTimestamp(),
            ),
            com.google.firebase.firestore.SetOptions.merge(),
        ).await()
        // The push token is best-effort here: a phone without Google Play services, or a blocked FCM, must not stop the role being
        // saved. MainActivity re-registers the token on every launch (refreshToken), so it is picked up later.
        runCatching { saveToken(FirebaseMessaging.getInstance().token.await()) }
            .onFailure { EventLog.log(EventType.SERVICE, "fcm", "token not saved: ${it.javaClass.simpleName}") }
    }

    /** Adds the token to users/{uid}.fcm_tokens (arrayUnion, so repeats are harmless). Only after a role is chosen. */
    suspend fun saveToken(token: String) {
        if (role == null) return
        val uid = ensureSignedIn()
        Firebase.firestore.collection("users").document(uid)
            .set(mapOf("fcm_tokens" to FieldValue.arrayUnion(token)), com.google.firebase.firestore.SetOptions.merge()).await()
        EventLog.log(EventType.SERVICE, "fcm", "token saved")
    }

    /** Re-registers the token on every launch (token may have rotated while the app was not running). */
    suspend fun refreshToken() {
        if (role == null) return
        saveToken(FirebaseMessaging.getInstance().token.await())
    }
}
