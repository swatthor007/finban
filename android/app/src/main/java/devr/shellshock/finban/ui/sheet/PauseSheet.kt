package devr.shellshock.finban.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.R
import devr.shellshock.finban.net.AlertState
import devr.shellshock.finban.ui.components.BusyRow
import devr.shellshock.finban.ui.components.PrimaryButton
import devr.shellshock.finban.ui.components.SecondaryButton
import devr.shellshock.finban.ui.theme.Finban

/**
 * Shown over a payment app during or just after a call from someone not in contacts. Three Yes/No questions, answered by the person.
 * Any Yes calls [onYes] once (the caller tells the trusted person). Never blocks: Close is always there.
 */
@Composable
fun PauseSheetContent(alert: AlertState, onClose: () -> Unit, onYes: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var anyYes by remember { mutableStateOf(false) }
    val questions = listOf(R.string.pause_q1, R.string.pause_q2, R.string.pause_q3)
    fun answer(yes: Boolean) {
        if (yes) { anyYes = true; onYes() }
        step++
    }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pause_title), style = Finban.type.title, color = Finban.colors.ink)
        Text(stringResource(R.string.pause_sub), style = Finban.type.subtitle, color = Finban.colors.muted)
        Spacer(Modifier.size(16.dp))
        if (step < questions.size) {
            StepBars(done = step + 1, total = questions.size)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.pause_step, step + 1, questions.size), style = Finban.type.caption, color = Finban.colors.muted)
            Spacer(Modifier.size(4.dp))
            Text(stringResource(questions[step]), style = Finban.type.heading, color = Finban.colors.ink)
            Spacer(Modifier.size(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.pause_yes), { answer(true) }, Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.pause_no), { answer(false) }, Modifier.weight(1f))
            }
            Spacer(Modifier.size(12.dp))
            SecondaryButton(stringResource(R.string.pause_skip), onClose)
        } else {
            if (anyYes) {
                Text(stringResource(R.string.pause_yes_advice), style = Finban.type.body, color = Finban.colors.ink)
                Spacer(Modifier.size(12.dp))
                when (alert) {
                    AlertState.Sending -> BusyRow(stringResource(R.string.alert_sending))
                    is AlertState.Sent -> Text(stringResource(if (alert.pushed > 0) R.string.alert_sent else R.string.alert_saved), style = Finban.type.bodyStrong, color = Finban.colors.ink)
                    AlertState.NoCaretaker -> Text(stringResource(R.string.alert_none), style = Finban.type.body, color = Finban.colors.body)
                    AlertState.Failed -> Text(stringResource(R.string.alert_failed), style = Finban.type.body, color = Finban.colors.body)
                    else -> {}
                }
            } else {
                Text(stringResource(R.string.pause_all_no), style = Finban.type.body, color = Finban.colors.ink)
            }
            Spacer(Modifier.size(20.dp))
            PrimaryButton(stringResource(R.string.sheet_close), onClose)
        }
    }
}

/** One bar per question: filled up to and including the current one. Text beside it says the same, so it is never colour alone. */
@Composable
private fun StepBars(done: Int, total: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            Box(Modifier.weight(1f).height(6.dp).background(if (i < done) Finban.colors.ink else Finban.colors.hair, RoundedCornerShape(3.dp)))
        }
    }
}
