package devr.shellshock.finban.a11y

/** Regex extractors for scam-relevant entities. */
object Extractors {
    enum class Kind { URL, PHONE, UPI, AMOUNT }

    data class Match(val kind: Kind, val text: String, val start: Int, val end: Int)

    private val url = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|in|net|org|co|xyz|top|info|link|app|online|site|club|ly|me|io|cc|vip|shop|dev|store|live|click|work|icu|buzz|cfd|sbs|rest|biz|pro|tech|bank)\b(?:/[^\s<>"']*)?""")
    private val phone = Regex("""(?<![\d])(?:\+91[\s-]?|91[\s-]?|0)?[6-9]\d{4}[\s-]?\d{5}(?![\d])""")
    // name@handle; possessive handle + lookahead rejects emails like user@gmail.com
    private val upi = Regex("""\b[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}+(?!\.[a-zA-Z])""")
    private val amount = Regex("""(?i)(?:₹|\brs\.?|\binr)\s?\d[\d,]*(?:\.\d{1,2})?|\d[\d,]*(?:\.\d{1,2})?\s?(?:rupees\b|/-)""")

    fun find(text: String): List<Match> {
        val out = ArrayList<Match>()
        fun add(k: Kind, r: Regex) = r.findAll(text).forEach { out += Match(k, it.value.trim(), it.range.first, it.range.last + 1) }
        add(Kind.UPI, upi); add(Kind.URL, url); add(Kind.PHONE, phone); add(Kind.AMOUNT, amount)
        // drop URL matches that are really the domain part of a UPI id
        val upis = out.filter { it.kind == Kind.UPI }
        return out.filterNot { m -> m.kind == Kind.URL && upis.any { it.start <= m.start && it.end >= m.end } }.sortedBy { it.start }
    }
}
