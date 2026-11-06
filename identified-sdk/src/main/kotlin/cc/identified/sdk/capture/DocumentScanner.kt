package cc.identified.sdk.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import cc.identified.sdk.capture.core.DocumentQuality
import cc.identified.sdk.capture.core.PixelRect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

internal data class DocumentObservation(val page: PageQuad?, val quality: DocumentQuality)

/** OpenCV only sees a bounded local image. A native load or processing failure leaves manual capture usable. */
internal object DocumentScanner {
    private const val analysisSide = 640
    private const val qualitySide = 384
    private const val outputSide = 2560
    private val available by lazy { safely { OpenCVLoader.initLocal() } == true }

    fun observe(bitmap: Bitmap, viewport: PixelRect): DocumentObservation? = scan(bitmap, viewport) { small ->
        val page = detect(small)
        val flattened = page?.let { rectify(small, fullBounds(small), it, qualitySide) }
        val qualityImage = flattened ?: small
        try {
            val frame = CameraPixels.frame(qualityImage, fullBounds(qualityImage), qualityImage.width, qualityImage.height)
            DocumentObservation(page, DocumentQuality.assess(frame))
        } finally {
            flattened?.recycle()
        }
    }

    fun straighten(bitmap: Bitmap, viewport: PixelRect, expectedPage: PageQuad?): Bitmap? {
        // Detect in the actual still. Preview corners may be stale or misregistered across use cases.
        val page = scan(bitmap, viewport, ::detect) ?: return null
        if (expectedPage != null && !expectedPage.matches(page, .055)) return null
        return safely { rectify(bitmap, viewport, page, outputSide) }
    }

