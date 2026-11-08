package devr.shellshock.finban.ui.share

import devr.shellshock.finban.net.explanation
import devr.shellshock.finban.net.text
import devr.shellshock.finban.net.title
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import devr.shellshock.finban.R
import devr.shellshock.finban.a11y.Extractors
import devr.shellshock.finban.net.AnalyzeRequest
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import devr.shellshock.finban.net.Api
import devr.shellshock.finban.ui.sheet.LinkCheckState
import devr.shellshock.finban.ui.sheet.LinkCheckUi
import devr.shellshock.finban.ui.sheet.ResultSheetContent
import devr.shellshock.finban.ui.sheet.SheetState
import devr.shellshock.finban.ui.theme.Finban
import devr.shellshock.finban.ui.theme.FinbanTheme
import devr.shellshock.finban.ui.theme.Radii
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share-to-check: any app can share text here. The text goes to /analyze once, is never stored or logged,
 * and the answer is shown in a bottom sheet. The person decides what to do; nothing is sent anywhere else.
 */
class ShareReceiverActivity : ComponentActivity() {
    private var state by mutableStateOf<SheetState>(SheetState.Loading)
    private var linkCheck by mutableStateOf<LinkCheckState>(LinkCheckState.Idle)
    private var linkUrl: String? = null
    private var fromPhoto = false
    private var imageVerdict by mutableStateOf<String?>(null) // null = no photo; "pending" | "likely_ai" | "unlikely_ai" | "unknown"
    private var alertState by mutableStateOf<devr.shellshock.finban.net.AlertState>(devr.shellshock.finban.net.AlertState.Idle)
    private var shared by mutableStateOf<String?>(null) // shown in the sheet only; never logged or saved

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !handle(intent)) { finish(); return }
        setContent {
            FinbanTheme {
                ModalBottomSheet(
                    onDismissRequest = { finish() },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    shape = Radii.sheet, containerColor = Finban.colors.canvas, scrimColor = Finban.colors.scrim,
                ) {
                    ResultSheetContent(
                        state, onClose = { finish() }, shared = shared, imageVerdict = imageVerdict,
                        linkCheck = linkUrl?.let { u -> LinkCheckUi(hostOf(u), linkCheck) { checkLink(u) } },
                        alert = devr.shellshock.finban.ui.sheet.AlertUi(alertState) { tellTrusted() },
                    )
                }
            }
        }
    }

    /** A second share while the sheet is open replaces the first one. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handle(intent)) finish()
    }

    /** Home's "Check a message or photo" starts this activity with [EXTRA_PICK]: the system photo picker (no permission needed). */
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) finish() else checkImage(uri)
    }

    private fun checkImage(uri: Uri) {
        job?.cancel(); linkJob?.cancel()
        state = SheetState.Loading
        linkCheck = LinkCheckState.Idle
        alertState = devr.shellshock.finban.net.AlertState.Idle
        shared = null; linkUrl = null
        imageVerdict = null; fromPhoto = true
        job = lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { downscaledJpeg(this@ShareReceiverActivity, uri) } // re-encoded smaller, in memory only
            val bmp = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) state = SheetState.ScanningPicture(bmp) // the photo itself, with a sweep over it, while it is looked at
            // First choice: a vision model describes the picture and reads its text; the rules judge that text (severity is never the model's).
            val pic = if (bytes == null) null else try {
                Api.client.analyzeImage(bytes).takeIf { it.visionAvailable }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) null else throw e
            } catch (e: Exception) {
                EventLog.log(EventType.SERVICE, "image-analyze", "failed: ${e.javaClass.simpleName}"); null
            }
            if (pic != null) {
                val r = pic.toAnalyze()
                val kind = PictureKind.detect(pic.text)
                shared = pic.text?.take(MAX_CHARS)
                linkUrl = Extractors.find(pic.text.orEmpty()).firstOrNull { it.kind == Extractors.Kind.URL }?.text
                devr.shellshock.finban.data.RecentChecks.add(r.severity, r.title, "photo", null, r.patternId, r.reasons.map { it.text }, r.explanation,
                    body = if (kind?.isId == true) null else listOfNotNull(pic.description, pic.text).joinToString("\n").ifBlank { null }) // an ID's text and numbers are never kept
                state = SheetState.Result(r, devr.shellshock.finban.ui.sheet.PictureInfo(null, pic.aiImage, bmp, kind))
                return@launch
            }
            // Fallback (no vision model, offline for this call, or too slow): on-device OCR + /analyze, plus the AI-photo note.
            imageVerdict = "pending"
            launch {
                imageVerdict = try {
                    if (bytes == null) "unknown" else Api.client.checkImage(bytes)
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) "unknown" else throw e
                } catch (e: Exception) {
                    EventLog.log(EventType.SERVICE, "image-check", "failed: ${e.javaClass.simpleName}")
                    "unknown"
                }
            }
            val text = readText(this@ShareReceiverActivity, uri)
            when {
                text == null -> { EventLog.log(EventType.SERVICE, "ocr", "failed"); state = SheetState.Failed }
                text.isBlank() -> state = SheetState.NoText
                else -> { shared = text.take(MAX_CHARS); check(text) } // the recognised text is shown on screen only, like shared text
            }
        }
    }

    private fun handle(intent: Intent?): Boolean {
        imageVerdict = null; fromPhoto = false
        if (intent?.getBooleanExtra(EXTRA_PICK, false) == true) {
            intent.removeExtra(EXTRA_PICK)
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            return true
        }
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri == null) { Toast.makeText(devr.shellshock.finban.data.AppLanguage.context(this), R.string.share_nothing, Toast.LENGTH_LONG).show(); return false }
            checkImage(uri)
            return true
        }
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(devr.shellshock.finban.data.AppLanguage.context(this), R.string.share_nothing, Toast.LENGTH_LONG).show()
            return false
        }
        linkJob?.cancel()
        state = SheetState.Loading
        linkCheck = LinkCheckState.Idle
        alertState = devr.shellshock.finban.net.AlertState.Idle
        shared = text.take(MAX_CHARS)
        check(text)
        return true
    }

    private var job: kotlinx.coroutines.Job? = null
    private var linkJob: kotlinx.coroutines.Job? = null

    /** The person tapped the button. The server fetches the link; this phone never contacts the site. */
    private fun checkLink(url: String) {
        linkJob?.cancel()
        linkCheck = LinkCheckState.Running
        linkJob = lifecycleScope.launch {
            linkCheck = try {
                LinkCheckState.Done(Api.client.checkLink(url))
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) LinkCheckState.Failed else throw e
            } catch (e: Exception) {
                EventLog.log(EventType.SERVICE, "link-check", "failed: ${e.javaClass.simpleName}") // never the URL
                LinkCheckState.Failed
            }
        }
    }

    private fun tellTrusted() {
        val r = (state as? SheetState.Result)?.response ?: return
        alertState = devr.shellshock.finban.net.AlertState.Sending
        lifecycleScope.launch { alertState = devr.shellshock.finban.net.CaretakerAlert.send(r.severity, r.patternId) }
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').take(60)

    private fun check(text: String) {
        job?.cancel()
        val capped = text.take(MAX_CHARS)
        val links = Extractors.find(capped).filter { it.kind == Extractors.Kind.URL }.map { it.text }.distinct().take(MAX_LINKS)
        linkUrl = links.firstOrNull() // the one the person can ask us to look at more closely
        job = lifecycleScope.launch {
            state = try {
                val r = Api.client.analyze(AnalyzeRequest(source = "share", text = capped, links = links))
                val kind = if (fromPhoto) PictureKind.detect(capped) else null
                // Home list keeps only severity + "Message"/"Photo" + time. Never the message text.
                devr.shellshock.finban.data.RecentChecks.add(r.severity, r.title, if (fromPhoto) "photo" else "share", null, r.patternId, r.reasons.map { it.text }, r.explanation, body = capped.takeUnless { kind?.isId == true })
                SheetState.Result(r, kind?.let { devr.shellshock.finban.ui.sheet.PictureInfo(null, "none", null, it) })
            } catch (e: TimeoutCancellationException) {
                EventLog.log(EventType.SERVICE, "analyze", "timeout")
                SheetState.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.log(EventType.SERVICE, "analyze", "failed: $e") // class + message only, never the text
                SheetState.Failed // offline, server down, 4xx/5xx, bad JSON: all the same message, no local rules in the prototype
            }
        }
    }

    companion object { const val EXTRA_PICK = "pick_image"; private const val MAX_CHARS = 4000; private const val MAX_LINKS = 10 }
}
