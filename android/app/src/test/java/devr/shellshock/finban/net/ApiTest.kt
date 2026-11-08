package devr.shellshock.finban.net

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = MockWebServer()
    private val tokens = mutableListOf<Boolean>()
    private lateinit var api: ApiClient

    // Unit tests run with the module dir (android/app) as cwd.
    private fun fixture(name: String) = File("../../data/contract/$name").readText()
    private val req = AnalyzeRequest(source = "share", text = "hello", links = listOf("http://a.in"))

    @Before fun setUp() {
        server.start()
        api = ApiClient({ server.url("/").toString() }, { force -> tokens += force; if (force) "fresh" else "t" })
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun contractFixturesDecode() {
        val r = json.decodeFromString<AnalyzeResponse>(fixture("analyze_response.json"))
        assertEquals("high", r.severity)
        assertEquals(1, r.reasons.size)
        assertFalse(r.aiAssisted)
        json.decodeFromString<AnalyzeRequest>(fixture("analyze_request.json"))
    }

    @Test fun aiBadgeOnlyForRulesPlusLlm() {
        val base = json.decodeFromString<AnalyzeResponse>(fixture("analyze_response.json"))
        assertTrue(base.copy(sourceOfVerdict = "rules+llm").aiAssisted)
        assertFalse(base.copy(sourceOfVerdict = "cache").aiAssisted)
    }

    @Test fun analyzeSendsBearerAndSnakeCaseBody() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("analyze_response.json")))
        val r = api.analyze(req)
        assertEquals("digital-arrest", r.patternId)
        val rec = server.takeRequest()
        assertEquals("Bearer t", rec.getHeader("Authorization"))
        val body = rec.body.readUtf8()
        assertTrue(body, body.contains("\"app_package\":null") && body.contains("\"locale\":\"ta-IN\"") && body.contains("\"source\":\"share\""))
    }

    @Test fun retriesOnceOn401WithRefreshedToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthenticated"}"""))
        server.enqueue(MockResponse().setBody(fixture("analyze_response.json")))
        api.analyze(req)
        assertEquals(listOf(false, true), tokens)
        server.takeRequest()
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun errorBodyBecomesApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate_limited"}"""))
        val e = runCatching { api.analyze(req) }.exceptionOrNull() as ApiException
        assertEquals(429, e.status); assertEquals("rate_limited", e.code)
    }

    @Test fun nonJsonErrorHasNullCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        assertNull((runCatching { api.analyze(req) }.exceptionOrNull() as ApiException).code)
    }

    @Test fun timesOutAfterAbout3500ms() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t0 = System.nanoTime()
        val e = runCatching { runBlocking { api.analyze(req) } }.exceptionOrNull()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(e.toString(), e is TimeoutCancellationException)
        assertTrue("took $ms ms", ms in 3400..5000)
    }

    private val linkBody = """{"severity":"high","reasons":[{"id":"link.page_brand_mismatch","level":"high","text_ta":"TODO(hackathon): Tamil","text_en":"The page says it is a bank site, but the address is not theirs."}],
        "checked":{"registrable_domain":"evil.top","final_domain":"evil.top","redirected":false,"age_days":3,"page_reached":true},"latency_ms":1800}"""

    @Test fun checkLinkPostsUrlInBodyNotQuery() = runBlocking {
        server.enqueue(MockResponse().setBody(linkBody))
        val r = api.checkLink("https://evil.top/login?x=1")
        assertEquals("high", r.severity); assertEquals(3, r.checked.ageDays); assertTrue(r.checked.pageReached)
        val rec = server.takeRequest()
        assertEquals("/link/check", rec.path)                       // no URL in the request line
        assertTrue(rec.body.readUtf8().contains("\"url\":\"https://evil.top/login?x=1\""))
    }

    @Test fun checkLinkRateLimitIsAnApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate_limited"}"""))
        assertEquals("rate_limited", (runCatching { api.checkLink("http://a.in") }.exceptionOrNull() as ApiException).code)
    }
}