    private fun <T> scan(bitmap: Bitmap, viewport: PixelRect, action: (Bitmap) -> T?): T? {
        if (!available) return null
        return safely {
            val scale = minOf(1.0, analysisSide / maxOf(viewport.width, viewport.height))
            val width = (viewport.width * scale).roundToInt()
            val height = (viewport.height * scale).roundToInt()
            if (minOf(width, height) < 48) return@safely null
            val small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val transform = Matrix().apply {
                    setRectToRect(
                        RectF(viewport.x.toFloat(), viewport.y.toFloat(),
                            (viewport.x + viewport.width).toFloat(), (viewport.y + viewport.height).toFloat()),
                        RectF(0f, 0f, width.toFloat(), height.toFloat()),
                        Matrix.ScaleToFit.FILL,
                    )
                }
                Canvas(small).drawBitmap(bitmap, transform, Paint(Paint.FILTER_BITMAP_FLAG))
                action(small)
            } finally {
                small.recycle()
            }
        }
    }

    private fun detect(bitmap: Bitmap): PageQuad? {
        val rgba = Mat()
        val gray = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 40.0, 120.0)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val width = bitmap.width
            val height = bitmap.height
            val luma = ByteArray(width * height)
            gray.get(0, 0, luma)
            val candidates = contours.mapNotNull { contour ->
                val area = Imgproc.contourArea(contour)
                contour.takeIf { area in (width * height * .20)..(width * height * .92) }
                    ?.let { area to it }
            }.sortedByDescending { it.first }

            var page: PageQuad? = null
            for ((_, contour) in candidates.take(12)) {
                val corners = quadrilateral(contour, width, height) ?: continue
                if (!hasPageEdges(corners, luma, width, height)) continue
                val next = PageQuad(corners.map { PagePoint(it.x / width, it.y / height) })
                // A Canny border often yields two near-identical contours. Two distinct pages
                // are ambiguous, so leave the complete image for review.
                if (page != null && !page.matches(next, .025)) return null
                page = page ?: next
            }
            return page
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
            edges.release()
            gray.release()
            rgba.release()
        }
    }

    private fun quadrilateral(contour: MatOfPoint, width: Int, height: Int): List<Point>? {
        val curve = MatOfPoint2f(*contour.toArray())
        val approx = MatOfPoint2f()
        try {
            Imgproc.approxPolyDP(curve, approx, .018 * Imgproc.arcLength(curve, true), true)
            if (approx.total() != 4L) return null
            val points = approx.toArray()
            val cx = points.map { it.x }.average()
            val cy = points.map { it.y }.average()
            val ordered = points.sortedBy { atan2(it.y - cy, it.x - cx) }
            val start = ordered.indices.minBy { ordered[it].x + ordered[it].y }
            val corners = List(4) { ordered[(start + it) % 4] }
            val margin = maxOf(6.0, minOf(width, height) * .025)
            val shortestSide = minOf(width, height) * .22
            if (corners.any { it.x < margin || it.y < margin || it.x >= width - margin || it.y >= height - margin }) return null
            val sides = corners.indices.map { distance(corners[it], corners[(it + 1) % 4]) }
            if (sides.any { it < shortestSide } ||
                minOf(sides[0], sides[2]) < .52 * maxOf(sides[0], sides[2]) ||
                minOf(sides[1], sides[3]) < .52 * maxOf(sides[1], sides[3])
            ) return null
            for (i in corners.indices) {
                val a = corners[i]
                val b = corners[(i + 1) % 4]
                val c = corners[(i + 2) % 4]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val ex = c.x - b.x
                val ey = c.y - b.y
                if (dx * ey - dy * ex <= 0 ||
                    abs(dx * ex + dy * ey) > .68 * sides[i] * sides[(i + 1) % 4]
                ) return null
            }
            val horizontal = maxOf(sides[0], sides[2])
            val vertical = maxOf(sides[1], sides[3])
            return corners.takeIf { horizontal / vertical in .40..2.5 }
        } finally {
            approx.release()
            curve.release()
        }
    }

    /** Require a paper-to-background transition along all four edges, with consistent polarity. */
    private fun hasPageEdges(corners: List<Point>, luma: ByteArray, width: Int, height: Int): Boolean {
        val inset = maxOf(3.0, minOf(width, height) * .009)
        fun sample(x: Double, y: Double): Int = luma[y.roundToInt() * width + x.roundToInt()].toInt() and 255
        var lightSides = 0
        var darkSides = 0
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val length = distance(a, b)
            val nx = (a.y - b.y) * inset / length
            val ny = (b.x - a.x) * inset / length
            var lighter = 0
            var darker = 0
            for (j in 1..9) {
                val t = j / 10.0
                val x = a.x + t * (b.x - a.x)
                val y = a.y + t * (b.y - a.y)
                val contrast = sample(x + nx, y + ny) - sample(x - nx, y - ny)
                if (contrast >= 16) lighter++
                if (contrast <= -16) darker++
            }
            if (lighter >= 6) lightSides++
            if (darker >= 6) darkSides++
        }
        return lightSides == 4 || darkSides == 4
    }

    private fun rectify(bitmap: Bitmap, viewport: PixelRect, page: PageQuad, limit: Int): Bitmap? {
        val points = page.points.map { Point(viewport.x + it.x * viewport.width, viewport.y + it.y * viewport.height) }
        val width = maxOf(distance(points[0], points[1]), distance(points[2], points[3]))
        val height = maxOf(distance(points[0], points[3]), distance(points[1], points[2]))
        val scale = minOf(1.0, limit / maxOf(width, height))
        val w = (width * scale).roundToInt()
        val h = (height * scale).roundToInt()
        if (minOf(w, h) < 12) return null
        val src = FloatArray(8) { i -> (if (i % 2 == 0) points[i / 2].x else points[i / 2].y).toFloat() }
        // Leave a narrow bleed so contour approximation does not shave off page-edge text.
        val dx = w * .008f
        val dy = h * .008f
        val dst = floatArrayOf(dx, dy, w - dx, dy, w - dx, h - dy, dx, h - dy)
        val transform = Matrix()
        if (!transform.setPolyToPoly(src, 0, dst, 0, 4)) return null
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            Canvas(result).drawBitmap(bitmap, transform, Paint(Paint.FILTER_BITMAP_FLAG))
            return result
        } catch (e: RuntimeException) {
            result.recycle()
            throw e
        }
    }

    private fun fullBounds(bitmap: Bitmap) = PixelRect(0.0, 0.0, bitmap.width.toDouble(), bitmap.height.toDouble())

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private inline fun <T> safely(action: () -> T): T? = try {
        action()
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }
}
