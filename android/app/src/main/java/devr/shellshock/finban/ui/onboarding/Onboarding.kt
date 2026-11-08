package devr.shellshock.finban.ui.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import devr.shellshock.finban.R
import devr.shellshock.finban.data.Role
import devr.shellshock.finban.data.Session
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import devr.shellshock.finban.net.Api
import devr.shellshock.finban.ui.components.AppIcons
import devr.shellshock.finban.ui.components.AppLogo
import devr.shellshock.finban.ui.components.AppTextField
import devr.shellshock.finban.ui.components.BusyRow
import devr.shellshock.finban.ui.components.GlassShield
import devr.shellshock.finban.ui.components.IconLine
import devr.shellshock.finban.ui.components.IconTap
import devr.shellshock.finban.ui.components.NoteCard
import devr.shellshock.finban.ui.components.PrimaryButton
import devr.shellshock.finban.ui.components.QuietButton
import devr.shellshock.finban.ui.components.ScreenScaffold
import devr.shellshock.finban.ui.components.SecondaryButton
import devr.shellshock.finban.ui.components.StepRow
import devr.shellshock.finban.ui.theme.Finban
import devr.shellshock.finban.ui.theme.Radii
import kotlinx.coroutines.launch

/** Design board 1 (Main.dc.html): five steps, nothing is switched on until the person taps. */

@Composable
private fun StepHeader(onBack: () -> Unit, step: Int, of: Int) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(AppIcons.Back, stringResource(R.string.back), onBack)
        Text(stringResource(R.string.step_of, step, of), style = Finban.type.caption, color = Finban.colors.muted)
    }
}

@Composable
fun WelcomeScreen(onNext: () -> Unit, onDev: () -> Unit = {}) = ScreenScaffold(bottom = { PrimaryButton(stringResource(R.string.get_started), onNext) }) {
    var taps by remember { mutableStateOf(0) }
    // Developer shortcut: tap the logo row 5 times to open Developer tools (server address) before pairing. Not shown anywhere.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { taps++; if (taps >= 5) { taps = 0; onDev() } }) {
        AppLogo()
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.app_name), style = Finban.type.title, color = Finban.colors.ink)
    }
    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) { GlassShield(260.dp) }
    Text(stringResource(R.string.welcome_title), style = Finban.type.heading, color = Finban.colors.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.welcome_body), style = Finban.type.body, color = Finban.colors.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
fun LanguageScreen(onBack: () -> Unit, onNext: () -> Unit) {
    ScreenScaffold(bottom = { PrimaryButton(stringResource(R.string.continue_btn), onNext) }) {
        StepHeader(onBack, 1, 4)
        Text(stringResource(R.string.lang_title), style = Finban.type.heading, color = Finban.colors.ink)
        Spacer(Modifier.height(8.dp))
        listOf("ta" to R.string.lang_tamil, "en" to R.string.lang_english).forEach { (code, label) ->
            LanguageOption(stringResource(label), selected = Session.lang == code) { Session.lang = code }
        }
        Text(stringResource(R.string.lang_note), style = Finban.type.caption, color = Finban.colors.muted)
    }
}

@Composable
fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Finban.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.borderStrong, Radii.md)
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Finban.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
        if (selected) Icon(AppIcons.Check, null, tint = c.ink, modifier = Modifier.size(22.dp))
    }
}

