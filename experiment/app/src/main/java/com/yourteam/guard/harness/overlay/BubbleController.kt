package com.yourteam.guard.harness.overlay

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
import android.widget.TextView
import com.yourteam.guard.harness.a11y.CaptureBus
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType
import kotlin.math.abs

/** Draggable edge-snapping bubble in a TYPE_ACCESSIBILITY_OVERLAY window (no SYSTEM_ALERT_WINDOW). */
class BubbleController(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    val isShown get() = view != null

    fun show() {
        if (view != null) return
        val d = svc.resources.displayMetrics.density
        val size = (56 * d).toInt()
        val v = TextView(svc).apply {
            text = "🛡"; textSize = 24f; gravity = Gravity.CENTER; alpha = 0.9f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#1565C0")) }
            elevation = 8 * d
            contentDescription = "Guard: scan this screen"
        }
        val p = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = wm.currentWindowMetrics.bounds.width() - size; y = (200 * d).toInt()
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
                MotionEvent.ACTION_UP -> if (moved) snap(v, p, size) else { EventLog.log(EventType.OVERLAY, "bubble", "bubble tapped"); CaptureBus.request("bubble") }
            }
            true
        }
        try { wm.addView(v, p); view = v; lp = p; EventLog.log(EventType.OVERLAY, "bubble", "bubble shown") }
        catch (t: Throwable) { EventLog.log(EventType.OVERLAY, "bubble-error", "addView failed: $t") }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        view = null; lp = null
        EventLog.log(EventType.OVERLAY, "bubble", "bubble hidden")
    }

    private fun update(v: View, p: WindowManager.LayoutParams) { try { wm.updateViewLayout(v, p) } catch (_: Throwable) {} }

    private fun snap(v: View, p: WindowManager.LayoutParams, size: Int) {
        val b = wm.currentWindowMetrics.bounds
        val target = if (p.x + size / 2 < b.width() / 2) 0 else b.width() - size
        p.y = p.y.coerceIn(0, b.height() - size)
        ValueAnimator.ofInt(p.x, target).apply {
            duration = 180
            addUpdateListener { p.x = it.animatedValue as Int; if (v.isAttachedToWindow) update(v, p) }
        }.start()
    }
}
