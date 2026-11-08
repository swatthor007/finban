package devr.shellshock.finban.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * A picture of the screen, for screens whose text cannot be read from the accessibility tree (images, canvases, chat photos).
 * Android 11+ (takeScreenshot), needs canTakeScreenshot in the service config. Secure windows (FLAG_SECURE: banking and payment
 * apps, password screens) come back as a failure, which is the right answer. Held in memory only, re-encoded smaller (JPEG,
 * longest side 1280 px), never written to disk and never logged.
 */
object ScreenShot {
    private const val MAX_SIDE = 1280

    /** Payment apps are never photographed, even if they forget to mark their window secure. */
    private val NEVER = setOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp")

    fun supported() = Build.VERSION.SDK_INT >= 30
    fun allowedHere(pkg: String?) = pkg == null || pkg !in NEVER

    /** The whole screen (for reading text) and, when [crop] is given, just that rectangle at full resolution (for the AI-photo check: cropping keeps the picture's own pixels and drops the app around it). */
    class Shots(val full: ByteArray, val crop: ByteArray?)

    suspend fun takeJpeg(svc: AccessibilityService): ByteArray? = take(svc, null)?.full

    suspend fun take(svc: AccessibilityService, crop: android.graphics.Rect?): Shots? = suspendCancellableCoroutine { cont ->
        if (!supported()) { cont.resume(null); return@suspendCancellableCoroutine }
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val bytes: Shots? = try {
                        val hb = r.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle(); hb.close()
                        soft?.let { b ->
                            fun encode(src: Bitmap, quality: Int): ByteArray {
                                val scale = MAX_SIDE.toFloat() / maxOf(src.width, src.height)
                                val out = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
                                return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, quality, it); if (out !== src) out.recycle() }.toByteArray()
                            }
                            val cropBytes = crop?.let { r ->
                                val l = r.left.coerceIn(0, b.width - 1); val t = r.top.coerceIn(0, b.height - 1)
                                val w = (r.right.coerceIn(l + 1, b.width) - l); val h = (r.bottom.coerceIn(t + 1, b.height) - t)
                                if (w < 64 || h < 64) null else Bitmap.createBitmap(b, l, t, w, h).let { c -> encode(c, 92).also { if (c !== b) c.recycle() } }   // createBitmap returns the source itself for a full-size crop
                            }
                            Shots(encode(b, 80), cropBytes).also { b.recycle() }
                        }
                    } catch (e: Throwable) { EventLog.log(EventType.CAPTURE, "screenshot", "encode failed: ${e.javaClass.simpleName} ${e.message} crop=$crop"); null }
                    if (cont.isActive) cont.resume(bytes)
                }
                override fun onFailure(errorCode: Int) {
                    EventLog.log(EventType.CAPTURE, "screenshot", "failed code=$errorCode") // 3 = secure window, 2 = too soon after the last one
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Throwable) {
            EventLog.log(EventType.CAPTURE, "screenshot", "error: ${e.javaClass.simpleName}")
            if (cont.isActive) cont.resume(null)
        }
    }
}
