package devr.shellshock.finban.overlay

import devr.shellshock.finban.net.explanation
import devr.shellshock.finban.net.text
import devr.shellshock.finban.net.title
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import devr.shellshock.finban.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import devr.shellshock.finban.ui.sheet.PictureInfo
import devr.shellshock.finban.net.AnalyzeResponse
import devr.shellshock.finban.data.Session
import devr.shellshock.finban.a11y.Extractors
import devr.shellshock.finban.a11y.ScreenShot
import devr.shellshock.finban.a11y.ForegroundTracker
import devr.shellshock.finban.a11y.FinbanAccessibilityService
import devr.shellshock.finban.a11y.ScreenCapture
import devr.shellshock.finban.a11y.ScreenReader
import devr.shellshock.finban.data.RecentChecks
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import devr.shellshock.finban.net.AnalyzeRequest
import devr.shellshock.finban.net.Api
import devr.shellshock.finban.ui.sheet.LinkCheckState
import devr.shellshock.finban.ui.sheet.LinkCheckUi
import devr.shellshock.finban.ui.sheet.ResultSheetContent
import devr.shellshock.finban.ui.sheet.SheetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bubble tap -> scan effect -> read the screen -> /analyze -> result sheet, all over whatever app is in front.
 * The screen text goes to /analyze once and is never stored or logged (same rule as share-to-check). Warns only.
 */
