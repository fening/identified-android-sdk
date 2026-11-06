package cc.identified.sdk.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedFile(val data: ByteArray, val mimeType: String)

/** All picker bytes and normalised images stay in memory. Bounds are checked before decoding. */
internal object FilePreparation {
    suspend fun read(context: Context, uri: Uri, maxBytes: Int): PreparedFile =
        withContext(Dispatchers.IO) {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0))
                        require(cursor.getLong(0) <= maxBytes) {
                            "That file is too large. Keep it under ${maxBytes / 1048576} MB."
                        }
                }
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val data =
                context.contentResolver.openInputStream(uri)?.use { readBounded(it, maxBytes) }
                    ?: throw IllegalArgumentException(
                        "We could not read that file on this device. Try a different one."
                    )
            prepare(data, mime, maxBytes)
        }

    internal fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var count = 0
        while (true) {
            val read =
                stream.read(
                    buffer,
                    0,
                    minOf(buffer.size.toLong(), maxBytes.toLong() - count + 1).toInt(),
                )
            if (read < 0) break
            count += read
            require(count <= maxBytes) {
                "That file is too large. Keep it under ${maxBytes / 1048576} MB."
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    internal fun prepare(data: ByteArray, mimeType: String, maxBytes: Int): PreparedFile {
        require(data.size <= maxBytes) {
            "That file is too large. Keep it under ${maxBytes / 1048576} MB."
        }
        if (mimeType == "application/pdf") return PreparedFile(data, mimeType)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "We could not read that file on this device. Try a different one."
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
        var bitmap =
            BitmapFactory.decodeByteArray(
                data,
                0,
                data.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: throw IllegalArgumentException("That file could not be prepared.")
        try {
            val orientation =
                runCatching {
                        ExifInterface(ByteArrayInputStream(data))
                            .getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            )
                    }
                    .getOrDefault(1)
            val matrix =
                Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> setScale(1f, -1f)
                        5 -> {
                            setRotate(90f)
                            postScale(-1f, 1f)
                        }
                        6 -> setRotate(90f)
                        7 -> {
                            setRotate(-90f)
                            postScale(-1f, 1f)
                        }
                        8 -> setRotate(-90f)
                    }
                }
            if (!matrix.isIdentity) {
                val rotated =
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
            }
            val longest = max(bitmap.width, bitmap.height)
            if (longest > 2000) {
                val scale = 2000.0 / longest
                val scaled =
                    Bitmap.createScaledBitmap(
                        bitmap,
                        max(1, (bitmap.width * scale).toInt()),
                        max(1, (bitmap.height * scale).toInt()),
                        true,
                    )
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                "That file could not be prepared."
            }
            val bytes = output.toByteArray()
            require(bytes.size <= maxBytes) {
                "That file is too large. Keep it under ${maxBytes / 1048576} MB."
            }
            return PreparedFile(bytes, "image/jpeg")
        } finally {
            bitmap.recycle()
        }
    }
}
