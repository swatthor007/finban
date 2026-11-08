package devr.shellshock.finban.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import devr.shellshock.finban.calls.CallMonitor
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import devr.shellshock.finban.overlay.BottomSheetController
import devr.shellshock.finban.overlay.BubbleController
import devr.shellshock.finban.overlay.ScreenCheck

/** OBSERVE ONLY. Never calls performAction / performGlobalAction / dispatchGesture. */
class FinbanAccessibilityService : AccessibilityService() {
    val sheet by lazy { BottomSheetController(this) }
    val screenCheck by lazy { ScreenCheck(this) }
    var bubble: BubbleController? = null
    var callMonitor: CallMonitor? = null
    private var trigger: devr.shellshock.finban.calls.TriggerRule? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        bubble = BubbleController(this).also { if (devr.shellshock.finban.data.Session.bubbleOn) it.show() }
        callMonitor = CallMonitor(this).also { it.start() }
        trigger = devr.shellshock.finban.calls.TriggerRule(this).also { it.start() }
        EventLog.log(EventType.SERVICE, "connected", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = event.packageName?.toString()
            ForegroundTracker.onWindowState(this, p, event.className?.toString())
            // No bubble on Finban's own screens. Our overlay windows (sheet, scan) also report our package, so only
            // real activities count (class name inside our package); another app in front (not system UI / keyboard) brings it back.
            if (p == packageName) { if (event.className?.startsWith(packageName) == true) bubble?.setInOwnApp(true) }
            else if (p != null && ForegroundTracker.pkg == p) bubble?.setInOwnApp(false)
        }
    }

    override fun onInterrupt() { EventLog.log(EventType.SERVICE, "interrupt", "onInterrupt") }

    /** Bubble tap: scan effect, read the screen, /analyze, result sheet. */
    fun capture(source: String) { screenCheck.start(source) }

    /** Settings toggle: show or hide the floating bubble right away. */
    fun setBubble(on: Boolean) { bubble?.let { if (on) it.show() else it.hide() } }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (sheet.isShown) sheet.requestDismiss("service-unbound")
        screenCheck.stop(); bubble?.hide(); bubble = null
        callMonitor?.stop(); callMonitor = null
        trigger?.stop(); trigger = null
        instance = null
        EventLog.log(EventType.SERVICE, "unbound", "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    companion object { @Volatile var instance: FinbanAccessibilityService? = null }
}
