package devr.shellshock.finban.a11y

/**
 * Turns a [Capture] into the text we send to /analyze. Reading order, app windows only (not our own overlay, the keyboard
 * or the system bar), password and "sensitive" nodes skipped. Pure: unit-testable. The result is never logged.
 */
object ScreenReader {
    const val MAX_CHARS = 4000
    private const val TYPE_APPLICATION = 1  // AccessibilityWindowInfo.TYPE_APPLICATION

    class Read(val text: String, val appPackage: String?, val links: List<String>)

    /** Fewer characters than this on screen = treat the text as too little to judge and look at a picture of the screen instead. */
    const val WEAK_CHARS = 40

    /** True when an image node covers a big part of the screen (a photo in a chat, a banner): its content is not in the text. */
    fun hasBigPicture(cap: Capture, ownPackage: String, screenW: Int, screenH: Int): Boolean {
        val screen = screenW.toLong() * screenH
        return cap.windows.asSequence()
            .filter { it.type == TYPE_APPLICATION || it.type == -1 }
            .filter { it.pkg != null && it.pkg != ownPackage && it.pkg != "com.android.systemui" }
            .flatMap { it.nodes.asSequence() }
            .any { n -> (n.cls.endsWith("ImageView") || n.cls == "android.widget.Image") && n.bounds.width().toLong() * n.bounds.height() * 5 > screen }
    }

    /** Bounds (screen pixels) of the biggest big picture on screen, or null. Same rule as [hasBigPicture]; used to crop the picture for the AI-photo check. */
    fun bigPictureBounds(cap: Capture, ownPackage: String, screenW: Int, screenH: Int): android.graphics.Rect? {
        val screen = screenW.toLong() * screenH
        return cap.windows.asSequence()
            .filter { it.type == TYPE_APPLICATION || it.type == -1 }
            .filter { it.pkg != null && it.pkg != ownPackage && it.pkg != "com.android.systemui" }
            .flatMap { it.nodes.asSequence() }
            .filter { n -> (n.cls.endsWith("ImageView") || n.cls == "android.widget.Image") && n.bounds.width().toLong() * n.bounds.height() * 5 > screen }
            .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }?.bounds?.let { android.graphics.Rect(it) }
    }

    /** null when there is nothing readable (blocked/secure screen, canvas, our own app in front). */
    fun read(cap: Capture, ownPackage: String): Read? {
        val nodes = cap.windows.asSequence()
            .filter { it.type == TYPE_APPLICATION || it.type == -1 }    // -1 = the rootInActiveWindow fallback
            .filter { it.pkg != null && it.pkg != ownPackage && it.pkg != "com.android.systemui" }
            .flatMap { w -> w.nodes.asSequence().map { w.pkg to it } }
            .filter { (_, n) -> !n.password && !n.sensitive }
            .sortedWith(compareBy({ it.second.bounds.top }, { it.second.bounds.left }))
            .toList()
        val lines = ArrayList<String>()
        for ((_, n) in nodes) {
            val t = (n.text ?: n.desc)?.trim().orEmpty()
            if (t.isEmpty() || lines.lastOrNull() == t) continue
            lines += t
        }
        if (lines.isEmpty()) return null
        val text = lines.joinToString("\n").take(MAX_CHARS)
        val links = Extractors.find(text).filter { it.kind == Extractors.Kind.URL }.map { it.text }.distinct().take(10)
        return Read(text, nodes.firstOrNull()?.first, links)
    }
}