class ScreenCheck(private val svc: FinbanAccessibilityService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scan = ScanOverlayController(svc)
    private var job: Job? = null
    private var linkJob: Job? = null
    private var state by mutableStateOf<SheetState>(SheetState.Loading)
    private var linkCheck by mutableStateOf<LinkCheckState>(LinkCheckState.Idle)
    private var linkUrl: String? = null
    private var pictureBitmap: android.graphics.Bitmap? = null
    private var aiCrop: ByteArray? = null                      // the big picture alone, in memory only; sent only if the person taps the AI-photo button
    private var aiVerdict by mutableStateOf<String?>(null)     // null | "pending" | likely_ai | unlikely_ai | unknown
    private var aiCropJob: Job? = null
    private var sharedText by mutableStateOf<String?>(null) // message quote for the SMS popup; on screen only, never stored
    private var alertState by mutableStateOf<devr.shellshock.finban.net.AlertState>(devr.shellshock.finban.net.AlertState.Idle)

    val busy get() = job?.isActive == true || svc.sheet.isShown

    fun start(source: String) {
        if (busy) return
        aiCropJob?.cancel(); aiCrop = null; aiVerdict = null
        linkJob?.cancel(); linkCheck = LinkCheckState.Idle; linkUrl = null; sharedText = null; pictureBitmap = null; alertState = devr.shellshock.finban.net.AlertState.Idle
        job = scope.launch {
            val origin = svc.bubble?.centerPx()
            val t0 = SystemClock.elapsedRealtime()
            var autoAiCheck = false
            val result: SheetState = try {
                val cap = withContext(Dispatchers.Default) { ScreenCapture.capture(svc, source) }
                val read = ScreenReader.read(cap, svc.packageName)
                val dm = svc.resources.displayMetrics
                // Little or no readable text, or a big picture on screen: also look at a picture of the screen (if allowed).
                val wantPicture = Session.pictureCheck && ScreenShot.supported() && ScreenShot.allowedHere(ForegroundTracker.pkg) &&
                    (read == null || read.text.length < ScreenReader.WEAK_CHARS || ScreenReader.hasBigPicture(cap, svc.packageName, dm.widthPixels, dm.heightPixels))
                // The screen is photographed whenever allowed: the text/vision path uses it only when text is weak or a big picture is on
                // screen, and the manual "made by a computer?" button always has something to send (the cropped picture, else the whole screen).
                var shot: ByteArray? = null
                var bigPicture = false
                if (Session.pictureCheck && ScreenShot.supported() && ScreenShot.allowedHere(ForegroundTracker.pkg)) {
                    svc.bubble?.setSheetOpen(true); delay(220)   // our own bubble must not be in the picture
                    val bounds = ScreenReader.bigPictureBounds(cap, svc.packageName, dm.widthPixels, dm.heightPixels)
                    val shots = ScreenShot.take(svc, bounds)
                    svc.bubble?.setSheetOpen(false)
                    if (shots != null) {
                        aiCrop = shots.crop ?: shots.full
                        bigPicture = shots.crop != null
                        if (wantPicture) shot = shots.full
                    }
                }
                // Picture path: the sheet opens at once with the picture and a sweep over it (instead of the full-screen wave).
                if (shot != null) {
                    pictureBitmap = android.graphics.BitmapFactory.decodeByteArray(shot, 0, shot.size)
                    pictureBitmap?.let { state = SheetState.ScanningPicture(it); presentSheet() } ?: scan.show(origin?.first, origin?.second)
                } else scan.show(origin?.first, origin?.second)
                linkUrl = read?.links?.firstOrNull()
                val (textR, picR) = coroutineScope {
                    val t = read?.let { r -> async { runCatching { Api.client.analyze(AnalyzeRequest(source = "screen", appPackage = r.appPackage, text = r.text, links = r.links)) } } }
                    val p = shot?.let { b -> async { runCatching { Api.client.analyzeImage(b, aiPhotoCheck = false) } } }
                    t?.await() to p?.await()
                }
                val text = textR?.getOrNull()
                val pic = picR?.getOrNull()?.takeIf { it.visionAvailable }
                // The riskier of the two answers wins; the model's description rides along. Severity is never the model's.
                fun rank(s: String) = when (s) { "high" -> 2; "careful" -> 1; else -> 0 }
                val chosen: AnalyzeResponse?; 
                // A payment proof or ID in a picture: say what it looks like, and check the picture itself without waiting for a tap. Only for an actual
                // picture on screen: words in a chat or a web form (a bank text, a Name + DOB form) have no picture worth sending to the AI check.
                val onPicture = pic != null || bigPicture
                val picKind = if (onPicture) devr.shellshock.finban.ui.share.PictureKind.detect(listOfNotNull(pic?.text, read?.text).joinToString("\n")) else null
                autoAiCheck = picKind != null
                val picInfo = if (pic != null || picKind != null) PictureInfo(null, "none", pictureBitmap, picKind) else null
                if (pic != null && (text == null || rank(pic.severity) >= rank(text.severity))) {
                    chosen = pic.toAnalyze(); sharedText = pic.text?.take(500)
                    if (linkUrl == null) linkUrl = Extractors.find(pic.text.orEmpty()).firstOrNull { it.kind == Extractors.Kind.URL }?.text
                } else chosen = text
                when {
                    chosen != null -> {
                        RecentChecks.add(chosen.severity, chosen.title, "screen", read?.appPackage ?: ForegroundTracker.pkg, chosen.patternId,
                            chosen.reasons.map { it.text }, chosen.explanation, body = if (picKind?.isId == true) null else listOfNotNull(pic?.description, read?.text).joinToString("\n").ifBlank { null })
                        SheetState.Result(chosen, picInfo)
                    }
                    textR == null && picR == null -> SheetState.NoScreenText
                    else -> {
                        EventLog.log(EventType.SERVICE, "screen-analyze", "failed: ${(picR?.exceptionOrNull() ?: textR?.exceptionOrNull())?.javaClass?.simpleName}")
                        SheetState.Failed
                    }
                }
            } catch (e: TimeoutCancellationException) {
                EventLog.log(EventType.SERVICE, "screen-analyze", "timeout"); SheetState.Failed
            } catch (e: CancellationException) {
                scan.hide(); throw e
            } catch (e: Exception) {
                EventLog.log(EventType.SERVICE, "screen-analyze", "failed: ${e.javaClass.simpleName}"); SheetState.Failed   // never the text
            }
            val inPicture = state is SheetState.ScanningPicture
            if (!inPicture) {
                val left = MIN_SCAN_MS - (SystemClock.elapsedRealtime() - t0)       // let at least one full sweep play
                if (left > 0) delay(left)
            }
            scan.hide()
            if (inPicture && !svc.sheet.isShown) return@launch                      // the person closed the sheet while it was looking
            state = result
            presentSheet()
            if (autoAiCheck && result is SheetState.Result) checkAiPhoto()
        }
    }

    /** Shows the result sheet over the front app (no-op if it is already up: the content follows [state]). */
    private fun presentSheet() {
        svc.sheet.show { dismiss ->
            ResultSheetContent(
                state, onClose = dismiss, overlay = true, shared = sharedText,
                alert = devr.shellshock.finban.ui.sheet.AlertUi(alertState) { tellTrusted() },
                imageVerdict = aiVerdict, aiCheck = aiCrop?.takeIf { aiVerdict == null }?.let { devr.shellshock.finban.ui.sheet.AiCheckUi { checkAiPhoto() } },
                linkCheck = linkUrl?.let { u -> LinkCheckUi(u.substringAfter("://").substringBefore('/').substringBefore('?').take(60), linkCheck) { checkLink(u) } },
            )
        }
    }

    /**
     * Incoming-SMS popup: the same bottom sheet as the bubble check, shown over whatever app is in front.
     * Must run on the main thread. False if another sheet is already up (caller falls back to a notification).
     */
    fun showResult(r: devr.shellshock.finban.net.AnalyzeResponse, text: String): Boolean {
        if (job?.isActive == true) return false
        // A newer risky text replaces the content of an SMS sheet that is still open (otherwise it would only get a quiet notification).
        // Another sheet (e.g. the payment pause) is never replaced.
        val replace = svc.sheet.isShown && svc.sheet.sheetOwner === this && state is SheetState.Result
        if (svc.sheet.isShown && !replace) return false
        linkJob?.cancel(); linkCheck = LinkCheckState.Idle; alertState = devr.shellshock.finban.net.AlertState.Idle
        linkUrl = devr.shellshock.finban.a11y.Extractors.find(text).firstOrNull { it.kind == devr.shellshock.finban.a11y.Extractors.Kind.URL }?.text
        sharedText = text.take(500)
        state = SheetState.Result(r)
        presentSheet()
        svc.sheet.sheetOwner = this
        return true
    }

    /** The person tapped "Check if this picture was made by a computer": only the cropped picture goes to the server. */
    private fun checkAiPhoto() {
        val bytes = aiCrop ?: return
        if (aiVerdict != null) return
        aiVerdict = "pending"
        aiCropJob = scope.launch {
            aiVerdict = try { Api.client.checkImage(bytes) }
            catch (e: CancellationException) { if (e is TimeoutCancellationException) "unknown" else throw e }
            catch (e: Exception) { EventLog.log(EventType.SERVICE, "ai-photo", "failed: ${e.javaClass.simpleName}"); "unknown" }
        }
    }

    private fun tellTrusted() {
        val r = (state as? SheetState.Result)?.response ?: return
        alertState = devr.shellshock.finban.net.AlertState.Sending
        scope.launch { alertState = devr.shellshock.finban.net.CaretakerAlert.send(r.severity, r.patternId) }
    }

    private fun checkLink(url: String) {
        linkJob?.cancel()
        linkCheck = LinkCheckState.Running
        linkJob = scope.launch {
            linkCheck = try { LinkCheckState.Done(Api.client.checkLink(url)) }
            catch (e: CancellationException) { if (e is TimeoutCancellationException) LinkCheckState.Failed else throw e }
            catch (e: Exception) { EventLog.log(EventType.SERVICE, "link-check", "failed: ${e.javaClass.simpleName}"); LinkCheckState.Failed }
        }
    }

    fun stop() { job?.cancel(); linkJob?.cancel(); aiCropJob?.cancel(); scan.hide() }

    private companion object { const val MIN_SCAN_MS = 1800L }   // one full expansion of the wave
}
