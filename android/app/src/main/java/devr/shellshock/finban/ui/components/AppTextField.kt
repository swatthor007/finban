package devr.shellshock.finban.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.ui.theme.Finban
import devr.shellshock.finban.ui.theme.Sizes

/**
 * The one text field style for the app. Fixes two real bugs seen in testing:
 * 1. `OutlinedTextField`'s floating label shrinks to the label's own `TextStyle` but keeps that style's line height
 *    for its box, so using the 30sp-line-height body style as the label made the floated label overlap the typed
 *    text. `Finban.type.fieldLabel` has a tight line height for exactly this reason.
 * 2. The border's notch around the floated label is sized for ONE line. A label long enough to wrap (e.g. a phone
 *    field's "(optional, stays on this phone)") had its second line collide with the border. Labels here are
 *    forced to one line (ellipsis if still too long) and any extra explanation goes in [supporting] below the
 *    field instead, which has normal room to wrap.
 */
@Composable
fun AppTextField(
    value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    supporting: String? = null, singleLine: Boolean = true, monospace: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = singleLine,
        label = { Text(label, style = Finban.type.fieldLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingText = supporting?.let { { Text(it, style = Finban.type.caption, color = Finban.colors.muted) } },
        textStyle = if (monospace) Finban.type.body.copy(fontFamily = FontFamily.Monospace) else Finban.type.body,
        keyboardOptions = keyboardOptions,
        modifier = modifier.fillMaxWidth().heightIn(min = Sizes.touch),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Finban.colors.ink, unfocusedBorderColor = Finban.colors.borderStrong,
            focusedLabelColor = Finban.colors.ink, unfocusedLabelColor = Finban.colors.muted,
            cursorColor = Finban.colors.ink,
        ),
    )
}