/** Not in the artifact: who is using this phone + name. Same tokens. Creates the users/{uid} doc and FCM token. */
@Composable
fun RoleScreen(onBack: () -> Unit, onDone: (Role) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(Session.displayName) }
    var picked by remember { mutableStateOf<Role?>(null) }
    var failed by remember { mutableStateOf(false) }
    var failedWhy by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { picked?.let(onDone) }

    fun go(r: Role) {
        busy = true; failed = false; picked = r
        scope.launch {
            val res = runCatching { Session.chooseRole(r, name.trim()) }
            res.exceptionOrNull()?.let { EventLog.log(EventType.SERVICE, "role-failed", it.toString()); failedWhy = generateSequence<Throwable>(it) { t -> t.cause }.joinToString(" < ") { t -> t.javaClass.simpleName } }
            busy = false
            if (res.isFailure) { failed = true; picked = null; return@launch }
            if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone(r)
        }
    }
    ScreenScaffold(bottom = {
        if (busy) BusyRow(stringResource(R.string.role_busy))
        else {
            PrimaryButton(stringResource(R.string.role_elder), { go(Role.ELDER) }, enabled = name.isNotBlank())
            SecondaryButton(stringResource(R.string.role_caretaker), { if (name.isNotBlank()) go(Role.CARETAKER) })
        }
        if (failed) {
            Text(stringResource(R.string.role_failed), style = Finban.type.body, color = Finban.colors.high.ink)
            // What actually failed (class names only), so "could not connect" can be told apart: network, Firebase config, Google Play services.
            if (failedWhy.isNotBlank()) Text(failedWhy.take(140), style = Finban.type.caption, color = Finban.colors.muted)
        }
    }) {
        StepHeader(onBack, 2, 4)
        Text(stringResource(R.string.role_title), style = Finban.type.heading, color = Finban.colors.ink)
        Text(stringResource(R.string.role_subtitle), style = Finban.type.subtitle, color = Finban.colors.muted)
        Spacer(Modifier.height(8.dp))
        AppTextField(name, { name = it.take(40) }, stringResource(R.string.role_name_label))
    }
}

/**
 * Elder only. Two distinct screens sharing one step, not one screen trying to be both at once:
 * the form (name + optional number, which stay on the phone), then — once a code exists — a focused reveal of it.
 * Showing the form AND the code together was the "cluttered" screen; a code is the one thing that matters once it exists.
 */
@Composable
fun TrustedSetupScreen(onBack: () -> Unit, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(Session.trustedName) }
    var phone by remember { mutableStateOf(Session.trustedPhone) }
    var code by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var failedWhy by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(devr.shellshock.finban.net.ServerConfig.url) }
    var busy by remember { mutableStateOf(false) }

    fun makeCode() {
        Session.trustedName = name.trim(); Session.trustedPhone = phone.trim()
        busy = true; failed = false
        scope.launch {
            runCatching { Api.client.pairCode().code }.onSuccess { code = it }
                .onFailure { failed = true; failedWhy = generateSequence<Throwable>(it) { t -> t.cause }.joinToString(" < ") { t -> t.javaClass.simpleName } }
            busy = false
        }
    }

    val codeNow = code
    if (codeNow != null) {
        ScreenScaffold(bottom = { PrimaryButton(stringResource(R.string.continue_btn), onNext) }) {
            StepHeader(onBack, 3, 4)
            Text(stringResource(R.string.trusted_code_title), style = Finban.type.heading, color = Finban.colors.ink)
            // Monospace + letter-spaced: a code is data to copy, not a word to read, and digits must not be mistaken for one another.
            Text(
                codeNow.chunked(3).joinToString("   "), style = Finban.type.code.copy(fontSize = 38.sp, lineHeight = 46.sp),
                color = Finban.colors.ink, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            )
            Text(stringResource(R.string.trusted_code_hint), style = Finban.type.body, color = Finban.colors.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        return
    }

    ScreenScaffold(bottom = {
        if (busy) BusyRow(stringResource(R.string.trusted_code_busy))
        else {
            PrimaryButton(stringResource(R.string.trusted_make_code), { makeCode() })
            QuietButton(stringResource(R.string.later), onNext)
        }
        if (failed) {
            Text(stringResource(R.string.trusted_code_failed), style = Finban.type.body, color = Finban.colors.high.ink)
            if (failedWhy.isNotBlank()) Text(failedWhy.take(140), style = Finban.type.caption, color = Finban.colors.muted)
        }
    }) {
        StepHeader(onBack, 3, 4)
        Text(stringResource(R.string.trusted_title), style = Finban.type.heading, color = Finban.colors.ink)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GlassShield(100.dp) }
        AppTextField(name, { name = it.take(40) }, stringResource(R.string.trusted_name_label))
        AppTextField(
            phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) }, stringResource(R.string.trusted_phone_label),
            supporting = stringResource(R.string.trusted_phone_hint), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        NoteCard(stringResource(R.string.trusted_note_title), stringResource(R.string.trusted_note_body))
        if (failed) {
            // The usual cause on a second phone: the app still points at the build default (127.0.0.1 = this phone itself).
            NoteCard(stringResource(R.string.server_fix_title), stringResource(R.string.server_fix_body))
            AppTextField(server, { server = it }, stringResource(R.string.server_label))
            SecondaryButton(stringResource(R.string.server_save_retry), { devr.shellshock.finban.net.ServerConfig.url = server; makeCode() })
        }
    }
}

