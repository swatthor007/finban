package devr.shellshock.finban.ui.share

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device OCR (ML Kit, bundled Latin model). The photo never leaves the phone through this path.
 * Latin script only: ML Kit has no Tamil model, so Tamil text in a photo is not read (TODO(hackathon): Tamil OCR).
 * Returns "" when nothing readable, null on failure.
 */
suspend fun readText(ctx: Context, uri: Uri): String? = suspendCancellableCoroutine { cont ->
    val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        rec.process(InputImage.fromFilePath(ctx, uri))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it.text.trim()) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
    cont.invokeOnCancellation { rec.close() }
}

/** Longest side 1600 px, JPEG 85. Keeps uploads small and drops EXIF (location) because it is re-encoded. null on failure. */
fun downscaledJpeg(ctx: Context, uri: Uri): ByteArray? = try {
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }
    bmp?.let { b -> java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it); b.recycle() }.toByteArray() }
} catch (e: Exception) { null }
