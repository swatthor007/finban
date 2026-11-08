package devr.shellshock.finban.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import devr.shellshock.finban.a11y.CaptureBus
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import kotlin.math.abs

/** Draggable edge-snapping bubble in a TYPE_ACCESSIBILITY_OVERLAY window (no SYSTEM_ALERT_WINDOW). */
class BubbleController(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var view: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    val isShown get() = view != null

    // The bubble steps aside while a result sheet is open and while Finban's own screens are in front.
    private var sheetOpen = false
    private var inOwnApp = false
    fun setSheetOpen(open: Boolean) { if (sheetOpen != open) { sheetOpen = open; applyVisibility() } }
    fun setInOwnApp(own: Boolean) { if (inOwnApp != own) { inOwnApp = own; applyVisibility() } }

    private fun applyVisibility() {
        val v = view ?: return
        val visible = !sheetOpen && !inOwnApp
        v.animate().cancel()
        if (visible) {
            if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE }
            v.animate().alpha(1f).setDuration(200).start()
        } else {
            v.animate().alpha(0f).setDuration(120).withEndAction { if (sheetOpen || inOwnApp) v.visibility = View.GONE }.start()
        }
    }

    /** Centre of the bubble in screen pixels (the scan wave starts here), or null when it is hidden (e.g. turned off in Settings). */
    fun centerPx(): Pair<Float, Float>? {
        val p = lp ?: return null; val v = view ?: return null
        return (p.x + v.width / 2f) to (p.y + v.height / 2f)
    }

    fun show() {
        if (view != null) return
        val d = svc.resources.displayMetrics.density
        val disc = (56 * d).toInt()              // the visible circle: 56 dp (hard rule for tap targets)
        val halo = (14 * d).toInt()              // transparent margin around it that holds the faint idle glow
        val size = disc + 2 * halo
        val inset = halo
        val v = ImageView(svc).apply {
            setImageResource(devr.shellshock.finban.R.drawable.ic_bubble_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = halo + (14 * d).toInt(); setPadding(pad, pad, pad, pad)   // mark stays 28 dp inside the disc
            // Flat accent disc + a very faint halo (about 12 % at its brightest). The scan wave grows from this glow on tap.
            val glow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x1FFF385C, 0x00FF385C)).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = size / 2f; shape = GradientDrawable.OVAL
            }
            val disk = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF385C")) }
            background = android.graphics.drawable.LayerDrawable(arrayOf(glow, disk)).apply { setLayerInset(1, halo, halo, halo, halo) }
            elevation = 0f
            contentDescription = devr.shellshock.finban.data.AppLanguage.context(svc).getString(devr.shellshock.finban.R.string.bubble_desc)
        }
        val p = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = wm.currentWindowMetrics.bounds.width() - size + inset; y = (200 * d).toInt()
        }
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) { p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); update(v, p) }
                }
                MotionEvent.ACTION_UP -> if (moved) snap(v, p, size, inset) else { EventLog.log(EventType.OVERLAY, "bubble", "bubble tapped"); CaptureBus.request("bubble") }
            }
            true
        }
        try { wm.addView(v, p); view = v; lp = p; applyVisibility(); EventLog.log(EventType.OVERLAY, "bubble", "bubble shown") }
        catch (t: Throwable) { EventLog.log(EventType.OVERLAY, "bubble-error", "addView failed: $t") }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        view = null; lp = null
        EventLog.log(EventType.OVERLAY, "bubble", "bubble hidden")
    }

    private fun update(v: View, p: WindowManager.LayoutParams) { try { wm.updateViewLayout(v, p) } catch (_: Throwable) {} }

    private fun snap(v: View, p: WindowManager.LayoutParams, size: Int, inset: Int) {
        val b = wm.currentWindowMetrics.bounds
        val target = if (p.x + size / 2 < b.width() / 2) -inset else b.width() - size + inset   // the disc, not its glow margin, touches the edge
        p.y = p.y.coerceIn(-inset, b.height() - size + inset)
        ValueAnimator.ofInt(p.x, target).apply {
            duration = 180
            addUpdateListener { p.x = it.animatedValue as Int; if (v.isAttachedToWindow) update(v, p) }
        }.start()
    }
}
