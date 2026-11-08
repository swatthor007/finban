package devr.shellshock.finban.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import devr.shellshock.finban.ui.theme.Radii
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.R
import devr.shellshock.finban.data.AppLanguage
import devr.shellshock.finban.data.RecentChecks
import devr.shellshock.finban.data.Session
import devr.shellshock.finban.net.AlertState
import devr.shellshock.finban.net.CaretakerAlert
import devr.shellshock.finban.ui.components.IconLine
import devr.shellshock.finban.ui.components.NoteCard
import devr.shellshock.finban.ui.components.ScreenScaffold
import devr.shellshock.finban.ui.components.SecondaryButton
import devr.shellshock.finban.ui.components.SectionLabel
import devr.shellshock.finban.ui.components.SeverityChip
import devr.shellshock.finban.ui.components.SeverityIcons
import devr.shellshock.finban.ui.components.TopBar
import devr.shellshock.finban.ui.theme.Finban
import kotlinx.coroutines.launch
import java.util.Date

/** Full logged information for one risky check (opened from a compact Home row). */
@Composable
fun CheckDetailScreen(id: Long, onBack: () -> Unit) {
    val item = remember(id) { RecentChecks.all().firstOrNull { it.at == id } }
    ScreenScaffold {
        TopBar(stringResource(R.string.detail_title), onBack)
        if (item == null) { Text(stringResource(R.string.detail_gone), style = Finban.type.body, color = Finban.colors.muted); return@ScreenScaffold }
        SeverityChip(item.severity, severityLabel(item.severity))
        Text(item.title, style = Finban.type.heading, color = Finban.colors.ink, modifier = Modifier_top)
        item.explanation?.let { Text(it, style = Finban.type.body, color = Finban.colors.body) }

        item.body?.let {
            SectionLabel(stringResource(R.string.detail_message))
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().background(Finban.colors.soft, Radii.md).padding(16.dp)) {
                androidx.compose.foundation.text.selection.SelectionContainer { Text(it, style = Finban.type.body, color = Finban.colors.body) }
            }
        }

        SectionLabel(stringResource(R.string.detail_details))
        Fact(stringResource(R.string.detail_where), sourceLabel(item.source))
        item.from?.let { Fact(stringResource(if (item.source == "screen") R.string.detail_app else R.string.detail_from), it) }
        item.pattern?.let { Fact(stringResource(R.string.detail_pattern), patternLabel(it) ?: it) }
        Fact(stringResource(R.string.detail_when), java.text.SimpleDateFormat("d MMM yyyy, h:mm a", AppLanguage.locale(Session.lang)).format(Date(item.at)))

        if (item.reasons.isNotEmpty()) {
            SectionLabel(stringResource(R.string.sheet_why))
            item.reasons.forEach { IconLine(SeverityIcons.forLevel(item.severity), it) }
        }

        if (item.severity == "high") {
            var st by remember { mutableStateOf<AlertState>(AlertState.Idle) }
            val scope = rememberCoroutineScope()
            SectionLabel(stringResource(R.string.detail_trusted))
            when (val s = st) {
                is AlertState.Sent -> NoteCard(stringResource(if (s.pushed > 0) R.string.alert_sent else R.string.alert_saved))
                AlertState.NoCaretaker -> NoteCard(stringResource(R.string.alert_none))
                else -> {
                    if (s == AlertState.Failed) Text(stringResource(R.string.alert_failed), style = Finban.type.body, color = Finban.colors.body)
                    SecondaryButton(stringResource(if (s == AlertState.Sending) R.string.alert_sending else R.string.alert_button), {
                        if (s != AlertState.Sending) { st = AlertState.Sending; scope.launch { st = CaretakerAlert.send(item.severity, item.pattern) } }
                    })
                }
            }
        }
        Text(stringResource(R.string.detail_note), style = Finban.type.caption, color = Finban.colors.muted, modifier = Modifier_top)
    }
}

private val Modifier_top = androidx.compose.ui.Modifier.padding(top = 8.dp)

@Composable
private fun Fact(label: String, value: String) {
    Column(androidx.compose.ui.Modifier.padding(vertical = 6.dp)) {
        Text(label, style = Finban.type.caption, color = Finban.colors.muted)
        Text(value, style = Finban.type.bodyStrong, color = Finban.colors.ink)
    }
}

@Composable
internal fun severityLabel(level: String) = stringResource(when (level) { "high" -> R.string.severity_high; "careful" -> R.string.severity_careful; else -> R.string.severity_none })

@Composable
internal fun sourceLabel(source: String) = stringResource(when (source) {
    "sms" -> R.string.src_sms; "whatsapp" -> R.string.src_whatsapp; "screen" -> R.string.src_screen
    "share" -> R.string.src_share; "photo" -> R.string.src_photo; else -> R.string.src_check
})

/** "5 minutes ago" in the app's language. Android's own formatter follows the phone's language instead. */
@Composable
internal fun timeAgo(at: Long): String {
    val mins = ((System.currentTimeMillis() - at) / 60_000).toInt()
    return when {
        mins < 1 -> stringResource(R.string.just_now)
        mins < 60 -> pluralStringResource(R.plurals.minutes_ago, mins, mins)
        mins < 1440 -> pluralStringResource(R.plurals.hours_ago, mins / 60, mins / 60)
        else -> pluralStringResource(R.plurals.days_ago, mins / 1440, mins / 1440)
    }
}

/** The scam's name for people. The list of ids lives in data/patterns. Null for an id this build does not know. */
@Composable
internal fun patternLabel(id: String): String? = when (id) {
    "customer-care-screen-share" -> R.string.pattern_screen_share
    "digital-arrest" -> R.string.pattern_digital_arrest
    "fake-kyc" -> R.string.pattern_fake_kyc
    "fake-scheme-ad" -> R.string.pattern_fake_scheme
    "family-emergency" -> R.string.pattern_family_emergency
    "investment-group" -> R.string.pattern_investment
    "otp-request" -> R.string.pattern_otp
    "refund-trick-1" -> R.string.pattern_refund_1
    "refund-trick-2" -> R.string.pattern_refund_2
    "refund-trick-3" -> R.string.pattern_refund_3
    "reward-fee" -> R.string.pattern_reward
    "sim-swap-signs" -> R.string.pattern_sim_swap
    "task-job" -> R.string.pattern_task_job
    else -> null
}?.let { stringResource(it) }

/** Why a caretaker alert was sent: the scam's name, else the trigger in words. */
@Composable
internal fun alertLabel(patternId: String?, trigger: String?): String =
    patternId?.let { patternLabel(it) ?: it } ?: when (trigger) {
        "manual" -> stringResource(R.string.trigger_manual)
        "pause_yes" -> stringResource(R.string.trigger_pause_yes)
        "already_paid" -> stringResource(R.string.trigger_already_paid)
        else -> stringResource(R.string.care_alerts)
    }
