package devr.shellshock.finban.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import devr.shellshock.finban.R
import devr.shellshock.finban.net.AnalyzeResponse
import devr.shellshock.finban.net.Reason
import devr.shellshock.finban.net.explanation
import devr.shellshock.finban.net.text
import devr.shellshock.finban.net.title
import devr.shellshock.finban.ui.components.PrimaryButton
import devr.shellshock.finban.ui.components.SeverityIcons
import devr.shellshock.finban.ui.components.WarningIllustration
import devr.shellshock.finban.ui.theme.Finban
import devr.shellshock.finban.ui.theme.Radii
import devr.shellshock.finban.ui.theme.SeverityColors
import devr.shellshock.finban.ui.theme.Sizes

sealed interface SheetState {
    data object Loading : SheetState
    /** A picture is being looked at: the sheet shows it with a scanning sweep over it. Held in memory, on screen only. */
    data class ScanningPicture(val picture: android.graphics.Bitmap) : SheetState
    data class Result(val response: AnalyzeResponse, val picture: PictureInfo? = null) : SheetState
    /** Timeout, offline or server error. No local rules engine in the prototype, so we only say we couldn't check. */
    data object Failed : SheetState
    /** A photo was picked but no text could be read from it. */
    data object NoText : SheetState
    /** The bubble was tapped but no text could be read from the screen (secure/blocked app, canvas, or Finban itself). */
    data object NoScreenText : SheetState
}

/** What a vision model said about a picture (description only; the warning level never comes from it) and the AI-photo check. */
data class PictureInfo(val description: String?, val aiImage: String, val bitmap: android.graphics.Bitmap? = null, val kind: devr.shellshock.finban.ui.share.PictureKind? = null)

sealed interface LinkCheckState {
    data object Idle : LinkCheckState
    data object Running : LinkCheckState
    data class Done(val response: devr.shellshock.finban.net.LinkCheckResponse) : LinkCheckState
    data object Failed : LinkCheckState
}

/** The optional "look at this link more closely" step. [label] is the site name shown to the person. */
class LinkCheckUi(val label: String, val state: LinkCheckState, val onCheck: () -> Unit)

private val RANK = mapOf("none" to 0, "careful" to 1, "high" to 2)
private fun higher(a: String, b: String?) = if (b != null && (RANK[b] ?: 0) > (RANK[a] ?: 0)) b else a

private fun severityColors(level: String, c: devr.shellshock.finban.ui.theme.FinbanColors): SeverityColors =
    when (level) { "high" -> c.high; "careful" -> c.careful; else -> c.none }

/**
 * Body of the result sheet. Hosted by a ModalBottomSheet (share, phase B) and later by the accessibility overlay (bubble, phase D).
 * Warns only: the one button closes the sheet; nothing is sent or tapped on the person's behalf.
 */
