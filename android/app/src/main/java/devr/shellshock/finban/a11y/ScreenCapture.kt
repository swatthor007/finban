package devr.shellshock.finban.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class NodeRec(
    val pkg: String, val cls: String, val viewId: String?, val text: String?, val desc: String?,
    val bounds: Rect, val sensitive: Boolean, val password: Boolean,
)

data class WindowRec(val pkg: String?, val title: String?, val type: Int, val layer: Int, val active: Boolean,
                     val rootNull: Boolean, val nodes: List<NodeRec>)

data class Capture(val ts: Long, val source: String, val foreground: String?, val windows: List<WindowRec>) {
    val allText: List<String> get() = windows.flatMap { w -> w.nodes.flatMap { listOfNotNull(it.text, it.desc) } }
    fun toJson(): JSONObject {
        val ex = JSONArray()
        allText.distinct().forEach { t -> Extractors.find(t).forEach { ex.put(JSONObject().put("kind", it.kind.name).put("text", it.text)) } }
        return JSONObject().put("ts", ts).put("source", source).put("foreground", foreground)
            .put("device", Build.MANUFACTURER + " " + Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("extracted", ex)
            .put("windows", JSONArray().also { wa -> windows.forEach { w ->
                wa.put(JSONObject().put("pkg", w.pkg).put("title", w.title).put("type", w.type).put("layer", w.layer)
                    .put("active", w.active).put("rootNull", w.rootNull)
                    .put("nodes", JSONArray().also { na -> w.nodes.forEach { n ->
                        na.put(JSONObject().put("class", n.cls).put("viewId", n.viewId).put("text", n.text).put("desc", n.desc)
                            .put("bounds", n.bounds.flattenToString()).put("sensitive", n.sensitive).put("password", n.password))
                    } }))
            } })
    }
}

object CaptureStore { val last = MutableStateFlow<Capture?>(null) }

/** Entry point for all capture triggers (bubble, notification action, QS tile). */
object CaptureBus {
    fun request(source: String) {
        val svc = FinbanAccessibilityService.instance
        if (svc == null) { EventLog.log(EventType.CAPTURE, "no-service", "Capture requested via $source but accessibility service is not running"); return }
        svc.capture(source)
    }
}

/** READ ONLY tree walk. */
object ScreenCapture {
    private const val MAX_NODES = 4000

    fun capture(svc: AccessibilityService, source: String): Capture {
        val wins = ArrayList<WindowRec>()
        val list = try { svc.windows } catch (e: Exception) { emptyList() }
        for (w in list) {
            val root = w.root
            val pkg = root?.packageName?.toString()
            val rec = WindowRec(pkg, w.title?.toString(), w.type, w.layer, w.isActive, root == null, root?.let { walk(it) } ?: emptyList())
            wins += rec
            @Suppress("DEPRECATION") root?.recycle()
            @Suppress("DEPRECATION") w.recycle()
        }
        if (wins.none { it.nodes.isNotEmpty() }) {
            svc.rootInActiveWindow?.let { r ->
                wins += WindowRec(r.packageName?.toString(), "rootInActiveWindow", -1, -1, true, false, walk(r))
                @Suppress("DEPRECATION") r.recycle()
            }
        }
        val cap = Capture(System.currentTimeMillis(), source, ForegroundTracker.pkg, wins)
        report(cap)
        return cap
    }

    private fun walk(root: AccessibilityNodeInfo): List<NodeRec> {
        val out = ArrayList<NodeRec>()
        fun rec(n: AccessibilityNodeInfo, depth: Int) {
            if (out.size >= MAX_NODES || depth > 60) return
            val b = Rect(); n.getBoundsInScreen(b)
            val sens = Build.VERSION.SDK_INT >= 34 && n.isAccessibilityDataSensitive
            val t = n.text?.toString(); val d = n.contentDescription?.toString()
            if (t != null || d != null || n.viewIdResourceName != null || sens || n.isPassword)
                out += NodeRec(n.packageName?.toString() ?: "?", n.className?.toString() ?: "?", n.viewIdResourceName, t, d, b, sens, n.isPassword)
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                rec(c, depth + 1)
                @Suppress("DEPRECATION") c.recycle()
            }
        }
        rec(root, 0)
        return out
    }

    private fun report(c: Capture) {
        val nodes = c.windows.sumOf { it.nodes.size }
        val texts = c.allText.size
        EventLog.log(EventType.CAPTURE, "done", "source=${c.source} fg=${c.foreground} windows=${c.windows.size} nodes=$nodes textBearing=$texts")
        for (w in c.windows) {
            val sens = w.nodes.count { it.sensitive }
            val withText = w.nodes.count { it.text != null || it.desc != null }
            when {
                w.rootNull -> EventLog.log(EventType.CAPTURE, "empty", "window pkg=${w.pkg ?: "?"} type=${w.type} has NO root node (blocked/secure?)")
                w.nodes.isEmpty() -> EventLog.log(EventType.CAPTURE, "empty", "pkg=${w.pkg} root present but 0 nodes (title=${w.title})")
                withText == 0 -> EventLog.log(EventType.CAPTURE, "empty", "pkg=${w.pkg} ${w.nodes.size} nodes but NO text/desc (canvas/webview/redacted?)")
            }
            if (sens > 0) EventLog.log(EventType.CAPTURE, "redacted", "pkg=${w.pkg} $sens nodes marked accessibilityDataSensitive")
            val pw = w.nodes.count { it.password }
            if (pw > 0) EventLog.log(EventType.CAPTURE, "password", "pkg=${w.pkg} $pw password fields (text withheld by system)")
        }
    }
}
