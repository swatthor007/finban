package devr.shellshock.finban.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import devr.shellshock.finban.data.RecentChecks
import devr.shellshock.finban.data.Session
import devr.shellshock.finban.diagnostics.Status
import devr.shellshock.finban.net.AnalyzeRequest
import devr.shellshock.finban.net.Api
import devr.shellshock.finban.net.ServerConfig
import devr.shellshock.finban.sms.SmsCheck
import devr.shellshock.finban.ui.components.AppIcons
import devr.shellshock.finban.ui.components.AppTextField
import devr.shellshock.finban.ui.components.ScreenScaffold
import devr.shellshock.finban.ui.components.SecondaryButton
import devr.shellshock.finban.ui.components.SectionLabel
import devr.shellshock.finban.ui.components.SeverityIcons
import devr.shellshock.finban.ui.components.TopBar
import devr.shellshock.finban.ui.onboarding.accessibilityOn
import devr.shellshock.finban.ui.share.downscaledJpeg
import devr.shellshock.finban.ui.theme.Finban
import devr.shellshock.finban.ui.theme.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Developer tools: set the server address on this phone, check what the server has configured, run each live check once
 * (text/AI, link, photo, sign-in), see which phone permissions are missing, and simulate a scam text for demos.
 * Results are plain lines with a tick or cross and the time taken. Nothing here is shown to the elder by default.
 */
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(ServerConfig.url) }
    val results = remember { androidx.compose.runtime.mutableStateMapOf<String, Pair<Boolean?, String>>() } // key -> (ok?, text); null = running
    var tick by remember { mutableIntStateOf(0) }

    /** Runs [block] off the main thread, shows "Running…" then a tick/cross + the time taken. The block returns the result text or throws. */
    fun probe(key: String, block: suspend () -> String) {
        results[key] = null to "Running…"
        scope.launch {
            val t0 = System.currentTimeMillis()
            results[key] = try {
                val text = withContext(Dispatchers.IO) { block() }
                true to "$text · ${System.currentTimeMillis() - t0} ms"
            } catch (e: Exception) { false to "${e.javaClass.simpleName}: ${e.message ?: ""}".take(160) }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) probe("image") {
            val bytes = downscaledJpeg(ctx, uri) ?: error("could not read the photo")
            when (val v = Api.client.checkImage(bytes)) {
                "likely_ai" -> "Looks computer-made (likely_ai)"
                "unlikely_ai" -> "No sign it is computer-made (unlikely_ai)"
                else -> "Could not tell ($v). Normal if SightEngine keys are not set on the server."
            }
        }
    }

    ScreenScaffold {
        TopBar("Developer tools", onBack)

        SectionLabel("Server")
        AppTextField(url, { url = it }, "Server address", supporting = "Example: http://192.168.1.20:8000 (build default: ${ServerConfig.default})")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { SecondaryButton("Save", { ServerConfig.url = url; results["save"] = true to "Saved. Used by every check from now on." }) }
            Box(Modifier.weight(1f)) { SecondaryButton("Use default", { ServerConfig.reset(); url = ServerConfig.url; results["save"] = true to "Back to the build default." }) }
        }
        Line(results["save"])
        Test("Test connection", results["conn"]) { probe("conn") { ServerConfig.url = url; val h = Api.client.health(); "Reachable: $h" } }
        Test("Check what the server has set up", results["status"]) {
            probe("status") {
                val s = Api.client.debugStatus()
                "v${s.version} · AI explanation: ${if (s.llmConfigured) "ON (${s.llmModel})" else "OFF (rules only)"} · photo check: ${if (s.imageConfigured) "ON" else "OFF"} · " +
                    "link check: ${if (s.linkFetch) "ON" else "OFF"} · domain age: ${if (s.domainAge) "ON" else "OFF"}${if (s.demoMode) " · DEMO MODE" else ""}"
            }
        }

        SectionLabel("Run each check once")
        Test("Text check (is the AI writing the explanation?)", results["text"]) {
            probe("text") {
                val r = Api.client.analyze(AnalyzeRequest(source = "share", text = SAMPLE, links = listOf("http://sbi-kyc-update.xyz/login")))
                "${r.severity.uppercase()} · pattern ${r.patternId} · ${if (r.aiAssisted) "AI wrote the explanation" else "rules only, no AI"}"
            }
        }
        Test("Link check", results["link"]) {
            probe("link") { val r = Api.client.checkLink("https://example.com"); "${r.severity} · page reached: ${r.checked.pageReached}" }
        }
        Test("Photo check (pick a photo)", results["image"]) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        Test("Sign-in and push token", results["fb"]) {
            probe("fb") {
                val uid = Session.ensureSignedIn()
                val token = FirebaseMessaging.getInstance().token.let { com.google.android.gms.tasks.Tasks.await(it) }
                if (Session.role != null) Session.saveToken(token)
                "uid ${uid.take(6)}… · role ${Session.role?.wire ?: "not chosen"} · push token ${token.take(8)}… ${if (Session.role != null) "saved" else "(not saved: no role)"}"
            }
        }

        SectionLabel("This phone")
        androidx.compose.runtime.key(tick) {
            val pkg = ctx.packageName
            Check("Accessibility (bubble + screen check)", accessibilityOn(ctx)) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Check("Notification access (reads SMS notifications)", NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(pkg)) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Check("Notifications allowed", NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val missing = listOf(Manifest.permission.RECEIVE_SMS to "Receive SMS", Manifest.permission.READ_CONTACTS to "Contacts (skip saved senders)", Manifest.permission.READ_PHONE_STATE to "Phone state (call detection)")
            missing.forEach { (perm, label) -> Check(label, Status.granted(ctx, perm)) { permLauncher.launch(arrayOf(perm)) } }
            Check("Battery: not restricted (push arrives on time)", ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)) {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        Text("Android 13+: if Accessibility is greyed out, open Settings > Apps > Finban > ⋮ > Allow restricted settings, then try again.", style = Finban.type.caption, color = Finban.colors.muted)
        SecondaryButton("Refresh", { tick++ })

        if (devr.shellshock.finban.BuildConfig.DEBUG) {
            SectionLabel("Payment pause (debug builds only)")
            var payPkg by remember { mutableStateOf("") }
            var payApps by remember { mutableStateOf(Session.debugPaymentApps) }
            Text("Call screening role: ${if (devr.shellshock.finban.calls.CallScreeningRole.held(ctx)) "held" else "not held"} · ${devr.shellshock.finban.calls.CallStateTracker.describe()}", style = Finban.type.caption, color = Finban.colors.muted)
            AppTextField(payPkg, { payPkg = it }, "Treat this app as a payment app", supporting = "Package name, e.g. com.android.settings. Extra apps now: ${payApps.joinToString().ifBlank { "none" }}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { SecondaryButton("Add", { if (payPkg.isNotBlank()) { Session.debugPaymentApps = payApps + payPkg.trim(); payApps = Session.debugPaymentApps; payPkg = "" } }) }
                Box(Modifier.weight(1f)) { SecondaryButton("Clear all", { Session.debugPaymentApps = emptySet(); payApps = emptySet() }) }
            }
        }

        SectionLabel("Demo")
        Test("Simulate a scam text (shows the popup)", results["sms"]) {
            results["sms"] = true to "Sent. The popup should appear within a few seconds."
            scope.launch(Dispatchers.IO) { SmsCheck.check(ctx.applicationContext, "+919800000000", SAMPLE + " Ref ${System.currentTimeMillis() % 100000}", fromNotification = false) }
        }
        Test("Clear the Home log", results["clear"]) { RecentChecks.clear(); results["clear"] = true to "Cleared. Reopen Home to see it." }
    }
}

