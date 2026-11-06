package cc.identified.sdk.capture

import android.graphics.Bitmap
import cc.identified.sdk.R
import cc.identified.sdk.capture.core.*
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark

internal data class CaptureFeedback(
    val hint: Int = R.string.identified_camera_starting,
    val ready: Boolean = false,
    val status: TurnStatus = TurnStatus.noFace,
    val yaw: Double = 0.0,
    val distanceLevel: Int = 2,
    val dark: Boolean = false,
    val countdown: Int? = null,
    val sweep: Float = 0f,
    val phase: TurnPhase = TurnPhase.position,
    val manual: Boolean = false,
    val timedWaiting: Boolean = false,
    val timed: Boolean = false,
    val autoCapture: Boolean = false,
    val sourceAspect: Float? = null,
    val completed: Map<String, ByteArray>? = null,
    val documentPage: PageQuad? = null,
)

/** Called exclusively on the camera's serial analysis executor. No Android lifecycle state. */
internal class CaptureProcessor(
    private val kind: CaptureKind,
    private val documentScanner: DocumentScanner? = DocumentScanner,
) {
    private val gate = AutoCaptureGate()
    private val pageGate = PageCaptureGate()
    private val challenge = TurnChallenge()
    private val light = SceneLight()
    private val frames = linkedMapOf<String, ByteArray>()
    private var first: Double? = null
    private var lastLight = Double.NEGATIVE_INFINITY
    private var sawFace = false
    private var waiting = false
    private var timedAt: Double? = null
    private var manualRequested = false

    fun manual() {
        manualRequested = true
    }

    fun startTimed(now: Double) {
        if (waiting) {
            timedAt = now
            waiting = false
        }
    }

    fun captureFailed() {
        gate.reset()
        pageGate.reset()
    }

    fun process(
        bitmap: Bitmap,
        view: PixelSize,
        now: Double,
        faces: List<Face> = emptyList(),
    ): CaptureFeedback =
        processObserved(
            bitmap,
            view,
            now,
            faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.observation(),
        )

    /**
     * Platform observation boundary; instrumentation can supply landmarks without constructing ML
     * Kit internals.
     */
    fun processObserved(
        bitmap: Bitmap,
        view: PixelSize,
        now: Double,
        face: FaceObservation?,
    ): CaptureFeedback {
        if (kind == CaptureKind.DOCUMENT) return processDocument(bitmap, view, now)
        if (kind == CaptureKind.CARD) {
            val visible = PixelRect(0.0, 0.0, bitmap.width.toDouble(), bitmap.height.toDouble())
            val size = CaptureGeometry.analysisSize(visible.width / visible.height)
            val frame = CameraPixels.frame(bitmap, visible, size.width, size.height)
            val assessment = CardDetector.assess(frame)
            return CaptureFeedback(
                hint =
                    when (assessment.hint) {
                        CardHint.fitEdges -> R.string.identified_card_hint_fit_edges
                        CardHint.moveToLight -> R.string.identified_card_hint_light
                        CardHint.removeGlare -> R.string.identified_card_hint_glare
                        CardHint.holdStillCloser -> R.string.identified_card_hint_closer
                        CardHint.capturing -> R.string.identified_card_hint_capturing
                    },
                ready = assessment.ready,
                autoCapture = gate.observe(assessment, frame.width, frame.height, now),
                sourceAspect = bitmap.width.toFloat() / bitmap.height,
            )
        }
        val source = PixelSize(bitmap.width.toDouble(), bitmap.height.toDouble())
        val framing = CaptureGeometry.selfieFramingRect(source, view) ?: return CaptureFeedback()
        val captureRect = CaptureGeometry.selfieCaptureRect(source, view) ?: return CaptureFeedback()
        val fullSource = PixelRect(0.0, 0.0, source.width, source.height)
        if (first == null) first = now
        if (now - lastLight >= SceneLight.sampleInterval) {
            light.observe(SceneLight.meanLuminance(CameraPixels.frame(bitmap, fullSource, 48, 64)))
            lastLight = now
        }
        if (face != null) sawFace = true
        if (!sawFace && timedAt == null && now - first!! >= 5) waiting = true
        if (waiting)
            return CaptureFeedback(
                hint = R.string.identified_selfie_timed_ready,
                dark = light.dark,
                timedWaiting = true,
            )
        timedAt?.let { start ->
            val elapsed = now - start
            for (step in TimedTurnFallback.captures) if (
                elapsed >= step.at && frames[step.role] == null
            ) {
                frames[step.role] = CameraPixels.still(bitmap, view, kind)
            }
            return CaptureFeedback(
                hint =
                    when {
                        elapsed < 2.4 -> R.string.identified_selfie_timed_left
                        elapsed < 3.3 -> R.string.identified_selfie_timed_center
                        else -> R.string.identified_selfie_timed_right
                    },
                dark = light.dark,
                timed = true,
                sweep = (elapsed / 6).toFloat().coerceIn(0f, 1f),
                completed = frames.takeIf { it.size == 3 }?.toMap(),
            )
        }
        // Accept a tap against the state that offered it. A newly good frame resets
        // stallSince in update(), so checking the tap afterwards would lose it.
        val manualRole = if (manualRequested) challenge.pressShutter(now) else null
        manualRequested = false
        val update =
            challenge.update(face, framing, now, captureBounds = captureRect)
        val role = manualRole ?: update.capture
        role?.let {
            try {
                frames[it] = CameraPixels.still(bitmap, view, kind)
                challenge.didCapture(it, now)
            } catch (_: Exception) {
                challenge.captureFailed()
            }
        }
        val phase = challenge.phase
        val turn = if (phase == TurnPhase.left) -update.yaw / TurnChallenge.side else update.yaw / TurnChallenge.side
        val hint =
            when {
                phase == TurnPhase.done -> R.string.identified_selfie_hint_done
                update.status == TurnStatus.noFace || update.status == TurnStatus.offCenter -> R.string.identified_selfie_hint_center
                update.status == TurnStatus.far -> R.string.identified_selfie_hint_closer
                update.status == TurnStatus.near -> R.string.identified_selfie_hint_back
                update.status == TurnStatus.lookStraight -> R.string.identified_selfie_hint_straight
                phase == TurnPhase.position -> R.string.identified_selfie_hint_hold_photo
                (phase == TurnPhase.left || phase == TurnPhase.right) && turn >= 1 -> R.string.identified_selfie_hint_perfect
                phase == TurnPhase.left -> if (turn >= .35) R.string.identified_selfie_hint_keep_left else R.string.identified_selfie_hint_turn_left
                phase == TurnPhase.center -> R.string.identified_selfie_hint_centre
                phase == TurnPhase.right -> if (turn >= .35) R.string.identified_selfie_hint_keep_right else R.string.identified_selfie_hint_turn_right
                else -> R.string.identified_selfie_hint_hold_photo
            }
        return CaptureFeedback(
            hint = hint,
            status = update.status,
            yaw = update.yaw,
            distanceLevel = update.distanceLevel,
            dark = light.dark,
            countdown = update.countdown,
            sweep = update.sweep.toFloat(),
            phase = phase,
            manual = update.shutterAvailable,
            completed = frames.takeIf { update.completed && it.size == 3 }?.toMap(),
        )
    }

    private fun processDocument(bitmap: Bitmap, view: PixelSize, now: Double): CaptureFeedback {
        val visible = CameraPixels.visible(bitmap, view)
        val observation = documentScanner?.observe(bitmap, visible)
        val quality = observation?.quality ?: run {
            val size = CaptureGeometry.analysisSize(visible.width / visible.height)
            DocumentQuality.assess(CameraPixels.frame(bitmap, visible, size.width, size.height))
        }
        val page = observation?.page
        val ready = page != null && quality.ready
        val autoCapture = pageGate.observe(page, quality.ready, now)
        val hint = when {
            autoCapture -> R.string.identified_poa_hint_capturing
            ready -> R.string.identified_poa_hint_steady
            quality.hint == DocumentHint.tooDark -> R.string.identified_poa_hint_dark
            quality.hint == DocumentHint.tooBright -> R.string.identified_poa_hint_bright
            quality.hint == DocumentHint.tooMuchGlare -> R.string.identified_poa_hint_glare
            observation != null && page == null -> R.string.identified_poa_hint_fit_page
            quality.hint == DocumentHint.blurry -> R.string.identified_poa_hint_blurry
            else -> R.string.identified_poa_hint_sharp
        }
        return CaptureFeedback(hint = hint, ready = ready, autoCapture = autoCapture, documentPage = page)
    }

    private fun Face.observation(): FaceObservation? {
        val a = getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val b = getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val n = getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        val box = boundingBox
        return FaceObservation(
            PixelRect(
                box.left.toDouble(),
                box.top.toDouble(),
                box.width().toDouble(),
                box.height().toDouble(),
            ),
            FacePoint(a.x.toDouble(), a.y.toDouble()),
            FacePoint(b.x.toDouble(), b.y.toDouble()),
            FacePoint(n.x.toDouble(), n.y.toDouble()),
        )
    }
}
