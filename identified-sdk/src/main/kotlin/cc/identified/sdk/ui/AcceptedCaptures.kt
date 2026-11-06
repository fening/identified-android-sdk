package cc.identified.sdk.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import cc.identified.sdk.core.DocType
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal data class AcceptedCapture(val docType: String, val jpeg: ByteArray?, val pdf: Boolean)

/** At most five 320px/128KiB thumbnails. Never retains upload bytes or PDF data. */
internal class AcceptedCaptures {
    var items by mutableStateOf<List<AcceptedCapture>>(emptyList())
        private set

    fun record(docType: String, data: ByteArray, mime: String) {
        if (
            docType !in
                setOf(
                    DocType.governmentId,
                    DocType.licenseFront,
                    DocType.licenseBack,
                    DocType.selfie,
                    DocType.proofOfAddress,
                )
        )
            return
        val pdf = mime.substringBefore(';').equals("application/pdf", ignoreCase = true)
        val thumbnail =
            if (pdf) null
            else {
                runCatching { thumbnail(data) }.getOrNull() ?: return
            }
        val entry = AcceptedCapture(docType, thumbnail, pdf)
        items =
            if (items.any { it.docType == docType })
                items.map { if (it.docType == docType) entry else it }
            else (items + entry).takeLast(5)
    }

    fun clear() {
        items = emptyList()
    }

    private fun thumbnail(data: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        val bitmap =
            BitmapFactory.decodeByteArray(
                data,
                0,
                data.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
        var scaled: Bitmap? = null
        try {
            val scale = minOf(1f, 320f / maxOf(bitmap.width, bitmap.height))
            val thumbnailBitmap =
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            scaled = thumbnailBitmap
            return ByteArrayOutputStream().use { output ->
                if (!thumbnailBitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)) return null
                output.toByteArray().takeIf { it.size <= 128 * 1024 }
            }
        } finally {
            if (scaled !== bitmap) scaled?.recycle()
            bitmap.recycle()
        }
    }
}
