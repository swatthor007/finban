package devr.shellshock.finban.ui.caretaker

import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import devr.shellshock.finban.R
import devr.shellshock.finban.ui.home.alertLabel
import devr.shellshock.finban.ui.home.timeAgo
import devr.shellshock.finban.net.Api
import devr.shellshock.finban.ui.components.AppIcons
import devr.shellshock.finban.ui.components.AppTextField
import devr.shellshock.finban.ui.components.IconLine
import devr.shellshock.finban.ui.components.ListRow
import devr.shellshock.finban.ui.components.NoteCard
import devr.shellshock.finban.ui.components.PrimaryButton
import devr.shellshock.finban.ui.components.QuietButton
import devr.shellshock.finban.ui.components.ScreenScaffold
import devr.shellshock.finban.ui.components.SectionLabel
import devr.shellshock.finban.ui.components.SecondaryButton
import devr.shellshock.finban.ui.components.SeverityChip
import devr.shellshock.finban.ui.components.StatusBanner
import devr.shellshock.finban.ui.components.TopBar
import devr.shellshock.finban.ui.theme.Finban
import kotlinx.coroutines.launch

/** Not in the artifact except board 5 steps 7-8 (alert detail). Same tokens. Caretakers see who / when / pattern / severity only. */
data class AlertItem(val id: String, val elderUid: String, val patternId: String?, val severity: String, val trigger: String?, val createdMs: Long, val status: String)

@Composable
fun CaretakerHome(onAlert: (String) -> Unit, onDebug: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failedWhy by remember { mutableStateOf("") }
    var linkedName by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf(devr.shellshock.finban.net.ServerConfig.url) }
    var elders by remember { mutableStateOf(devr.shellshock.finban.data.Session.linkedElders) }
    val linkedCount = devr.shellshock.finban.data.rememberLinkCount(devr.shellshock.finban.data.Role.CARETAKER)
    fun claim() {
        busy = true; failed = false
        scope.launch {
            runCatching { Api.client.pairClaim(code) }
                .onSuccess { linkedName = it.elderName; code = ""; devr.shellshock.finban.data.Session.linkedElders = devr.shellshock.finban.data.Session.linkedElders + it.elderName.ifBlank { "Linked" }; elders = devr.shellshock.finban.data.Session.linkedElders }
                .onFailure { e ->
                    failed = true
                    failedWhy = (e as? devr.shellshock.finban.net.ApiException)?.let { a -> a.code ?: "HTTP ${a.status}" }
                        ?: generateSequence<Throwable>(e) { t -> t.cause }.joinToString(" < ") { t -> t.javaClass.simpleName }
                }
            busy = false
        }
    }
    val alerts = remember { mutableStateListOf<AlertItem>() }

    DisposableEffect(Unit) {
        val uid = Firebase.auth.currentUser?.uid
        val reg = uid?.let {
            Firebase.firestore.collection("alerts").whereArrayContains("caretaker_uids", it).addSnapshotListener { snap, _ ->
                val items = snap?.documents?.map { d ->
                    AlertItem(d.id, d.getString("elder_uid") ?: "", d.getString("pattern_id"), d.getString("severity") ?: "careful",
                        d.getString("trigger"), (d.getTimestamp("created_at") ?: Timestamp.now()).toDate().time, d.getString("status") ?: "new")
                }.orEmpty().sortedByDescending { it.createdMs }
                alerts.clear(); alerts.addAll(items)
            }
        }
        onDispose { reg?.remove() }
    }

    ScreenScaffold {
        TopBar(stringResource(R.string.care_home_title), trailing = { devr.shellshock.finban.ui.components.IconTap(AppIcons.Sliders, stringResource(R.string.set_debug), onDebug) })
        linkedName?.let { StatusBanner(it) }
        SectionLabel(stringResource(R.string.care_enter_code))
        AppTextField(
            code, { code = it.filter(Char::isDigit).take(6) }, stringResource(R.string.care_code_label), monospace = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        PrimaryButton(stringResource(R.string.care_link), { claim() }, enabled = code.length == 6 && !busy)
        if (failed) {
            Text(stringResource(R.string.care_claim_failed), style = Finban.type.body, color = Finban.colors.high.ink)
            // Server code (code_invalid, code_expired, ...) = a wrong or old code; anything else = this phone cannot reach the server.
            if (failedWhy.isNotBlank()) Text(failedWhy.take(140), style = Finban.type.caption, color = Finban.colors.muted)
            if (failedWhy !in listOf("code_invalid", "code_expired", "code_used", "rate_limited")) {
                NoteCard(stringResource(R.string.server_fix_title), stringResource(R.string.server_fix_body))
            }
        }
        // Server address: always here, so a phone that cannot reach the server can be pointed at the right one before linking.
        SectionLabel(stringResource(R.string.server_label))
        AppTextField(server, { server = it }, stringResource(R.string.server_label), supporting = stringResource(R.string.server_paste_hint))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { SecondaryButton(stringResource(R.string.trusted_edit_save), { devr.shellshock.finban.net.ServerConfig.url = server.trim(); server = devr.shellshock.finban.net.ServerConfig.url }) }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { SecondaryButton(stringResource(R.string.save_and_link), { devr.shellshock.finban.net.ServerConfig.url = server.trim(); server = devr.shellshock.finban.net.ServerConfig.url; if (code.length == 6 && !busy) claim() }) }
        }
        SectionLabel(stringResource(R.string.care_linked_list))
        if (elders.isEmpty() && (linkedCount ?: 0) == 0) Text(stringResource(R.string.link_none), style = Finban.type.body, color = Finban.colors.muted)
        elders.forEach { ListRow(it) }
        if ((linkedCount ?: 0) > elders.size) Text(stringResource(R.string.link_count_many, linkedCount ?: 0), style = Finban.type.caption, color = Finban.colors.muted)
        SectionLabel(stringResource(R.string.care_alerts))
        if (alerts.isEmpty()) Text(stringResource(R.string.care_alerts_empty), style = Finban.type.body, color = Finban.colors.muted)
        alerts.forEach { a ->
            ListRow(alertLabel(a.patternId, a.trigger),
                timeAgo(a.createdMs), onClick = { onAlert(a.id) }) {
                SeverityChip(a.severity, stringResource(when (a.severity) { "high" -> R.string.severity_high; "careful" -> R.string.severity_careful; else -> R.string.severity_none }))
            }
        }
    }
}

