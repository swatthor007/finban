package devr.shellshock.finban.data

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

/**
 * The app's own language ([Session.lang]), separate from the phone's. Sheets drawn over other apps and notifications
 * have no Activity to restart, so screens read it through FinbanTheme and code outside Compose uses [context].
 */
object AppLanguage {
    fun locale(lang: String): Locale = Locale(lang, "IN")

    /** Same context, except its strings come from the chosen language. Still reaches the Activity or Service underneath. */
    fun context(base: Context): Context {
        val cfg = Configuration(base.resources.configuration).apply { setLocale(locale(Session.lang)) }
        return Localized(base, base.createConfigurationContext(cfg).resources)
    }

    private class Localized(base: Context, private val res: Resources) : ContextWrapper(base) {
        override fun getResources(): Resources = res
    }
}

@Composable
fun ProvideLanguage(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val lang = Session.lang
    val system = LocalConfiguration.current
    val ctx = remember(base, lang, system) { AppLanguage.context(base) }
    CompositionLocalProvider(
        LocalContext provides ctx, LocalConfiguration provides ctx.resources.configuration, LocalResources provides ctx.resources, content = content,
    )
}
