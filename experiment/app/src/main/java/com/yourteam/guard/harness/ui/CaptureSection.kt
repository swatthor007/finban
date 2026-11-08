package com.yourteam.guard.harness.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourteam.guard.harness.a11y.CaptureBus
import com.yourteam.guard.harness.a11y.CaptureStore
import com.yourteam.guard.harness.a11y.Extractors
import com.yourteam.guard.harness.a11y.GuardAccessibilityService
import com.yourteam.guard.harness.rules.Prefs
import java.io.File

private fun color(k: Extractors.Kind) = when (k) {
    Extractors.Kind.URL -> Color(0xFFBBDEFB); Extractors.Kind.PHONE -> Color(0xFFC8E6C9)
    Extractors.Kind.UPI -> Color(0xFFFFE0B2); Extractors.Kind.AMOUNT -> Color(0xFFF8BBD0)
}

@Composable
fun CaptureSection() {
    val ctx: Context = LocalContext.current
    val cap by CaptureStore.last.collectAsState()
    var bubble by remember { mutableStateOf(Prefs.bubbleEnabled) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Screen capture", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Floating bubble", Modifier.weight(1f))
            Switch(bubble, { bubble = it; Prefs.bubbleEnabled = it; GuardAccessibilityService.instance?.bubble?.let { b -> if (it) b.show() else b.hide() } })
        }
        Text("Capture runs against whatever app is in front. Use the bubble, the notification action or the QS tile; the button below captures THIS app (for a sanity check).", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { CaptureBus.request("debug-button") }) { Text("Capture now") }
            Button(onClick = { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                GuardAccessibilityService.instance?.let { it.sheet.show(com.yourteam.guard.harness.overlay.placeholderSheet) }
            }, 5000) }) { Text("Sheet in 5s") }
            val c = cap
            Button(onClick = { c?.let { cp ->
                val f = File(File(ctx.filesDir, "exports").apply { mkdirs() }, "capture-${cp.ts}.json").apply { writeText(cp.toJson().toString(2)) }
                shareFile(ctx, f)
            } }, enabled = c != null) { Text("Export capture") }
        }
        cap?.let { c ->
            Text("Last: ${c.source} fg=${c.foreground} windows=${c.windows.size}", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            c.windows.forEach { w ->
                Text("• ${w.pkg ?: "?"} \"${w.title ?: ""}\" nodes=${w.nodes.size}${if (w.rootNull) " NO-ROOT" else ""}${if (w.active) " ACTIVE" else ""}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Row { Extractors.Kind.entries.forEach { k -> Text(" $k ", Modifier.padding(end = 4.dp), fontSize = 11.sp, style = androidx.compose.ui.text.TextStyle(background = color(k))) } }
            c.allText.distinct().take(150).forEach { t ->
                val ms = Extractors.find(t)
                Text(buildAnnotatedString {
                    var i = 0
                    ms.forEach { m -> append(t.substring(i, m.start)); withStyle(SpanStyle(background = color(m.kind))) { append(t.substring(m.start, m.end)) }; i = m.end }
                    append(t.substring(i))
                }, fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}
