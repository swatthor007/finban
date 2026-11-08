package devr.shellshock.finban.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import devr.shellshock.finban.ui.components.SecondaryButton
import devr.shellshock.finban.ui.theme.Finban
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import devr.shellshock.finban.R
import devr.shellshock.finban.data.Session
import devr.shellshock.finban.ui.components.AppIcons
import devr.shellshock.finban.ui.components.AppTextField
import devr.shellshock.finban.ui.components.ChevronRow
import devr.shellshock.finban.ui.components.IconLine
import devr.shellshock.finban.ui.components.NoteCard
import devr.shellshock.finban.ui.components.PrimaryButton
import devr.shellshock.finban.ui.components.QuietButton
import devr.shellshock.finban.ui.components.ScreenScaffold
import devr.shellshock.finban.ui.components.SectionLabel
import devr.shellshock.finban.ui.components.ToggleRow
import devr.shellshock.finban.ui.components.TopBar

/** Design board 11: five rows, plain words, nothing hidden. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onTrusted: () -> Unit, onPrivacy: () -> Unit, onDebug: () -> Unit = {}) {
    var voice by remember { mutableStateOf(Session.voice) }
    var bubble by remember { mutableStateOf(Session.bubbleOn) }
    var pictures by remember { mutableStateOf(Session.pictureCheck) }
    ScreenScaffold {
        TopBar(stringResource(R.string.settings), onBack)
        ChevronRow(stringResource(R.string.set_language), stringResource(if (Session.lang == "ta") R.string.lang_tamil else R.string.lang_english)) {
            Session.lang = if (Session.lang == "ta") "en" else "ta"
        }
        ToggleRow(stringResource(R.string.set_voice), stringResource(R.string.set_voice_sub), voice) { voice = it; Session.voice = it }
        ChevronRow(stringResource(R.string.set_trusted), Session.trustedName.ifBlank { null }, onTrusted)
        ToggleRow(stringResource(R.string.set_bubble), stringResource(R.string.set_bubble_sub), bubble) { bubble = it; Session.bubbleOn = it; devr.shellshock.finban.a11y.FinbanAccessibilityService.instance?.setBubble(it) }
        ToggleRow(stringResource(R.string.set_pictures), stringResource(R.string.set_pictures_sub), pictures) { pictures = it; Session.pictureCheck = it }
        ChevronRow(stringResource(R.string.set_privacy), stringResource(R.string.set_privacy_sub), onPrivacy)
        ChevronRow(stringResource(R.string.set_debug), stringResource(R.string.set_debug_sub), onDebug)
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) = ScreenScaffold {
    TopBar(stringResource(R.string.set_privacy), onBack)
    SectionLabel(stringResource(R.string.privacy_sees))
    IconLine(AppIcons.Eye, stringResource(R.string.privacy_sees_1), stringResource(R.string.privacy_sees_1b))
    IconLine(AppIcons.Image, stringResource(R.string.privacy_sees_2), stringResource(R.string.privacy_sees_2b))
    IconLine(AppIcons.Eye, stringResource(R.string.privacy_sees_3), stringResource(R.string.privacy_sees_3b))
    SectionLabel(stringResource(R.string.privacy_never))
    IconLine(AppIcons.Close, stringResource(R.string.privacy_never_1))
    IconLine(AppIcons.Close, stringResource(R.string.privacy_never_2))
    IconLine(AppIcons.Close, stringResource(R.string.privacy_never_3), stringResource(R.string.privacy_never_3b))
}

@Composable
fun TrustedScreen(onBack: () -> Unit) {
    var name by remember { mutableStateOf(Session.trustedName) }
    var phone by remember { mutableStateOf(Session.trustedPhone) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failedWhy by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf(devr.shellshock.finban.net.ServerConfig.url) }
    val linked = devr.shellshock.finban.data.rememberLinkCount(devr.shellshock.finban.data.Role.ELDER)
    fun makeCode() {
        busy = true; failedWhy = null
        scope.launch {
            runCatching { devr.shellshock.finban.net.Api.client.pairCode().code }.onSuccess { code = it }
                .onFailure { failedWhy = generateSequence<Throwable>(it) { t -> t.cause }.joinToString(" < ") { t -> t.javaClass.simpleName }.ifBlank { "error" } }
            busy = false
        }
    }
    ScreenScaffold(bottom = {
        PrimaryButton(stringResource(R.string.trusted_edit_save), { Session.trustedName = name.trim(); Session.trustedPhone = phone.trim(); onBack() })
        QuietButton(stringResource(R.string.trusted_remove), { Session.trustedName = ""; Session.trustedPhone = ""; onBack() })
    }) {
        TopBar(stringResource(R.string.set_trusted), onBack)
        AppTextField(name, { name = it.take(40) }, stringResource(R.string.trusted_name_label))
        Spacer(Modifier.height(8.dp))
        AppTextField(
            phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) }, stringResource(R.string.trusted_phone_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        Spacer(Modifier.height(8.dp))
        NoteCard(stringResource(R.string.trusted_only_when_tap), stringResource(R.string.trusted_note_body))

        // Pairing after setup: make a fresh code any time (codes last 10 minutes and are single-use).
        SectionLabel(stringResource(R.string.link_add))
        Text(
            when (linked) { null -> ""; 0 -> stringResource(R.string.link_none); 1 -> stringResource(R.string.link_count_one); else -> stringResource(R.string.link_count_many, linked) },
            style = Finban.type.bodyStrong, color = Finban.colors.ink,
        )
        code?.let {
            Text(it.chunked(3).joinToString("   "), style = Finban.type.code.copy(fontSize = 34.sp, lineHeight = 42.sp), color = Finban.colors.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text(stringResource(R.string.link_code_hint), style = Finban.type.caption, color = Finban.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        SecondaryButton(stringResource(if (busy) R.string.trusted_code_busy else R.string.link_make_code), { if (!busy) makeCode() })
        failedWhy?.let { why ->
            Text(stringResource(R.string.link_code_failed), style = Finban.type.body, color = Finban.colors.high.ink)
            Text(why.take(140), style = Finban.type.caption, color = Finban.colors.muted)
            AppTextField(server, { server = it }, stringResource(R.string.server_label))
            SecondaryButton(stringResource(R.string.server_save_retry), { devr.shellshock.finban.net.ServerConfig.url = server; makeCode() })
        }
    }
}
