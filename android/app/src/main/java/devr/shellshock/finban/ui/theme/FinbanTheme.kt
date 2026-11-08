package devr.shellshock.finban.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The ONE theme file. Tokens come from the design artifact (scam.css / "Design tokens" board).
 * Deviations from the artifact, forced by the hard rules: body text >= 18sp, 56dp touch targets.
 * Font: system default (Roboto + system Tamil fallback), not Figtree (no Tamil glyphs).
 */
@Immutable
class SeverityColors(val tint: Color, val ink: Color, val icon: Color)

@Immutable
class FinbanColors(
    val canvas: Color, val soft: Color, val strong: Color,
    val ink: Color, val body: Color, val muted: Color,
    val hair: Color, val borderStrong: Color,
    val accent: Color, val emergency: Color, val scrim: Color,
    val high: SeverityColors, val careful: SeverityColors, val none: SeverityColors,
)

private val LightColors = FinbanColors(
    canvas = Color(0xFFFFFFFF), soft = Color(0xFFF7F7F7), strong = Color(0xFFF2F2F2),
    ink = Color(0xFF222222), body = Color(0xFF3F3F3F), muted = Color(0xFF6A6A6A),
    hair = Color(0xFFDDDDDD), borderStrong = Color(0xFFC1C1C1),
    accent = Color(0xFFFF385C), emergency = Color(0xFFE00B41), scrim = Color(0x6B000000),
    high = SeverityColors(Color(0xFFFDECE7), Color(0xFFA8290A), Color(0xFFC13515)),
    careful = SeverityColors(Color(0xFFFFF3D6), Color(0xFF6B4400), Color(0xFFB36B00)),
    none = SeverityColors(Color(0xFFECEFF1), Color(0xFF33414A), Color(0xFF4A5A63)),
)

private val DarkColors = FinbanColors(
    canvas = Color(0xFF1C1C1C), soft = Color(0xFF262626), strong = Color(0xFF2E2E2E),
    ink = Color(0xFFF4F4F4), body = Color(0xFFD9D9D9), muted = Color(0xFFA8A8A8),
    hair = Color(0xFF3D3D3D), borderStrong = Color(0xFF5A5A5A),
    accent = Color(0xFFFF385C), emergency = Color(0xFFE00B41), scrim = Color(0x8C000000),
    high = SeverityColors(Color(0xFF3B1A12), Color(0xFFFFB5A1), Color(0xFFFF8A6B)),
    careful = SeverityColors(Color(0xFF3A2A08), Color(0xFFFFD27A), Color(0xFFF0B84A)),
    none = SeverityColors(Color(0xFF263036), Color(0xFFCFD8DD), Color(0xFF9FB0B9)),
)

object Spacing { val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val base = 16.dp; val lg = 24.dp; val xl = 32.dp }
object Sizes { val touch = 56.dp; val icon = 24.dp; val iconL = 28.dp; val bubble = 56.dp }
object Radii {
    val sm = RoundedCornerShape(8.dp)
    val md = RoundedCornerShape(14.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val full = RoundedCornerShape(percent = 50)
}

@Immutable
class FinbanType(
    val title: TextStyle, val heading: TextStyle, val subtitle: TextStyle, val body: TextStyle, val bodyStrong: TextStyle,
    val button: TextStyle, val severity: TextStyle, val caption: TextStyle, val fieldLabel: TextStyle, val code: TextStyle,
)

// Body text sits right at the hard floor (AGENTS.md: body >= 18sp); everything else is scaled down from the previous
// pass, which read as too large on screen. Captions stay close to 16sp rather than shrinking further: elderly users.
private val Type = FinbanType(
    title = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    // Onboarding step headings: smaller than the sheet's title so a one-line question doesn't dominate a small screen.
    heading = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
    subtitle = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    body = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyStrong = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    button = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    severity = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    caption = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    // Deliberately NOT `body`: OutlinedTextField shrinks the label to ~75% and floats it above the border using this
    // style's own line height. body's old 30sp line height was taller than the shrunk glyphs, so the floated label's
    // box overlapped the text below it. A tight line height keeps the floated label flush with the border.
    fieldLabel = TextStyle(fontSize = 16.sp, lineHeight = 18.sp),
    // Monospace, letter-spaced: a pairing code should read as data, not prose, and digits must not look alike.
    code = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
)

private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalType = staticCompositionLocalOf { Type }

object Finban {
    val colors: FinbanColors @Composable get() = LocalColors.current
    val type: FinbanType @Composable get() = LocalType.current
}

@Composable
fun FinbanTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        background = c.canvas, surface = c.canvas, onBackground = c.ink, onSurface = c.ink,
        primary = c.ink, onPrimary = c.canvas, outline = c.borderStrong, outlineVariant = c.hair,
        surfaceVariant = c.soft, error = c.high.icon,
    )
    val base = Typography()
    val typo = base.copy(
        titleLarge = Type.title, bodyLarge = Type.body, bodyMedium = Type.body, labelLarge = Type.button,
        bodySmall = Type.caption, labelMedium = Type.caption,
    )
    CompositionLocalProvider(LocalColors provides c, LocalType provides Type) {
        devr.shellshock.finban.data.ProvideLanguage {
            MaterialTheme(colorScheme = scheme, typography = typo, shapes = Shapes(), content = content)
        }
    }
}
