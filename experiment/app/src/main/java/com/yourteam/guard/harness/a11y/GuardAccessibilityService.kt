package com.yourteam.guard.harness.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType

/** OBSERVE ONLY. Never calls performAction / performGlobalAction / dispatchGesture. */
class GuardAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        bubble = com.yourteam.guard.harness.overlay.BubbleController(this)
        if (com.yourteam.guard.harness.rules.Prefs.bubbleEnabled) bubble?.show()
        CaptureNotification.post(this)
        ForegroundTracker.listeners.add(ruleListener)
        callMonitor = com.yourteam.guard.harness.calls.CallMonitor(this).also { it.start() }
        EventLog.log(EventType.SERVICE, "connected", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ForegroundTracker.onWindowState(this, event.packageName?.toString(), event.className?.toString())
        }
    }

    override fun onInterrupt() {
        EventLog.log(EventType.SERVICE, "interrupt", "onInterrupt")
    }

    val sheet by lazy { com.yourteam.guard.harness.overlay.BottomSheetController(this) }

    private val ruleListener: (String, String, Boolean) -> Unit = { p, _, w -> com.yourteam.guard.harness.rules.TriggerRule.onForeground(this, p, w) }

    var callMonitor: com.yourteam.guard.harness.calls.CallMonitor? = null

    var bubble: com.yourteam.guard.harness.overlay.BubbleController? = null

    fun capture(source: String) { CaptureStore.last.value = ScreenCapture.capture(this, source) }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (sheet.isShown) sheet.requestDismiss("service-unbound")
        bubble?.hide(); bubble = null
        CaptureNotification.cancel(this)
        callMonitor?.stop(); callMonitor = null
        ForegroundTracker.listeners.remove(ruleListener)
        instance = null
        EventLog.log(EventType.SERVICE, "unbound", "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile var instance: GuardAccessibilityService? = null
    }
}
