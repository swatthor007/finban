package com.yourteam.guard.harness.sms

/** Parses Indian SMS sender IDs (TRAI DLT headers like "AX-HDFCBK-S") and flags plain mobile numbers. */
object SenderParser {
    private val dlt = Regex("^([A-Z])([A-Z])-([A-Z0-9]{6})(?:-([PSTG]))?$")
    private val mobile = Regex("^(?:\\+91|91|0)?[6-9]\\d{9}$")

    data class Parsed(val kind: Kind, val operator: String? = null, val circle: String? = null, val header: String? = null, val type: String? = null) {
        enum class Kind { DLT_HEADER, PLAIN_MOBILE, OTHER }
        override fun toString() = when (kind) {
            Kind.DLT_HEADER -> "DLT header=$header op=$operator circle=$circle type=${type ?: "-(none)"}"
            Kind.PLAIN_MOBILE -> "PLAIN_MOBILE (10-digit/+91 number)"
            Kind.OTHER -> "OTHER (not DLT-shaped)"
        }
    }

    fun parse(sender: String?): Parsed {
        val s = sender?.trim().orEmpty()
        dlt.matchEntire(s)?.let { return Parsed(Parsed.Kind.DLT_HEADER, it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4].ifEmpty { null }) }
        if (mobile.matches(s.replace(Regex("[\\s-]"), ""))) return Parsed(Parsed.Kind.PLAIN_MOBILE)
        return Parsed(Parsed.Kind.OTHER)
    }
}
