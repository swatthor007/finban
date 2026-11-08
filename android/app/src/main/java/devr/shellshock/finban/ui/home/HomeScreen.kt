package devr.shellshock.finban.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.R
import devr.shellshock.finban.data.RecentChecks
import devr.shellshock.finban.data.Session
import devr.shellshock.finban.ui.components.AppIcons
import devr.shellshock.finban.ui.components.AppLogo
import devr.shellshock.finban.ui.components.IconTap
import devr.shellshock.finban.ui.components.ScreenScaffold
import devr.shellshock.finban.ui.components.SecondaryButton
import devr.shellshock.finban.ui.components.SectionLabel
import devr.shellshock.finban.ui.components.SeverityIcons
import devr.shellshock.finban.ui.onboarding.accessibilityOn
import devr.shellshock.finban.ui.theme.Finban
import devr.shellshock.finban.ui.theme.Radii
import devr.shellshock.finban.ui.theme.Spacing

/** Elder Home: status hero, three clear actions, compact recent risky checks (tap for the full log), trusted person, helplines. */
@Composable
fun HomeScreen(onSettings: () -> Unit, onPaid: () -> Unit, onOpenCheck: (Long) -> Unit) {
    val ctx = LocalContext.current
    devr.shellshock.finban.ui.components.rememberResumeTick()
    val on = accessibilityOn(ctx)
    val smsOk = devr.shellshock.finban.ui.components.smsPermissionsGranted(ctx)
    val recent = remember { RecentChecks.all() }
    val c = Finban.colors
    ScreenScaffold {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppLogo()
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(stringResource(R.string.app_name), style = Finban.type.title, color = c.ink)
                Session.displayName.takeIf { it.isNotBlank() }?.let {
                    Text(stringResource(R.string.home_hello, it), style = Finban.type.caption, color = c.muted)
                }
            }
            IconTap(AppIcons.Sliders, stringResource(R.string.settings), onSettings)
        }

        // Status hero
        val protectedAll = on && smsOk
        val tone = if (protectedAll) c.none else c.careful
        Row(
            Modifier.fillMaxWidth().background(tone.tint, Radii.md).padding(Spacing.base),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(Modifier.size(48.dp).background(c.canvas, Radii.full), contentAlignment = Alignment.Center) {
                Icon(if (protectedAll) AppIcons.Check else SeverityIcons.Careful, null, tint = tone.icon, modifier = Modifier.size(Spacing.lg))
            }
            Column {
                Text(stringResource(if (protectedAll) R.string.home_protection_on else if (on) R.string.home_finish_setup else R.string.home_protection_off), style = Finban.type.severity, color = tone.ink)
                Text(stringResource(if (protectedAll) R.string.home_protection_sub else if (on) R.string.home_finish_setup_sub else R.string.home_protection_off_sub), style = Finban.type.caption, color = tone.ink)
            }
        }

        // Primary action
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).background(c.ink, Radii.md)
                .clickable {
                    // Finban can't read its own screen: send the person to the other app, where the round button does the check.
                    if (!on) ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else android.widget.Toast.makeText(ctx, R.string.home_check_screen_hint, android.widget.Toast.LENGTH_LONG).show()
                }.semantics { role = Role.Button }.padding(horizontal = Spacing.base, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(AppIcons.Search, null, tint = c.canvas, modifier = Modifier.size(Spacing.lg + 4.dp))
            Text(stringResource(R.string.home_check_screen), style = Finban.type.button, color = c.canvas)
        }

        // Two tiles
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Tile(Modifier.weight(1f).fillMaxHeight(), AppIcons.Image, stringResource(R.string.home_check_message), c.soft, c.ink) {
                ctx.startActivity(Intent(ctx, devr.shellshock.finban.ui.share.ShareReceiverActivity::class.java).putExtra(devr.shellshock.finban.ui.share.ShareReceiverActivity.EXTRA_PICK, true))
            }
            Tile(Modifier.weight(1f).fillMaxHeight(), AppIcons.Rupee, stringResource(R.string.home_paid), c.high.tint, c.high.ink, onPaid)
        }

        // Recent risky checks: compact rows, full details on the next page
        SectionLabel(stringResource(R.string.home_recent))
        if (recent.isEmpty()) Text(stringResource(R.string.home_recent_empty), style = Finban.type.body, color = c.muted)
        Column(Modifier.fillMaxWidth().background(c.soft, Radii.md)) {
            recent.take(5).forEachIndexed { i, item -> CheckRow(item, last = i == minOf(recent.size, 5) - 1) { onOpenCheck(item.at) } }
        }.takeIf { recent.isNotEmpty() }

        // Trusted person + helplines
        SectionLabel(stringResource(R.string.home_trusted_label))
        val phone = Session.trustedPhone
        Row(
            Modifier.fillMaxWidth().background(c.soft, Radii.md).padding(Spacing.base),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(Modifier.size(44.dp).background(c.canvas, Radii.full), contentAlignment = Alignment.Center) { Icon(AppIcons.User, null, tint = c.ink, modifier = Modifier.size(Spacing.lg)) }
            Column(Modifier.weight(1f)) {
                Text(Session.trustedName.ifBlank { stringResource(R.string.home_no_trusted) }, style = Finban.type.bodyStrong, color = c.ink)
                if (phone.isNotBlank()) Text(phone, style = Finban.type.caption, color = c.muted)
            }
            if (phone.isNotBlank()) Box(Modifier.size(width = 110.dp, height = 56.dp)) {
                // ACTION_DIAL only opens the dialler; the person presses call.
                SecondaryButton(stringResource(R.string.call), { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { SecondaryButton(stringResource(R.string.call_1930), { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) }
            Box(Modifier.weight(1f)) { SecondaryButton(stringResource(R.string.chakshu), { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sancharsaathi.gov.in/sfc/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) }
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, icon: ImageVector, text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 112.dp).background(bg, Radii.md).clickable(onClick = onClick).semantics { role = Role.Button }.padding(Spacing.base),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(Spacing.lg + 4.dp))
        Spacer(Modifier.size(Spacing.sm))
        Text(text, style = Finban.type.bodyStrong, color = fg)
    }
}

/** One compact line per check: severity glyph, short title, where + when. The whole row opens the details page. */
@Composable
private fun CheckRow(item: RecentChecks.Item, last: Boolean, onClick: () -> Unit) {
    val tone = when (item.severity) { "high" -> Finban.colors.high; "careful" -> Finban.colors.careful; else -> Finban.colors.none }
    val ago = timeAgo(item.at)
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).semantics { role = Role.Button }.padding(horizontal = Spacing.base, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(Modifier.size(40.dp).background(tone.tint, Radii.sm), contentAlignment = Alignment.Center) {
                Icon(SeverityIcons.forLevel(item.severity), stringResource(if (item.severity == "high") R.string.severity_high else R.string.severity_careful), tint = tone.icon, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(compactTitle(item), style = Finban.type.bodyStrong, color = Finban.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${sourceLabel(item.source)} · $ago", style = Finban.type.caption, color = Finban.colors.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            Icon(AppIcons.Chevron, null, tint = Finban.colors.muted, modifier = Modifier.size(Spacing.lg))
        }
        if (!last) androidx.compose.material3.HorizontalDivider(color = Finban.colors.hair, modifier = Modifier.padding(horizontal = Spacing.base))
    }
}

/** Short, specific line for the list: the pattern name if known ("Fake KYC"), plus the sender for texts. Full wording is on the details page. */
@Composable
private fun compactTitle(i: RecentChecks.Item): String {
    val what = i.pattern?.let { patternLabel(it) } ?: i.title.trimEnd('.')
    return if ((i.source == "sms" || i.source == "whatsapp") && i.from != null) "$what · ${i.from}" else what
}
