package devr.shellshock.finban.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Risky checks (careful/high) for the Home list: what happened, where it came from, when, and why.
 * Stored on this phone only (prototype decision, so the demo can show details). The message text is kept too (prototype decision, so the details page can show it); never logged to logcat.
 * `from` is the SMS sender or the app package for screen checks. Caretakers never see any of this.
 */
object RecentChecks {
    data class Item(
        val severity: String, val title: String, val at: Long,
        val source: String = "", val from: String? = null, val pattern: String? = null, val reasons: List<String> = emptyList(), val explanation: String? = null, val body: String? = null,
    )
    private const val KEY = "recent"
    private const val MAX = 20

    /** "none" results are not logged: Home only lists things to be careful about. */
    fun add(severity: String, title: String, source: String = "", from: String? = null, pattern: String? = null,
            reasons: List<String> = emptyList(), explanation: String? = null, body: String? = null, now: Long = System.currentTimeMillis()) {
        if (severity == "none") return
        val list = (listOf(Item(severity, title.take(80), now, source, from?.take(40), pattern, reasons.take(6).map { it.take(200) }, explanation?.take(400), body?.take(1500))) + all()).take(MAX)
        val arr = JSONArray().also { a ->
            list.forEach {
                a.put(JSONObject().put("s", it.severity).put("t", it.title).put("a", it.at).put("src", it.source)
                    .put("f", it.from ?: JSONObject.NULL).put("p", it.pattern ?: JSONObject.NULL).put("r", JSONArray(it.reasons)).put("e", it.explanation ?: JSONObject.NULL).put("b", it.body ?: JSONObject.NULL))
            }
        }
        Session.prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() { Session.prefs.edit().remove(KEY).apply() }

    fun all(): List<Item> = runCatching {
        val arr = JSONArray(Session.prefs.getString(KEY, "[]"))
        (0 until arr.length()).map {
            arr.getJSONObject(it).let { o ->
                val r = o.optJSONArray("r")
                Item(o.getString("s"), o.getString("t"), o.getLong("a"), o.optString("src", ""),
                    o.optString("f").takeIf { s -> s.isNotEmpty() && s != "null" }, o.optString("p").takeIf { s -> s.isNotEmpty() && s != "null" },
                    if (r == null) emptyList() else (0 until r.length()).map { i -> r.getString(i) },
                    o.optString("e").takeIf { s -> s.isNotEmpty() && s != "null" },
                    o.optString("b").takeIf { s -> s.isNotEmpty() && s != "null" })
            }
        }
    }.getOrDefault(emptyList())
}
