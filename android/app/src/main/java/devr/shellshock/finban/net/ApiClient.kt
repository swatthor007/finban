package devr.shellshock.finban.net

import devr.shellshock.finban.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Server answered with a non-2xx status. [code] is the `{"error": code}` value when present. */
class ApiException(val status: Int, val code: String?) : IOException("HTTP $status ${code ?: ""}")

/**
 * Thin client for docs/API.md. [token] returns a Firebase ID token; called with forceRefresh=true after a 401.
 * Never logs request or response bodies (they hold message text).
 */
class ApiClient(
    private val baseUrl: () -> String = { ServerConfig.url },
    private val token: suspend (forceRefresh: Boolean) -> String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val jsonType = "application/json".toMediaType()

    /** Whole call (token + network + one retry) must finish within [ANALYZE_TIMEOUT_MS] or throws TimeoutCancellationException. */
    suspend fun analyze(req: AnalyzeRequest, timeoutMs: Long = ANALYZE_TIMEOUT_MS): AnalyzeResponse =
        withTimeout(timeoutMs) { post("/analyze", json.encodeToString(req)) }

    /** The person asked us to look at a link more closely. The server fetches it; may take a few seconds. */
    suspend fun checkLink(url: String): LinkCheckResponse =
        withTimeout(LINK_CHECK_TIMEOUT_MS) { post("/link/check", json.encodeToString(LinkCheckRequest(url))) }

    suspend fun alert(req: AlertRequest): AlertResult = post("/alert", json.encodeToString(req))

    /** Photo bytes as the raw body (jpeg/png/webp). The server holds it in memory only and asks SightEngine if it looks computer-made. */
    suspend fun checkImage(bytes: ByteArray, mime: String = "image/jpeg"): String = withTimeout(IMAGE_TIMEOUT_MS) {
        val rb = bytes.toRequestBody(mime.toMediaType())
        var res = send("/image", rb, token(false))
        if (res.status == 401) res = send("/image", rb, token(true))
        if (res.status !in 200..299) throw ApiException(res.status, null)
        json.decodeFromString<ImageCheckResponse>(res.body).verdict
    }

    /** Developer tools: is the server reachable at all (no sign-in needed)? Returns the raw JSON. */
    suspend fun health(): String = withTimeout(5000) { get("/health", null) }

    /** Developer tools: which optional services the server has configured (needs sign-in). */
    suspend fun debugStatus(): DebugStatus = withTimeout(5000) { json.decodeFromString(get("/debug/status", token(false))) }

    private suspend fun get(path: String, idToken: String?): String {
        val b = Request.Builder().url(baseUrl().trimEnd('/') + path)
        b.header("ngrok-skip-browser-warning", "1")
        if (idToken != null) b.header("Authorization", "Bearer $idToken")
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(b.get().build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val text = try { r.body.string() } catch (e: IOException) { if (cont.isActive) cont.resumeWithException(e); return }
                        if (cont.isActive) { if (r.code in 200..299) cont.resume(text) else cont.resumeWithException(ApiException(r.code, null)) }
                    }
                }
            })
        }
    }

    /** A photo or screenshot: a vision model describes it and reads its text, the rules judge the text. Slower than a text check. */
    suspend fun analyzeImage(bytes: ByteArray, mime: String = "image/jpeg", aiPhotoCheck: Boolean = true): ImageAnalyzeResponse = withTimeout(IMAGE_ANALYZE_TIMEOUT_MS) {
        val path = if (aiPhotoCheck) "/image/analyze" else "/image/analyze?ai=0"
        val rb = bytes.toRequestBody(mime.toMediaType())
        var res = send(path, rb, token(false), IMAGE_ANALYZE_TIMEOUT_MS)
        if (res.status == 401) res = send(path, rb, token(true), IMAGE_ANALYZE_TIMEOUT_MS)
        if (res.status !in 200..299) throw ApiException(res.status, runCatching { json.decodeFromString<ErrorBody>(res.body).error }.getOrNull())
        json.decodeFromString(res.body)
    }

    suspend fun pairCode(): PairCode = post("/pair/code", "{}")

    suspend fun pairClaim(code: String): PairClaimResponse = post("/pair/claim", json.encodeToString(PairClaimRequest(code)))

    private suspend inline fun <reified T> post(path: String, body: String): T {
        val rb = body.toRequestBody(jsonType)
        var res = send(path, rb, token(false))
        if (res.status == 401) res = send(path, rb, token(true))
        if (res.status !in 200..299) throw ApiException(res.status, runCatching { json.decodeFromString<ErrorBody>(res.body).error }.getOrNull())
        return json.decodeFromString(res.body)
    }

    private class Raw(val status: Int, val body: String)

    private suspend fun send(path: String, body: okhttp3.RequestBody, idToken: String, callTimeoutMs: Long = 0): Raw {
        val req = Request.Builder().url(baseUrl().trimEnd('/') + path)
            .header("Authorization", "Bearer $idToken")
            .header("ngrok-skip-browser-warning", "1") // ngrok's free tier can put a warning page in front of browser-like traffic; harmless elsewhere
            .post(body).build()
        return suspendCancellableCoroutine { cont ->
            val client = if (callTimeoutMs > 0) http.newBuilder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build() else http
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val text = try { r.body.string() } catch (e: IOException) { if (cont.isActive) cont.resumeWithException(e); return }
                        if (cont.isActive) cont.resume(Raw(r.code, text))
                    }
                }
            })
        }
    }

    companion object { const val ANALYZE_TIMEOUT_MS = 3500L; const val LINK_CHECK_TIMEOUT_MS = 5000L; const val IMAGE_TIMEOUT_MS = 8000L; const val IMAGE_ANALYZE_TIMEOUT_MS = 15000L }
}