fun accessibilityOn(ctx: android.content.Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.contains("${ctx.packageName}/", ignoreCase = true)
}

@Composable
fun PermissionsScreen(onBack: () -> Unit, onNext: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val resume = devr.shellshock.finban.ui.components.rememberResumeTick()
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    ScreenScaffold(bottom = {
        PrimaryButton(stringResource(R.string.continue_btn), onNext)
    }) {
        StepHeader(onBack, 4, 4)
        Text(stringResource(R.string.perm_title), style = Finban.type.heading, color = Finban.colors.ink)
        Text(stringResource(R.string.perm_sub), style = Finban.type.subtitle, color = Finban.colors.muted)
        IconLine(AppIcons.Eye, stringResource(R.string.perm_1))
        IconLine(AppIcons.Check, stringResource(R.string.perm_2))
        IconLine(AppIcons.User, stringResource(R.string.perm_3))
        // Status checklist: each line is ticked from the real state and refreshed when the person comes back from Settings.
        androidx.compose.runtime.key(tick, resume) {
            devr.shellshock.finban.ui.components.PermRow(stringResource(R.string.perm_row_access), accessibilityOn(ctx), stringResource(R.string.perm_open)) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            devr.shellshock.finban.ui.components.PermRow(stringResource(R.string.perm_row_sms), devr.shellshock.finban.ui.components.smsPermissionsGranted(ctx), stringResource(R.string.perm_allow)) {
                smsLauncher.launch(devr.shellshock.finban.ui.components.SMS_PERMISSIONS)
            }
            devr.shellshock.finban.ui.components.PermRow(stringResource(R.string.perm_row_notif_access), devr.shellshock.finban.ui.components.notificationAccessOn(ctx), stringResource(R.string.perm_open)) {
                devr.shellshock.finban.ui.components.openNotificationAccess(ctx)
            }
            if (devr.shellshock.finban.calls.CallScreeningRole.available(ctx)) {
                devr.shellshock.finban.ui.components.PermRow(stringResource(R.string.perm_row_call), devr.shellshock.finban.calls.CallScreeningRole.held(ctx), stringResource(R.string.perm_allow)) {
                    roleLauncher.launch(devr.shellshock.finban.calls.CallScreeningRole.requestIntent(ctx))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Text(stringResource(R.string.perm_restricted), style = Finban.type.bodyStrong, color = Finban.colors.muted, modifier = Modifier.padding(top = 12.dp))
            StepRow(1, stringResource(R.string.perm_step_1))
            StepRow(2, stringResource(R.string.perm_step_2))
            StepRow(3, stringResource(R.string.perm_step_3))
            SecondaryButton(stringResource(R.string.perm_step_1), {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
    }
}

@Composable
fun ProtectedScreen(onDone: () -> Unit) = ScreenScaffold(bottom = { PrimaryButton(stringResource(R.string.go_home), onDone) }) {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { GlassShield(220.dp) }
    Text(stringResource(R.string.protected_title), style = Finban.type.heading, color = Finban.colors.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.protected_body), style = Finban.type.body, color = Finban.colors.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}