private const val SAMPLE = "Dear customer, your SBI account will be blocked today. Update KYC immediately at http://sbi-kyc-update.xyz/login and share the OTP you receive with our officer."

@Composable
private fun Test(label: String, result: Pair<Boolean?, String>?, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SecondaryButton(label, onClick)
        Line(result)
    }
}

/** Tick or cross + the line, so a result is never colour alone. */
@Composable
private fun Line(r: Pair<Boolean?, String>?) {
    if (r == null) return
    val tone = when (r.first) { true -> Finban.colors.none; false -> Finban.colors.high; else -> Finban.colors.none }
    Row(Modifier.fillMaxWidth().background(tone.tint, Radii.sm).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(when (r.first) { true -> AppIcons.Check; false -> AppIcons.Close; else -> SeverityIcons.None }, null, tint = tone.icon, modifier = Modifier.size(20.dp))
        Text(r.second, style = Finban.type.caption, color = tone.ink)
    }
}

@Composable
private fun Check(label: String, ok: Boolean, fix: () -> Unit) {
    val tone = if (ok) Finban.colors.none else Finban.colors.high
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (ok) AppIcons.Check else AppIcons.Close, if (ok) "OK" else "Missing", tint = tone.icon, modifier = Modifier.size(22.dp))
        Text(label, style = Finban.type.body, color = Finban.colors.ink, modifier = Modifier.weight(1f))
        if (!ok) Box(Modifier.width(100.dp).size(width = 100.dp, height = 56.dp)) { SecondaryButton("Fix", fix) }
    }
}
