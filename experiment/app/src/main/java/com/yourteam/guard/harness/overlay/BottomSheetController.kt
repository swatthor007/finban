package com.yourteam.guard.harness.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

typealias SheetContent = @Composable ColumnScope.(dismiss: () -> Unit) -> Unit

/**
 * Bottom sheet in its own FOCUSABLE TYPE_ACCESSIBILITY_OVERLAY window (focus is required to receive BACK).
 * Side effect to watch on devices: a focusable overlay can steal the IME from the app underneath.
 */
class BottomSheetController(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: SheetRoot? = null
    private var owner: OverlayOwner? = null
    private val dismissSignal = mutableIntStateOf(0)
    private var dismissReason = "back"
    val isShown get() = root != null

    private inner class SheetRoot : FrameLayout(svc) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) requestDismiss("back")
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            EventLog.log(EventType.OVERLAY, "sheet-focus", "sheet window focus=$hasWindowFocus")
        }
    }

    fun requestDismiss(reason: String) { dismissReason = reason; dismissSignal.intValue++ }

    fun show(content: SheetContent) {
        if (root != null) return
        val o = OverlayOwner()
        val r = SheetRoot().apply { isFocusableInTouchMode = true; isFocusable = true }
        o.attachTo(r)
        val cv = ComposeView(svc).apply {
            setContent { MaterialTheme { SheetHost(content) } }
        }
        r.addView(cv, FrameLayout.LayoutParams(-1, -1))
        val lp = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        try {
            wm.addView(r, lp)
            root = r; owner = o
            o.resume()
            r.requestFocus()
            EventLog.log(EventType.OVERLAY, "sheet", "sheet shown (fg=${com.yourteam.guard.harness.a11y.ForegroundTracker.pkg})")
        } catch (t: Throwable) {
            EventLog.log(EventType.OVERLAY, "sheet-error", "addView failed: $t")
            o.destroy()
        }
    }

    private fun remove() {
        root?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        owner?.destroy()
        root = null; owner = null
        EventLog.log(EventType.OVERLAY, "sheet", "sheet removed (reason=$dismissReason)")
    }

    @Composable
    private fun SheetHost(content: SheetContent) {
        val scope = rememberCoroutineScope()
        val offset = remember { Animatable(3000f) }
        var height by remember { mutableFloatStateOf(0f) }
        val scrimAlpha = if (height > 0f) (0.5f * (1f - (offset.value / height).coerceIn(0f, 1f))) else 0f
        var removed by remember { mutableStateOf(false) }

        fun dismiss() {
            if (removed) return
            scope.launch {
                offset.animateTo(if (height > 0) height else 3000f, tween(220))
                removed = true; remove()
            }
        }
        val measured = height > 0f
        LaunchedEffect(measured) {
            if (!measured) return@LaunchedEffect
            try {
                offset.snapTo(height)
                offset.animateTo(0f, tween(280))
                EventLog.log(EventType.OVERLAY, "sheet-debug", "slide-in done offset=${offset.value}")
            } catch (t: Throwable) {
                EventLog.log(EventType.OVERLAY, "sheet-debug", "slide-in interrupted: $t offset=${offset.value}"); throw t
            }
        }
        LaunchedEffect(dismissSignal.intValue) { if (dismissSignal.intValue > 0) dismiss() }

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    EventLog.log(EventType.OVERLAY, "sheet-touch", "scrim tapped"); dismissReason = "scrim"; dismiss()
                },
        ) {
            val density = LocalDensity.current
            val nav = WindowInsets.navigationBars.getBottom(density)
            val gest = WindowInsets.systemGestures.getBottom(density)
            val top = WindowInsets.statusBars.getTop(density)
            LaunchedEffect(nav, gest, top) { EventLog.log(EventType.OVERLAY, "sheet-insets", "navBar.bottom=$nav gestures.bottom=$gest statusBar.top=$top") }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .offset { IntOffset(0, offset.value.roundToInt()) }
                    .onSizeChanged { height = it.height.toFloat() }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceAtLeast(0f)) } },
                        onDragStopped = { v ->
                            if (offset.value > height * 0.3f || v > 1500f) { dismissReason = "drag"; dismiss() }
                            else offset.animateTo(0f, tween(180))
                        },
                    )
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { /* swallow taps */ },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                shadowElevation = 16.dp,
            ) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
                    Box(Modifier.padding(vertical = 10.dp).align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp)
                        .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
                    content { dismissReason = "button"; dismiss() }
                }
            }
        }
    }
}
