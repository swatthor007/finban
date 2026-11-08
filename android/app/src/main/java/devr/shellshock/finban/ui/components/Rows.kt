package devr.shellshock.finban.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.ui.theme.Finban
import devr.shellshock.finban.ui.theme.Radii
import devr.shellshock.finban.ui.theme.SeverityColors
import devr.shellshock.finban.ui.theme.Sizes
import devr.shellshock.finban.ui.theme.Spacing

/** Shared building blocks from the design artifact (scam.css): .hdr, .li, .stat, .act, .chip, .tg, .note, .sec, .st, .kv/.cp. */

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = Sizes.touch), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconTap(AppIcons.Back, androidx.compose.ui.res.stringResource(devr.shellshock.finban.R.string.back), onBack)
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(title, style = Finban.type.title, color = Finban.colors.ink, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun IconTap(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(Sizes.touch).clickable(onClick = onClick).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = Finban.colors.ink, modifier = Modifier.size(Sizes.icon)) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Finban.type.caption, color = Finban.colors.muted, modifier = modifier.padding(top = Spacing.base, bottom = Spacing.xs))
}

/** `.li` row: title + optional subtitle, optional trailing content. Whole row is the tap target when [onClick] is given. */
@Composable
fun ListRow(
    title: String, subtitle: String? = null, onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .let { if (onClick != null) it.clickable(onClick = onClick).semantics { role = Role.Button } else it }
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Finban.type.bodyStrong, color = Finban.colors.ink)
                if (subtitle != null) Text(subtitle, style = Finban.type.caption, color = Finban.colors.muted)
            }
            trailing?.invoke()
        }
        HorizontalDivider(color = Finban.colors.hair)
    }
}

@Composable
fun ChevronRow(title: String, subtitle: String? = null, onClick: () -> Unit) =
    ListRow(title, subtitle, onClick) {
        Icon(AppIcons.Chevron, null, tint = Finban.colors.muted, modifier = Modifier.size(Sizes.icon))
    }

/** `.tg` toggle row. The switch is a real Switch so TalkBack announces on/off. */
@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) =
    ListRow(title, subtitle, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Finban.colors.accent, checkedThumbColor = Color.White,
                uncheckedTrackColor = Finban.colors.borderStrong, uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Finban.colors.borderStrong,
            ),
        )
    }

/** `.stat` banner, e.g. "Protection is on". */
@Composable
fun StatusBanner(text: String, icon: ImageVector = AppIcons.Info, tone: SeverityColors = Finban.colors.none) {
    Row(
        Modifier.fillMaxWidth().background(tone.tint, Radii.md).padding(Spacing.base),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(icon, null, tint = tone.icon, modifier = Modifier.size(Sizes.iconL))
        Text(text, style = Finban.type.severity, color = tone.ink)
    }
}

/** `.act` big home action. [emphasis]: primary = ink fill, help = risk tint, normal = outlined. */
enum class ActionStyle { Primary, Normal, Help }

@Composable
fun ActionCard(text: String, icon: ImageVector, style: ActionStyle = ActionStyle.Normal, onClick: () -> Unit) {
    val c = Finban.colors
    val bg = when (style) { ActionStyle.Primary -> c.ink; ActionStyle.Help -> c.high.tint; else -> c.canvas }
    val fg = when (style) { ActionStyle.Primary -> c.canvas; ActionStyle.Help -> c.high.ink; else -> c.ink }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).background(bg, Radii.md)
            .border(if (style == ActionStyle.Normal) BorderStroke(1.5.dp, c.borderStrong) else BorderStroke(0.dp, bg), Radii.md)
            .clickable(onClick = onClick).semantics { role = Role.Button }
            .padding(horizontal = Spacing.base, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(Sizes.iconL))
        Text(text, style = Finban.type.button, color = fg)
    }
}

/** `.chip` severity pill: icon + word, never colour alone. */
@Composable
fun SeverityChip(level: String, label: String) {
    val tone = when (level) { "high" -> Finban.colors.high; "careful" -> Finban.colors.careful; else -> Finban.colors.none }
    Row(
        Modifier.background(tone.tint, Radii.full).padding(horizontal = Spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(SeverityIcons.forLevel(level), null, tint = tone.icon, modifier = Modifier.size(18.dp))
        Text(label, style = Finban.type.caption, color = tone.ink)
    }
}

/** `.note` card: icon + bold line + softer line. */
@Composable
fun NoteCard(title: String, body: String? = null, icon: ImageVector = AppIcons.Bell) {
    Row(
        Modifier.fillMaxWidth().background(Finban.colors.soft, Radii.md).padding(Spacing.base),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(icon, null, tint = Finban.colors.ink, modifier = Modifier.size(Sizes.icon))
        Column {
            Text(title, style = Finban.type.bodyStrong, color = Finban.colors.ink)
            if (body != null) Text(body, style = Finban.type.body, color = Finban.colors.body)
        }
    }
}

/** `.r` icon + text row used for reasons and privacy lists. */
@Composable
fun IconLine(icon: ImageVector, title: String, body: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Icon(icon, null, tint = Finban.colors.ink, modifier = Modifier.size(Sizes.icon))
        Column {
            Text(title, style = Finban.type.bodyStrong, color = Finban.colors.ink)
            if (body != null) Text(body, style = Finban.type.caption, color = Finban.colors.muted)
        }
    }
}

/** `.st` numbered step. */
@Composable
fun StepRow(number: Int, title: String, body: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Box(Modifier.size(32.dp).background(Finban.colors.ink, Radii.full), contentAlignment = Alignment.Center) {
            Text("$number", style = Finban.type.caption, color = Finban.colors.canvas)
        }
        Column {
            Text(title, style = Finban.type.bodyStrong, color = Finban.colors.ink)
            if (body != null) Text(body, style = Finban.type.caption, color = Finban.colors.muted)
        }
    }
}

/** `.dis` text-only quiet button (e.g. "Do this later", "Close"). */
@Composable
fun QuietButton(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = Sizes.touch).clickable(onClick = onClick).semantics { role = Role.Button }, contentAlignment = Alignment.Center) {
        Text(text, style = Finban.type.button, color = Finban.colors.muted)
    }
}