@Composable
fun ResultSheetContent(state: SheetState, onClose: () -> Unit, shared: String? = null, linkCheck: LinkCheckUi? = null, imageVerdict: String? = null, overlay: Boolean = false, alert: AlertUi? = null, aiCheck: AiCheckUi? = null) {
    val maxScroll = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    // In the bubble overlay the window already supplies side/bottom padding and the grab handle.
    Column(if (overlay) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)) {
        when (state) {
            SheetState.Loading -> Loading(shared)
            is SheetState.ScanningPicture -> ScanningPicture(state.picture)
            SheetState.NoScreenText -> Body(
                level = "careful", titleMain = stringResource(R.string.screen_no_text_title), titleSub = stringResource(R.string.screen_no_text_sub),
                reasons = emptyList(), explanation = null, ai = false, showDisclaimer = false, maxScroll = maxScroll, shared = null, linkCheck = null,
            )
            SheetState.NoText -> Body(
                level = "careful", titleMain = stringResource(R.string.image_no_text_title), titleSub = stringResource(R.string.image_no_text_sub),
                reasons = emptyList(), explanation = null, ai = false, showDisclaimer = false, maxScroll = maxScroll, shared = shared, linkCheck = null, imageVerdict = imageVerdict,
            )
            SheetState.Failed -> Body(
                level = "careful", titleMain = stringResource(R.string.sheet_failed_title), titleSub = stringResource(R.string.sheet_failed_sub),
                reasons = emptyList(), explanation = null, ai = false, showDisclaimer = false, maxScroll = maxScroll, shared = shared, linkCheck = null,
            )
            is SheetState.Result -> {
                val r = state.response
                // Tamil: Tamil title with the English one underneath. English: English only. Reasons and paragraph: one language.
                val englishSub = r.titleEn.takeIf { devr.shellshock.finban.data.Session.lang == "ta" && r.title != it }
                val found = (linkCheck?.state as? LinkCheckState.Done)?.response
                val linkLevel = higher(r.severity, found?.severity)   // a link check can only raise the warning, never lower it
                // A probably-computer-made photo raises "none" to "careful" and no further (image signals are capped at careful).
                val pic = state.picture
                val iv = imageVerdict ?: pic?.aiImage?.takeIf { it != "none" }
                val aiPhoto = iv == "likely_ai"
                val level = if (aiPhoto) higher(linkLevel, "careful") else linkLevel
                val photoOnly = aiPhoto && linkLevel == "none"
                val escalated = linkLevel != r.severity && linkLevel == "high"
                Body(
                    level = level,
                    titleMain = if (escalated) stringResource(R.string.link_escalated_title) else if (photoOnly) stringResource(R.string.image_ai_title) else r.title,
                    titleSub = if (escalated || photoOnly) null else englishSub,
                    linkCheck = linkCheck.takeIf { r.severity != "high" },
                    reasons = r.reasons.take(3),
                    explanation = if (photoOnly) null else r.explanation
                        ?.takeUnless { e -> r.reasons.any { it.text.trim() == e.trim() } }, // don't repeat a reason as the paragraph
                    ai = r.aiAssisted, showDisclaimer = level == "none", maxScroll = maxScroll, shared = shared, imageVerdict = iv, pictureDescription = pic?.description, pictureBitmap = pic?.bitmap, pictureKind = pic?.kind,
                )
            }
        }
        if (state !is SheetState.Loading) {
            Spacer(Modifier.size(24.dp))
            if (aiCheck != null && state is SheetState.Result) {
                devr.shellshock.finban.ui.components.SecondaryButton(stringResource(R.string.ai_check_button), aiCheck.onCheck); Spacer(Modifier.size(12.dp))
            }
            if (alert != null && state is SheetState.Result && state.response.severity == "high") { AlertSection(alert); Spacer(Modifier.size(12.dp)) }
            PrimaryButton(stringResource(R.string.sheet_close), onClose)
        }
    }
}

@Composable
private fun Loading(shared: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.sheet_loading_title), style = Finban.type.title, color = Finban.colors.ink)
        Spacer(Modifier.size(4.dp))
        Text(stringResource(R.string.sheet_loading_sub), style = Finban.type.subtitle, color = Finban.colors.muted)
        Spacer(Modifier.size(16.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Finban.colors.accent, trackColor = Finban.colors.hair)
        if (shared != null) SharedQuote(shared)
    }
}

@Composable
private fun Body(
    level: String, titleMain: String, titleSub: String?, reasons: List<Reason>,
    explanation: String?, ai: Boolean, showDisclaimer: Boolean, maxScroll: androidx.compose.ui.unit.Dp, shared: String?,
    linkCheck: LinkCheckUi?, imageVerdict: String? = null, pictureDescription: String? = null, pictureBitmap: android.graphics.Bitmap? = null,
    pictureKind: devr.shellshock.finban.ui.share.PictureKind? = null,
) {
    SeverityBar(level)
    val word = stringResource(when (level) { "high" -> R.string.severity_high; "careful" -> R.string.severity_careful; else -> R.string.severity_none })
    // Don't say the same words twice (badge and headline), e.g. "No warning signs found" / "No warning signs found."
    val showTitle = !titleMain.trim().trimEnd('.').equals(word, ignoreCase = true)
    Column(Modifier.heightIn(max = maxScroll).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.size(0.dp))
        // Design board 3: title left, 72 dp 3D illustration right (high risk only).
        if (showTitle || titleSub != null || level == "high") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showTitle) Text(titleMain, style = Finban.type.title, color = Finban.colors.ink)
                if (titleSub != null) Text(titleSub, style = Finban.type.subtitle, color = Finban.colors.body)
            }
            if (level == "high") WarningIllustration(80.dp)
        }
        if (pictureDescription != null || pictureBitmap != null) PictureNote(pictureDescription, pictureBitmap)
        if (pictureKind != null) PictureKindNote(pictureKind)
        if (reasons.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeading(stringResource(R.string.sheet_why))
            reasons.forEach { ReasonRow(it) }
        }
        if (explanation != null) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(explanation, style = Finban.type.body, color = Finban.colors.body)
            if (ai) AiBadge()
        }
        if (imageVerdict != null) ImageVerdictNote(imageVerdict)
        if (linkCheck != null) LinkCheckSection(linkCheck, alreadyShown = reasons.map { it.id }.toSet())
        if (shared != null) SharedQuote(shared)
        if (showDisclaimer) Text(stringResource(R.string.sheet_none_note), style = Finban.type.body, color = Finban.colors.muted)
    }
}

