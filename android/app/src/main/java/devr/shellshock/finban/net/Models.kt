package devr.shellshock.finban.net

import devr.shellshock.finban.data.Session
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors docs/API.md ("Kotlin data classes"). Enums stay as strings so unknown values never crash the client.

@Serializable
data class AnalyzeContext(
    @SerialName("in_call") val inCall: Boolean? = null,
    @SerialName("caller_unknown") val callerUnknown: Boolean? = null,
    @SerialName("call_type") val callType: String? = null,
)

/** Server placeholders ("TODO(hackathon): Tamil") must never reach the screen. */
internal fun String?.realText(): String? = this?.takeUnless { it.isBlank() || it.startsWith("TODO") }

/** The chosen language, or the other one if that text is missing. */
private fun pick(ta: String?, en: String?): String? =
    if (Session.lang == "ta") ta.realText() ?: en.realText() else en.realText() ?: ta.realText()

val AnalyzeResponse.title: String get() = pick(titleTa, titleEn).orEmpty()
val AnalyzeResponse.explanation: String? get() = pick(explanationTa, explanationEn)
val Reason.text: String get() = pick(textTa, textEn).orEmpty()

@Serializable
data class AnalyzeRequest(
    val source: String,
    @SerialName("app_package") val appPackage: String? = null,
    val sender: String? = null,
    val text: String,
    val links: List<String> = emptyList(),
    @SerialName("image_id") val imageId: String? = null,
    val context: AnalyzeContext? = null,
    val locale: String = "ta-IN",
)

@Serializable
data class Reason(
    val id: String,
    val level: String,
    @SerialName("text_ta") val textTa: String,
    @SerialName("text_en") val textEn: String,
)

@Serializable
data class AnalyzeResponse(
    val severity: String,
    @SerialName("pattern_id") val patternId: String? = null,
    @SerialName("title_ta") val titleTa: String,
    @SerialName("title_en") val titleEn: String,
    val reasons: List<Reason> = emptyList(),
    @SerialName("explanation_ta") val explanationTa: String,
    @SerialName("explanation_en") val explanationEn: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    val actions: List<String> = emptyList(),
    @SerialName("source_of_verdict") val sourceOfVerdict: String,
    @SerialName("latency_ms") val latencyMs: Int = 0,
) {
    /** True when an LLM wrote the explanation text (never the severity). Drives the "AI-assisted" badge. */
    val aiAssisted: Boolean get() = sourceOfVerdict == "rules+llm"
}

@Serializable data class LinkCheckRequest(val url: String)

@Serializable
data class DebugStatus(
    val version: String = "", @SerialName("demo_mode") val demoMode: Boolean = false,
    @SerialName("llm_configured") val llmConfigured: Boolean = false, @SerialName("llm_model") val llmModel: String? = null,
    @SerialName("image_configured") val imageConfigured: Boolean = false, @SerialName("link_fetch") val linkFetch: Boolean = true,
    @SerialName("domain_age") val domainAge: Boolean = true,
)

@Serializable
data class LinkChecked(
    @SerialName("registrable_domain") val registrableDomain: String,
    @SerialName("final_domain") val finalDomain: String,
    val redirected: Boolean = false,
    @SerialName("age_days") val ageDays: Int? = null,
    @SerialName("page_reached") val pageReached: Boolean = false,
)

@Serializable
data class LinkCheckResponse(
    val severity: String,
    val reasons: List<Reason> = emptyList(),
    val checked: LinkChecked,
    @SerialName("latency_ms") val latencyMs: Int = 0,
)

@Serializable data class PairCode(val code: String, @SerialName("expires_at") val expiresAt: String)
@Serializable data class PairClaimRequest(val code: String)
@Serializable data class PairClaimResponse(@SerialName("link_id") val linkId: String, @SerialName("elder_name") val elderName: String)
@Serializable data class AlertRequest(@SerialName("alert_id") val alertId: String)
@Serializable data class AlertResult(val sent: Int, val pruned: Int)
@Serializable data class ErrorBody(val error: String? = null)

@Serializable data class ImageCheckResponse(val verdict: String)

/** POST /image/analyze: the /analyze answer plus what the picture shows. [visionAvailable] false = no vision model answered. */
@Serializable
data class ImageAnalyzeResponse(
    val severity: String,
    @SerialName("pattern_id") val patternId: String? = null,
    @SerialName("title_ta") val titleTa: String,
    @SerialName("title_en") val titleEn: String,
    val reasons: List<Reason> = emptyList(),
    @SerialName("explanation_ta") val explanationTa: String,
    @SerialName("explanation_en") val explanationEn: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    val actions: List<String> = emptyList(),
    @SerialName("source_of_verdict") val sourceOfVerdict: String,
    @SerialName("latency_ms") val latencyMs: Int = 0,
    val description: String? = null,
    @SerialName("text_found") val textFound: Boolean = false,
    val text: String? = null,
    @SerialName("vision_available") val visionAvailable: Boolean = false,
    @SerialName("ai_image") val aiImage: String = "unknown",
) {
    fun toAnalyze() = AnalyzeResponse(severity, patternId, titleTa, titleEn, reasons, explanationTa, explanationEn, audioUrl, actions, sourceOfVerdict, latencyMs)
}
