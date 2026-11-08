package devr.shellshock.finban.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import devr.shellshock.finban.data.Role
import devr.shellshock.finban.data.Session
import devr.shellshock.finban.ui.caretaker.AlertDetail
import devr.shellshock.finban.ui.caretaker.CaretakerHome
import devr.shellshock.finban.ui.home.HomeScreen
import devr.shellshock.finban.ui.home.PaidScreen
import devr.shellshock.finban.ui.nav.NavHost
import devr.shellshock.finban.ui.nav.Screen
import devr.shellshock.finban.ui.nav.rememberNavigator
import devr.shellshock.finban.ui.onboarding.LanguageScreen
import devr.shellshock.finban.ui.onboarding.PermissionsScreen
import devr.shellshock.finban.ui.onboarding.ProtectedScreen
import devr.shellshock.finban.ui.onboarding.RoleScreen
import devr.shellshock.finban.ui.onboarding.TrustedSetupScreen
import devr.shellshock.finban.ui.onboarding.WelcomeScreen
import devr.shellshock.finban.ui.settings.PrivacyScreen
import devr.shellshock.finban.ui.settings.SettingsScreen
import devr.shellshock.finban.ui.settings.TrustedScreen
import devr.shellshock.finban.ui.theme.FinbanTheme
import kotlinx.coroutines.launch

/** Hosts every in-app screen from the design artifact. Start: onboarding until a role exists, then the role's home. */
class MainActivity : ComponentActivity() {
    /** Server address from a finban://server?url=... link or an `--es server_url ...` intent extra (dev/test, before pairing). */
    private fun applyServerFromIntent(i: android.content.Intent?) {
        val url = i?.data?.takeIf { it.scheme == "finban" && it.host == "server" }?.getQueryParameter("url") ?: i?.getStringExtra("server_url")
        if (url.isNullOrBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            android.widget.Toast.makeText(this, "Server address must start with http:// or https://", android.widget.Toast.LENGTH_LONG).show(); return
        }
        devr.shellshock.finban.net.ServerConfig.url = url
        android.widget.Toast.makeText(this, "Server set to ${devr.shellshock.finban.net.ServerConfig.url}", android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        applyServerFromIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyServerFromIntent(intent)
        lifecycleScope.launch { runCatching { Session.refreshToken() } }
        setContent {
            FinbanTheme {
                val role = Session.role
                val start = when {
                    role == null || !Session.onboarded -> Screen.Welcome
                    role == Role.CARETAKER -> Screen.CaretakerHome
                    else -> Screen.Home
                }
                val nav = rememberNavigator(start)
                // Push tap -> alert detail (FinbanMessagingService puts alert_id in the intent; wired in phase G).
                NavHost(nav) { s ->
                    when (s) {
                        Screen.Welcome -> WelcomeScreen(onNext = { nav.push(Screen.Language) }, onDev = { nav.push(Screen.Debug) })
                        Screen.Language -> LanguageScreen(onBack = { nav.back() }) { nav.push(Screen.Role) }
                        Screen.Role -> RoleScreen(onBack = { nav.back() }) { r ->
                            if (r == Role.ELDER) nav.push(Screen.TrustedSetup) else { Session.onboarded = true; nav.reset(Screen.CaretakerHome) }
                        }
                        Screen.TrustedSetup -> TrustedSetupScreen(onBack = { nav.back() }) { nav.push(Screen.Permissions) }
                        Screen.Permissions -> PermissionsScreen(onBack = { nav.back() }) { nav.push(Screen.Protected) }
                        Screen.Protected -> ProtectedScreen { Session.onboarded = true; nav.reset(Screen.Home) }
                        Screen.Home -> HomeScreen(onSettings = { nav.push(Screen.Settings) }, onPaid = { nav.push(Screen.Paid) }, onOpenCheck = { nav.push(Screen.CheckDetail(it)) })
                        Screen.Settings -> SettingsScreen({ nav.back() }, { nav.push(Screen.Trusted) }, { nav.push(Screen.Privacy) }, { nav.push(Screen.Debug) })
                        Screen.Debug -> devr.shellshock.finban.ui.settings.DebugScreen { nav.back() }
                        Screen.Privacy -> PrivacyScreen { nav.back() }
                        Screen.Trusted -> TrustedScreen { nav.back() }
                        Screen.Paid -> PaidScreen { nav.back() }
                        is Screen.CheckDetail -> devr.shellshock.finban.ui.home.CheckDetailScreen(s.id) { nav.back() }
                        Screen.CaretakerHome -> CaretakerHome(onAlert = { id -> nav.push(Screen.AlertDetail(id)) }, onDebug = { nav.push(Screen.Debug) })
                        is Screen.AlertDetail -> AlertDetail(s.alertId) { nav.back() }
                    }
                }
            }
        }
    }
}
