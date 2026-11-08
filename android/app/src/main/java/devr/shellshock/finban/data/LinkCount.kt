package devr.shellshock.finban.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore

/** Live number of links this phone is part of (null until the first answer). Elder: links where elder_uid is me; caretaker: caretaker_uid. */
@Composable
fun rememberLinkCount(role: Role): Int? {
    var n by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(role) {
        val uid = Firebase.auth.currentUser?.uid
        val field = if (role == Role.ELDER) "elder_uid" else "caretaker_uid"
        val reg = uid?.let { Firebase.firestore.collection("links").whereEqualTo(field, it).addSnapshotListener { s, _ -> if (s != null) n = s.size() } }
        onDispose { reg?.remove() }
    }
    return n
}
