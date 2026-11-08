package devr.shellshock.finban.net

import devr.shellshock.finban.BuildConfig
import devr.shellshock.finban.data.Session

/** Server address: the build default (permanent ngrok domain, or android/local.properties override) unless Developer tools set one on this phone. */
object ServerConfig {
    val default: String get() = BuildConfig.SERVER_URL

    // Addresses from earlier setups (rotating ngrok, adb reverse, emulator loopback) are dropped once so installs move to the default.
    private val STALE = listOf("ngrok-free.app", "127.0.0.1", "10.0.2.2")

    var url: String
        get() {
            val saved = Session.prefs.getString("server_url", null)?.takeIf { it.isNotBlank() } ?: return default
            if (STALE.any { saved.contains(it) } && default != saved) {
                // Skip when the build default is itself a stale-looking address (local override for emulator work).
                if (STALE.none { default.contains(it) }) { reset(); return default }
            }
            return saved
        }
        set(v) { Session.prefs.edit().putString("server_url", v.trim().trimEnd('/')).apply() }
    fun reset() { Session.prefs.edit().remove("server_url").apply() }
}