@Composable
private fun SectionHeading(text: String) =
    Text(text, style = Finban.type.bodyStrong, color = Finban.colors.muted, modifier = Modifier.padding(bottom = 4.dp))

@Composable
private fun SeverityBar(level: String) {
    val sc = severityColors(level, Finban.colors)
    val word = when (level) { "high" -> R.string.severity_high; "careful" -> R.string.severity_careful; else -> R.string.severity_none }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(sc.tint, Radii.md).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(SeverityIcons.forLevel(level), contentDescription = null, tint = sc.icon, modifier = Modifier.size(Sizes.icon))
        Text(stringResource(word), style = Finban.type.severity, color = sc.ink)
    }
}

@Composable
private fun ReasonRow(r: Reason) {
    val sc = severityColors(r.level, Finban.colors)
    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(SeverityIcons.forLevel(r.level), contentDescription = null, tint = sc.icon, modifier = Modifier.padding(top = 3.dp).size(Sizes.icon))
        Text(r.text, style = Finban.type.bodyStrong, color = Finban.colors.ink)
    }
}

/** Shown only when source_of_verdict == "rules+llm". The text says what the AI did and did not do. */
@Composable
private fun AiBadge() {
    Box(Modifier.background(Finban.colors.soft, Radii.full).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.sheet_ai_badge), style = Finban.type.caption, color = Finban.colors.body)
    }
    Text(stringResource(R.string.sheet_ai_note), style = Finban.type.caption, color = Finban.colors.muted)
}

/** The text the person shared, so they can see exactly what was checked. Shown on screen only; never logged or stored. */
/** What the picture shows, in the model's words, with the picture that was looked at. A note only: it never changes the warning level. */
@Composable
private fun PictureNote(text: String?, bitmap: android.graphics.Bitmap?) {
    Column(Modifier.fillMaxWidth().background(Finban.colors.soft, Radii.md).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (bitmap != null) PicturePreview(bitmap, scanning = false, maxHeight = 180.dp)
        Text(stringResource(R.string.sheet_picture_label), style = Finban.type.caption, color = Finban.colors.muted)
        // No description of the picture on purpose: the sheet gives a verdict on it (see ImageVerdictNote), not a caption.
    }
}

/** A guess at what the picture shows, from its words. A note only: it never changes the warning level, and it always says it may be wrong. */
@Composable
private fun PictureKindNote(kind: devr.shellshock.finban.ui.share.PictureKind) {
    val title = stringResource(when (kind) {
        devr.shellshock.finban.ui.share.PictureKind.PAYMENT -> R.string.kind_payment
        devr.shellshock.finban.ui.share.PictureKind.AADHAAR -> R.string.kind_aadhaar
        devr.shellshock.finban.ui.share.PictureKind.PAN -> R.string.kind_pan
        devr.shellshock.finban.ui.share.PictureKind.VOTER -> R.string.kind_voter
        devr.shellshock.finban.ui.share.PictureKind.ID_CARD -> R.string.kind_id
    })
    Column(Modifier.fillMaxWidth().background(Finban.colors.soft, Radii.md).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = Finban.type.bodyStrong, color = Finban.colors.ink)
        Text(stringResource(R.string.kind_warning), style = Finban.type.bodyStrong, color = Finban.colors.ink)
        Text(stringResource(if (kind.isId) R.string.kind_id_note else R.string.kind_payment_note), style = Finban.type.body, color = Finban.colors.body)
    }
}

/** The picture being checked: shown in full with a sweep moving over it, then a steady title. */
@Composable
private fun ScanningPicture(bitmap: android.graphics.Bitmap) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PicturePreview(bitmap, scanning = true, maxHeight = 300.dp)
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.scan_picture_title), style = Finban.type.title, color = Finban.colors.ink)
        Spacer(Modifier.size(4.dp))
        Text(stringResource(R.string.scan_picture_sub), style = Finban.type.subtitle, color = Finban.colors.muted)
        Spacer(Modifier.size(16.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Finban.colors.accent, trackColor = Finban.colors.hair)
    }
}

/** The picture at its own proportions (never stretched), with an optional accent sweep over exactly the picture. */
@Composable
private fun PicturePreview(bitmap: android.graphics.Bitmap, scanning: Boolean, maxHeight: androidx.compose.ui.unit.Dp) {
    val accent = Finban.colors.accent
    val ratio = bitmap.width.toFloat() / bitmap.height
    val sweep by androidx.compose.animation.core.rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1700, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "sweep",
    )
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val h = minOf(maxHeight, maxWidth / ratio)
        androidx.compose.foundation.layout.Box(Modifier.size(width = h * ratio, height = h).clip(Radii.sm)) {
            androidx.compose.foundation.Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.sheet_picture_label), modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
            if (scanning) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val y = sweep * size.height
                val band = size.height * 0.22f
                drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.10f))
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(androidx.compose.ui.graphics.Color.Transparent, accent.copy(alpha = 0.38f), androidx.compose.ui.graphics.Color.Transparent),
                        startY = y - band, endY = y + band,
                    ),
                )
                drawLine(accent, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 3.dp.toPx())
            }
        }
    }
}

