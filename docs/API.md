# Finban API contract (v1)

Source of truth: `docs/SPEC.md` ("Shared contract", "Firebase"). Kotlin data classes and Pydantic models (`server/app/models.py`) must match this file exactly. Fixtures in `data/contract/` are validated on the server side; Android tests will reuse them.

All endpoints except `GET /health` require `Authorization: Bearer <Firebase ID token>`. Missing or invalid: `401 {"error":"unauthenticated"}`. All errors have the shape `{"error": "<code>"}` (not FastAPI's default `detail`). Server logs never contain message text.

UI wording: severity `none` shows "No warning signs found". Never say "safe" or "real".

## Enums
| Name | Values |
|---|---|
| `severity` | `high`, `careful`, `none` |
| `source` (request) | `screen`, `share`, `sms`, `call_context` |
| `call_type` | `cellular`, `voip` |
| `source_of_verdict` | `rules`, `rules+llm`, `cache` |
| `action` | `alert_caretaker`, `report_chakshu`, `call_1930`, `listen` |
| `trigger` | `pause_yes`, `already_paid`, `manual` |
| `alert status` | `new`, `seen`, `calling`, `resolved` |
| `role` | `elder`, `caretaker` |

## POST /analyze
Request:
```json
{
  "source": "screen",
  "app_package": "com.whatsapp",
  "sender": "+919840012345",
  "text": "...",
  "links": ["https://..."],
  "image_id": null,
  "context": {"in_call": true, "caller_unknown": true, "call_type": "voip"},
  "locale": "ta-IN"
}
```
`sender` is a TRAI header (`VM-SBIBNK`), a phone number, or for email an address, either `a@b.com` or `Display Name <a@b.com>`. The server then checks the address domain and whether the display name claims a brand the domain does not own; reason ids are `email.<flag>` (flags above plus `brand_mismatch`, `brand_mismatch_free_mail`). We cannot see SPF/DKIM, so copy says "this address doesn't match", never "this email is fake". `text` is capped at about 4 KB by the client. `app_package`, `sender`, `image_id`, `context` are nullable; `links` defaults to `[]`; `context` fields are nullable booleans / `call_type` nullable.

Response:
```json
{
  "severity": "high",
  "pattern_id": "digital-arrest",
  "title_ta": "...", "title_en": "...",
  "reasons": [{"id": "sender.mobile-bank", "level": "high", "text_ta": "...", "text_en": "..."}],
  "explanation_ta": "...",
  "explanation_en": "...",
  "audio_url": "/audio/digital-arrest.mp3",
  "actions": ["alert_caretaker", "report_chakshu", "call_1930", "listen"],
  "source_of_verdict": "rules",
  "latency_ms": 840
}
```
- `reasons`: at most 3 (the UI shows 2-3). `reasons[].level` is a `severity`.
- `explanation_en` is nullable (older clients ignore it). `source_of_verdict` is `rules+llm` when an LLM wrote the explanation text (both languages, or neither): it only rewrites `explanation_en`/`explanation_ta` from the findings, never severity, reasons or actions, and `explanation_ta` is replaced only while the template Tamil is still a TODO.
- `pattern_id`, `audio_url` are nullable. `audio_url` is relative to the server base URL.
- Severity is decided by rules and domain checks only (deterministic). The LLM never changes it.
- Client times out at 3.5 s and shows its local verdict.
- Link-level detail is not part of this response; use `GET /link`.
- Domain age (RDAP): for links that are not official, not popular and not a bare IP, the server asks the registry of the TLD (found via IANA's bootstrap file) when the domain was registered. Only the registrable domain is sent. Unknown/slow/failed means no flag, never "old". Budget 1.5 s for all links, results cached 24 h. `DOMAIN_AGE=0` turns it off.
- Page fetch is NOT part of `/analyze`. It only happens when the person taps "Look at this link more closely" (`POST /link/check`, below). The server GETs the page (no JavaScript, <= 200 KB, <= 3 redirects, 2 s, public addresses only, ports 80/443, IP pinned) and looks at title/site name/headings, forms and where they post, password/OTP/PIN/card fields, downloads, and where redirects end. It can only ADD warnings; a clean page proves nothing (scam pages hide from bots) and never lowers a severity. Page text is never logged or stored. It reveals the server's IP to the link's owner.

## POST /link/check
The person tapped "Look at this link more closely". The SERVER fetches the page (the phone never contacts the site). URL is in the body (never in a query string, so it is not in access logs; it is never logged).
Request: `{"url": "https://..."}` (<= 2048 chars; a missing scheme becomes `http://`; other schemes `422 {"error":"bad_url"}`). Rate limit: 10 per minute per uid (`429 rate_limited`). Kill switch: server env `LINK_FETCH=0`.
Response:
```json
{"severity": "high",
 "reasons": [{"id": "link.page_brand_mismatch", "level": "high", "text_ta": "...", "text_en": "..."}],
 "checked": {"registrable_domain": "sbi-kyc.evil.top", "final_domain": "evil.top", "redirected": false, "age_days": 3, "page_reached": true},
 "latency_ms": 1800}
```
Same flags as above (address checks + domain age + page content). The client takes the HIGHER of this and the `/analyze` severity; this call can only add warnings. `page_reached:false` (blocked address, timeout, error) means "unknown", never "fine"; with no other findings the single reason is `link.page_unreachable` (level `none`). The client allows ~5 s for this call; the server stops at ~3.2 s.

## POST /image
Raw image bytes as the body (`Content-Type: image/jpeg|png|webp`, max 4 MB; the app re-encodes to <= 1600 px JPEG). No multipart. Held in memory only, never written or logged; it is sent to SightEngine (`genai` model), which the sheet tells the person. Response: `{"verdict": "likely_ai" | "unlikely_ai" | "unknown"}` (>= 0.85 likely, <= 0.15 unlikely, else or on any error/no key: unknown). It never changes the text severity: the sheet adds a note, "probably made by a computer" or "we couldn't tell", never "real"/"fake"/"safe". Errors: `415 unsupported_type`, `413 too_large`, `429 rate_limited` (10/min/uid). Env: `SIGHTENGINE_USER`, `SIGHTENGINE_SECRET` (unset = always `unknown`), `IMAGE_CHECK=0` kill switch. The scam wording is read ON THE PHONE (ML Kit, Latin script only, no Tamil) and sent to `/analyze` as text; `image_id` is unused.

## GET /link?url=
Link-only check. Response `LinkVerdict`:
`{"url": str, "registrable_domain": str, "flags": [str], "age_days": int|null, "suspected_malicious": bool|null}` (`null` = unknown, never "old"/"clean").
Flags (open list, clients must ignore unknown ones): `punycode, non_ascii, mixed_script, confusable, lookalike, lookalike_official, shortener, risky_tld, many_hyphens, bare_ip, user_info, apk, brand_in_domain`. Server severity: `high` for bare_ip, user_info, apk, mixed_script, confusable, lookalike_official, brand_in_domain; `careful` for the rest (punycode/non_ascii alone are careful: genuine Tamil IDN domains exist).

## POST /pair/code   (elder)
Response: `{"code": "123456", "expires_at": "<iso8601>"}` (6 digits, valid 10 minutes, single use).

## POST /pair/claim   (caretaker)
Request: `{"code": str}`. Response: `{"link_id": str, "elder_name": str}`.
Rate limits: 5 claims per uid per 10 minutes (every attempt counts), 5 codes per uid per 10 minutes. A failed claim (including `elder_full`) does not burn the code; claiming your own code is `code_invalid`; re-claiming an existing link succeeds again.
Errors: `404 code_invalid`, `410 code_expired`, `409 code_used`, `409 elder_full` (max 3 caretakers per elder), `429 rate_limited` (per uid).
Link id is deterministic: `{elder_uid}_{caretaker_uid}` (see Firestore).

## DELETE /pair/{link_id}
Either member of the link. `204`; `403 forbidden` otherwise (also when the link does not exist). The caretaker is also removed from `caretaker_uids` of that elder's existing alerts, so they lose access to the alert history.

## POST /alert   (elder)
Request: `{"alert_id": str}` for an `alerts/{id}` doc the caller already created. Server verifies ownership and that links exist, then sends a high-priority FCM push (channel "Scam alerts") to each caretaker's `fcm_tokens` and drops tokens FCM reports invalid. Response: `{"sent": int, "pruned": int}`. Errors: `404 alert_not_found`, `403 forbidden` (not the alert's elder). Caretakers listed but without a link are skipped. The server sets `pushed_at` on the alert once at least one push succeeded; a repeat call then returns `{"sent":0,"pruned":0}` without pushing. FCM message: `android.priority=high`, `channel_id="scam_alerts"`, `data={"type":"scam_alert","alert_id":"..."}` only.
Push text: "[Name] may be on a scam call. Call them now." plus the pattern name in Tamil and English.

## GET /health   (no auth)
`{"status": "ok", "version": str, "demo_mode": bool}`

## GET /patterns/version
`{"version": str}`

## Firestore model
```
users/{uid}        role, display_name, fcm_tokens[], lang, created_at
links/{linkId}     elder_uid, caretaker_uid, created_at          (linkId = "{elder_uid}_{caretaker_uid}")
pair_codes/{code}  elder_uid, expires_at, used                   (server-only)
alerts/{alertId}   elder_uid, caretaker_uids[], pattern_id, severity,
                   trigger, created_at, status, ack_by, ack_at,
                   pushed_at (server-written)
```
- A caretaker may follow several elders; an elder may have up to 3 caretakers.
- Deterministic link ids let security rules check `exists(/links/...)` when an alert is created (rules cannot run queries).
- `alerts` creation: elder only, `status == "new"`, `caretaker_uids` all linked to the elder. Listed caretakers may update only `status`, `ack_by`, `ack_at`.
- Caretakers see only who / when / pattern / severity / trigger. Never message text, numbers, screenshots or amounts.

## Kotlin data classes (to be created in Sprint 4, `analyze` package)
Use `kotlinx.serialization` with `@SerialName` snake_case keys; enums serialise to the lowercase strings above (`call_context`, `rules+llm`).
```kotlin
@Serializable data class AnalyzeContext(val in_call: Boolean? = null, val caller_unknown: Boolean? = null, val call_type: String? = null)
@Serializable data class AnalyzeRequest(
  val source: String, val app_package: String? = null, val sender: String? = null,
  val text: String, val links: List<String> = emptyList(), val image_id: String? = null,
  val context: AnalyzeContext? = null, val locale: String = "ta-IN")
@Serializable data class Reason(val id: String, val level: String, val text_ta: String, val text_en: String)
@Serializable data class AnalyzeResponse(
  val severity: String, val pattern_id: String? = null, val title_ta: String, val title_en: String,
  val reasons: List<Reason>, val explanation_ta: String, val explanation_en: String? = null, val audio_url: String? = null,
  val actions: List<String>, val source_of_verdict: String, val latency_ms: Int)
@Serializable data class LinkVerdict(val url: String, val registrable_domain: String, val flags: List<String> = emptyList(), val age_days: Int? = null, val suspected_malicious: Boolean? = null)
@Serializable data class LinkCheckRequest(val url: String)
@Serializable data class LinkChecked(
  @SerialName("registrable_domain") val registrableDomain: String, @SerialName("final_domain") val finalDomain: String,
  val redirected: Boolean = false, @SerialName("age_days") val ageDays: Int? = null, @SerialName("page_reached") val pageReached: Boolean = false)
@Serializable data class LinkCheckResponse(val severity: String, val reasons: List<Reason> = emptyList(), val checked: LinkChecked, @SerialName("latency_ms") val latencyMs: Int = 0)
@Serializable data class PairCode(val code: String, val expires_at: String)
@Serializable data class PairClaimRequest(val code: String)
@Serializable data class PairClaimResponse(val link_id: String, val elder_name: String)
@Serializable data class AlertRequest(val alert_id: String)
@Serializable data class AlertResult(val sent: Int, val pruned: Int)
```


## POST /image/analyze   (added after S2)
Raw image bytes as the body (`Content-Type: image/jpeg|png|webp`, max 4 MB), Bearer token. A vision model (OpenRouter; `VISION_MODEL`, else `LLM_MODEL`; must accept images) says what the picture shows and reads its text; **the text is judged by the same rules as `/analyze`, so severity is never the model's**. Also runs the AI-photo check (paid; skipped with `?ai=0`, which the app uses for screenshots).
Response = the `/analyze` response plus: `description` (1-2 plain sentences, no judgement; dropped if it uses banned wording), `text_found`, `text` (what was read, returned to the sender only), `vision_available` (false = no vision model answered; the app falls back to on-device OCR), `ai_image` (`likely_ai|unlikely_ai|unknown`).
Errors: `415 unsupported_type`, `413 too_large`, `429 rate_limited` (shared 10/min limiter with `/image`). The image is held in memory only and never logged; it leaves the server for OpenRouter and its provider (and SightEngine for the AI-photo check).
