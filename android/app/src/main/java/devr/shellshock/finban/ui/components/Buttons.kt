package devr.shellshock.finban.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.ui.theme.Finban
import devr.shellshock.finban.ui.theme.Radii
import devr.shellshock.finban.ui.theme.Sizes

/** 56dp minimum touch target, ink-on-canvas as in the design's `.btn.p`. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, shape = Radii.sm,
        modifier = modifier.fillMaxWidth().heightIn(min = Sizes.touch),
        colors = ButtonDefaults.buttonColors(containerColor = Finban.colors.ink, contentColor = Finban.colors.canvas,
            disabledContainerColor = Finban.colors.borderStrong, disabledContentColor = Finban.colors.canvas),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(text, style = Finban.type.button, textAlign = TextAlign.Center) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = Radii.sm,
        modifier = modifier.fillMaxWidth().heightIn(min = Sizes.touch),
        border = BorderStroke(1.5.dp, if (enabled) Finban.colors.ink else Finban.colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Finban.colors.canvas, contentColor = Finban.colors.ink,
            disabledContentColor = Finban.colors.muted),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(text, style = Finban.type.button, textAlign = TextAlign.Center) }
}

/** Spinner + text, so a button tap that kicked off a network call doesn't look like it did nothing while it waits. */
@Composable
fun BusyRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Sizes.touch), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Finban.colors.ink, strokeWidth = 2.5.dp)
        Text(text, style = Finban.type.body, color = Finban.colors.body)
    }
}