@Composable
private fun SharedQuote(text: String) {
    Column(Modifier.fillMaxWidth().background(Finban.colors.soft, Radii.md).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.sheet_shared_label), style = Finban.type.caption, color = Finban.colors.muted)
        Text(
            text, style = Finban.type.body, color = Finban.colors.body, maxLines = 8,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}


@Composable
private fun LinkCheckSection(ui: LinkCheckUi, alreadyShown: Set<String>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val st = ui.state) {
            LinkCheckState.Running -> {
                Text(stringResource(R.string.link_check_running), style = Finban.type.bodyStrong, color = Finban.colors.ink)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Finban.colors.accent, trackColor = Finban.colors.hair)
            }
            is LinkCheckState.Done -> {
                // The server repeats the address findings the sheet already shows; list only what is new.
                val fresh = st.response.reasons.filter { it.id !in alreadyShown && it.id != "link.page_unreachable" }
                Text(stringResource(R.string.link_check_found), style = Finban.type.bodyStrong, color = Finban.colors.muted, modifier = Modifier.padding(bottom = 4.dp))
                fresh.forEach { ReasonRow(it) }
                if (fresh.isEmpty()) Text(
                    stringResource(if (st.response.checked.pageReached) R.string.link_check_nothing_new else R.string.link_check_failed),
                    style = Finban.type.body, color = Finban.colors.body,
                )
            }
            else -> {
                if (st == LinkCheckState.Failed) Text(stringResource(R.string.link_check_failed), style = Finban.type.body, color = Finban.colors.body, modifier = Modifier.padding(bottom = 8.dp))
                devr.shellshock.finban.ui.components.SecondaryButton(
                    if (st == LinkCheckState.Failed) stringResource(R.string.link_check_retry) else stringResource(R.string.link_check_button) + "\n" + ui.label,
                    ui.onCheck,
                )
                Text(stringResource(R.string.link_check_note), style = Finban.type.caption, color = Finban.colors.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}


/** The photo itself (not its words): "probably made by a computer" or "couldn't tell". Never "real" or "safe". */
@Composable
private fun ImageVerdictNote(verdict: String) {
    val (text, level) = when (verdict) {
        "pending" -> stringResource(R.string.image_checking) to "none"
        "likely_ai" -> stringResource(R.string.image_likely_ai) to "careful"
        "unlikely_ai" -> stringResource(R.string.image_unlikely_ai) to "none"
        else -> stringResource(R.string.image_unknown) to "none"
    }
    val sc = severityColors(level, Finban.colors)
    Column(Modifier.fillMaxWidth().background(sc.tint, Radii.md).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(SeverityIcons.forLevel(level), contentDescription = null, tint = sc.icon, modifier = Modifier.padding(top = 3.dp).size(Sizes.icon))
            Text(text, style = Finban.type.bodyStrong, color = sc.ink)
        }
        Text(stringResource(R.string.image_sent_note), style = Finban.type.caption, color = sc.ink, modifier = Modifier.padding(start = 38.dp))
    }
}

/** "Tell my trusted person": the elder taps it; nothing is sent otherwise. */
/** The manual "is this picture computer-made?" button, shown when a big picture was on screen. */
class AiCheckUi(val onCheck: () -> Unit)

class AlertUi(val state: devr.shellshock.finban.net.AlertState, val onSend: () -> Unit)

@Composable
private fun AlertSection(ui: AlertUi) {
    val st = ui.state
    Column {
        when (st) {
            is devr.shellshock.finban.net.AlertState.Sent -> Text(stringResource(if (st.pushed > 0) R.string.alert_sent else R.string.alert_saved), style = Finban.type.bodyStrong, color = Finban.colors.ink)
            devr.shellshock.finban.net.AlertState.NoCaretaker -> Text(stringResource(R.string.alert_none), style = Finban.type.body, color = Finban.colors.body)
            else -> {
                if (st == devr.shellshock.finban.net.AlertState.Failed) Text(stringResource(R.string.alert_failed), style = Finban.type.body, color = Finban.colors.body, modifier = Modifier.padding(bottom = 8.dp))
                devr.shellshock.finban.ui.components.SecondaryButton(
                    stringResource(if (st == devr.shellshock.finban.net.AlertState.Sending) R.string.alert_sending else R.string.alert_button),
                    { if (st != devr.shellshock.finban.net.AlertState.Sending) ui.onSend() },
                )
            }
        }
    }
}