/** Board 5 step 8: severity, when, signs seen, "You only see warning signs", Call button. Opening marks the alert `seen`. */
@Composable
fun AlertDetail(alertId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var item by remember { mutableStateOf<AlertItem?>(null) }
    var elderPhoneHint by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(alertId) {
        val ref = Firebase.firestore.collection("alerts").document(alertId)
        runCatching {
            val d = com.google.android.gms.tasks.Tasks.await(ref.get())
            item = AlertItem(d.id, d.getString("elder_uid") ?: "", d.getString("pattern_id"), d.getString("severity") ?: "careful",
                d.getString("trigger"), (d.getTimestamp("created_at") ?: Timestamp.now()).toDate().time, d.getString("status") ?: "new")
            if (d.getString("status") == "new") ref.update(mapOf("status" to "seen", "ack_by" to Firebase.auth.currentUser?.uid, "ack_at" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
        }
    }
    val a = item
    ScreenScaffold(bottom = {
        if (a != null) PrimaryButton(stringResource(R.string.care_calling), {
            Firebase.firestore.collection("alerts").document(a.id).update(mapOf("status" to "calling", "ack_by" to Firebase.auth.currentUser?.uid, "ack_at" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) // TODO(hackathon): elder's number is not shared with caretakers; opens the dialler only
        })
        QuietButton(stringResource(R.string.close), onBack)
    }) {
        TopBar(stringResource(R.string.care_alerts), onBack)
        if (a != null) {
            SeverityChip(a.severity, stringResource(when (a.severity) { "high" -> R.string.severity_high; "careful" -> R.string.severity_careful; else -> R.string.severity_none }))
            Text(stringResource(R.string.care_alert_title, ""), style = Finban.type.title, color = Finban.colors.ink)
            Text(timeAgo(a.createdMs), style = Finban.type.body, color = Finban.colors.muted)
            SectionLabel(stringResource(R.string.care_alert_signs))
            IconLine(AppIcons.User, alertLabel(a.patternId, a.trigger))
            NoteCard(stringResource(R.string.care_note_title), stringResource(R.string.care_note_body), AppIcons.Info)
        }
    }
}
