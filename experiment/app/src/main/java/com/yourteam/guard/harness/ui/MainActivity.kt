package com.yourteam.guard.harness.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yourteam.guard.harness.diagnostics.Status
import com.yourteam.guard.harness.log.Event
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { Home() } } }
    }
}

@Composable
private fun Home() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) } // bump to re-evaluate statuses
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val events by EventLog.observe().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf<String?>(null) }
    var showGuide by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++; com.yourteam.guard.harness.a11y.GuardAccessibilityService.instance?.callMonitor?.ensureTelephony()
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        item { Text("Guard Harness – Diagnostics", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp)) }

        item {
            key(tick) {
                val enabled = Status.a11yEnabled(ctx)
                CheckRow("Accessibility service", if (enabled) (if (Status.a11yConnected()) "enabled, connected" else "enabled, not connected") else "OFF", enabled) {
                    Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open settings") }
                    TextButton(onClick = { showGuide = !showGuide }) { Text("Restricted settings guide") }
                }
                if (showGuide) Text(RESTRICTED_GUIDE, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                val ta by com.yourteam.guard.harness.tts.TtsManager.tamilOk.collectAsState()
                CheckRow("Tamil TTS (ta-IN)", if (ta) "available" else "not available / unchecked", ta) { Text("see TTS section below", fontSize = 12.sp) }
                val nl = Status.notifListenerEnabled(ctx)
                CheckRow("Notification listener", if (nl) "enabled" else "OFF", nl) {
                    Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Open settings") }
                }
                val role = Status.screeningRoleHeld(ctx)
                CheckRow("Call screening role", if (role) "held" else "not held", role) {
                    Button(onClick = { roleLauncher.launch(ctx.getSystemService(android.app.role.RoleManager::class.java).createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING)) }, enabled = !role) { Text("Request role") }
                    TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) { Text("Default apps") }
                }
                val missing = Status.runtimePerms.filterNot { Status.granted(ctx, it) }
                CheckRow("Runtime permissions", if (missing.isEmpty()) "all granted" else "missing: " + missing.joinToString { it.substringAfterLast('.') }, missing.isEmpty()) {
                    Button(onClick = { permLauncher.launch(missing.toTypedArray()) }, enabled = missing.isNotEmpty()) { Text("Request") }
                    TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }) { Text("App settings") }
                }
            }
        }

        item { CaptureSection() }
        item { TtsSection() }
        item { RuleSection() }
        item { SmsSection() }
        item { WatchlistSection() }

        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                Text("Event timeline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = {
                    scope.launch {
                        val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
                        val f = File(dir, "log-${System.currentTimeMillis()}.json").apply { writeText(EventLog.exportJson()) }
                        shareFile(ctx, f)
                    }
                }) { Text("Export log") }
                TextButton(onClick = { EventLog.clear() }) { Text("Clear") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("all") })
                EventType.ALL.forEach { t -> FilterChip(selected = filter == t, onClick = { filter = t }, label = { Text(t) }) }
            }
        }
        items(events.filter { filter == null || it.type == filter }, key = { it.id }) { EventRow(it) }
    }
}

@Composable
private fun CheckRow(title: String, status: String, ok: Boolean, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text((if (ok) "✅ " else "❌ ") + title + " — " + status, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun EventRow(e: Event) {
    Text("${fmt.format(Date(e.ts))} [${e.type}/${e.tag}] ${e.message}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
}

internal fun shareFile(ctx: android.content.Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private const val RESTRICTED_GUIDE = """Android 13+ blocks accessibility for APKs installed from a browser or file manager ("Restricted setting"):
1. Open Settings → Apps → Guard Harness (long-press the app icon → App info).
2. Tap ⋮ (top right) → "Allow restricted settings" and confirm with your PIN/biometric. (If ⋮ is missing, first try to enable the service once; the entry appears after a blocked attempt.)
3. Go back to Settings → Accessibility → Installed apps → Guard Harness (test) and turn it on.
Installing with adb avoids this restriction."""
