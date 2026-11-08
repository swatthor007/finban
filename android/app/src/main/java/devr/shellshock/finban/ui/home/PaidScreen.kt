package devr.shellshock.finban.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import devr.shellshock.finban.net.AlertState
import devr.shellshock.finban.net.CaretakerAlert
import devr.shellshock.finban.ui.components.BusyRow
import devr.shellshock.finban.ui.components.SecondaryButton
import kotlinx.coroutines.launch
import devr.shellshock.finban.ui.components.AppIcons
import devr.shellshock.finban.ui.components.QuietButton
import devr.shellshock.finban.ui.components.ScreenScaffold
import devr.shellshock.finban.ui.components.StepRow
import devr.shellshock.finban.ui.components.WarningIllustration
import devr.shellshock.finban.ui.theme.Finban
import devr.shellshock.finban.ui.theme.Radii

/** Design board 6: calm but urgent. One huge call button, three steps. Opens the dialler only; the person presses call. */
@Composable
fun PaidScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    ScreenScaffold(bottom = { QuietButton(stringResource(R.string.close), onClose) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.paid_title), style = Finban.type.title, color = Finban.colors.ink)
                Text(stringResource(R.string.paid_sub), style = Finban.type.subtitle, color = Finban.colors.muted)
            }
            WarningIllustration(72.dp)
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 88.dp).background(Finban.colors.emergency, Radii.md)
                .clickable { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Phone, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.paid_call), style = Finban.type.title, color = androidx.compose.ui.graphics.Color.White)
                Text(stringResource(R.string.paid_call_sub), style = Finban.type.caption, color = androidx.compose.ui.graphics.Color.White)
            }
        }
        var alert by remember { mutableStateOf<AlertState>(AlertState.Idle) }
        val scope = rememberCoroutineScope()
        when (val st = alert) {
            is AlertState.Sent -> Text(stringResource(if (st.pushed > 0) R.string.alert_sent else R.string.alert_saved), style = Finban.type.bodyStrong, color = Finban.colors.ink)
            AlertState.NoCaretaker -> Text(stringResource(R.string.alert_none), style = Finban.type.body, color = Finban.colors.body)
            AlertState.Sending -> BusyRow(stringResource(R.string.alert_sending))
            else -> {
                if (st == AlertState.Failed) Text(stringResource(R.string.alert_failed), style = Finban.type.body, color = Finban.colors.body)
                SecondaryButton(stringResource(R.string.paid_tell), { alert = AlertState.Sending; scope.launch { alert = CaretakerAlert.send("high", null, "already_paid") } })
            }
        }
        Text(stringResource(R.string.paid_steps), style = Finban.type.bodyStrong, color = Finban.colors.muted)
        StepRow(1, stringResource(R.string.paid_s1))
        StepRow(2, stringResource(R.string.paid_s2))
        StepRow(3, stringResource(R.string.paid_s3))
    }
}
