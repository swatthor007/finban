package devr.shellshock.finban.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Reading the screen" effect: a wavy band of grain sweeps down the whole screen, over any app. Not touchable and not
 * focusable, so the app underneath is untouched; it only shows that Finban is looking. Removed when the answer is ready.
 */
class ScanOverlayController(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var owner: OverlayOwner? = null

    /** [originX]/[originY]: where the wave starts (the bubble). Null = middle of the screen. */
    fun show(originX: Float? = null, originY: Float? = null) {
        if (root != null) return
        val o = OverlayOwner()
        val r = FrameLayout(svc)
        o.attachTo(r)
        r.addView(ComposeView(svc).apply { setContent { ScanEffect(originX, originY) } }, FrameLayout.LayoutParams(-1, -1))
        val lp = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try { wm.addView(r, lp); root = r; owner = o; o.resume() }
        catch (t: Throwable) { EventLog.log(EventType.OVERLAY, "scan-error", "addView failed: ${t.javaClass.simpleName}"); o.destroy() }
    }

    fun hide() {
        root?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        owner?.destroy()
        root = null; owner = null
    }
}

private class Grain(val x: Float, val y: Float, val size: Float, val phase: Float)

private val Accent = Color(0xFFFF385C)

/** Slow start, quick middle, soft landing: the wave gathers itself at the bubble, then opens out across the screen. */
private val WaveEase = CubicBezierEasing(0.55f, 0.0f, 0.30f, 1.0f)

/**
 * Siri-style invocation wave. It starts as a small glow on the bubble, and a ring of grain grows out of it with inertia
 * (slow at first, then expanding fast and widening as it goes). It repeats until the answer is ready.
 */
@Composable
private fun ScanEffect(originX: Float?, originY: Float?) {
    val grain = remember { Random(7).let { r -> List(3200) { Grain(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
    val tr = rememberInfiniteTransition(label = "scan")
    val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = WaveEase), RepeatMode.Restart), label = "wave")
    val twinkle by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Restart), label = "twinkle")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val o = Offset(originX ?: (w / 2f), originY ?: (h / 2f))
        val dens = density
        // reach the farthest corner, so the wave covers the whole screen
        val maxR = maxOf(hypot(o.x, o.y), hypot(w - o.x, o.y), hypot(o.x, h - o.y), hypot(w - o.x, h - o.y)) * 1.08f
        val r = p * maxR
        val width = 36f * dens + r * 0.22f                                   // the ring widens as it travels
        val fade = 1f - ((p - 0.82f) / 0.18f).coerceIn(0f, 1f)               // melts away at the edges before the next wave
        // The source glow starts at the bubble's faint idle level and GROWS after the tap (ease-in), peaks while the ring
        // gathers, then hands over to the ring. It never starts at full strength.
        val idle = 0.12f
        val rise = (p / 0.32f).coerceIn(0f, 1f)
        val grow = rise * rise * (3f - 2f * rise)                                   // smoothstep
        val hand = 1f - ((p - 0.32f) / 0.38f).coerceIn(0f, 1f)                     // fades while the ring leaves
        val strength = if (p < 0.32f) idle + (1f - idle) * grow else hand
        val gr = (62f + 66f * grow) * dens                                          // and the glow widens as it strengthens
        drawCircle(
            Brush.radialGradient(listOf(Accent.copy(alpha = 0.5f * strength), Color.Transparent), center = o, radius = gr),
            radius = gr, center = o,
        )
        if (r > 1f) drawCircle(
            Brush.radialGradient(
                0.0f to Color.Transparent, ((r - width) / (r + width)).coerceIn(0f, 0.98f) to Color.Transparent,
                (r / (r + width)).coerceIn(0f, 0.99f) to Accent.copy(alpha = 0.16f * fade), 1f to Color.Transparent,
                center = o, radius = r + width,
            ),
            radius = r + width, center = o,
        )
        for (g in grain) {
            val x = g.x * w; val y = g.y * h
            val dx = x - o.x; val dy = y - o.y
            val dist = hypot(dx, dy)
            val edge = r + sin(atan2(dy, dx) * 7f + p * 14f) * width * 0.22f  // the ring edge ripples like a wave
            val d = abs(dist - edge) / width
            if (d >= 1f) continue
            val falloff = (1f - d) * (1f - d)
            val tw = 0.6f + 0.4f * sin((twinkle + g.phase) * 2f * PI.toFloat())
            val a = (falloff * tw * fade).coerceIn(0f, 1f)
            drawCircle(
                color = if (g.size > 0.72f) Color.White.copy(alpha = a * 0.95f) else Accent.copy(alpha = a * 0.9f),
                radius = (1.1f + g.size * 1.9f) * dens, center = Offset(x, y),
            )
        }
    }
}
