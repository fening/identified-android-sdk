package cc.identified.sdk.capture.core

import kotlin.math.*

internal data class FacePoint(var x: Double, var y: Double)

internal data class FaceObservation(
    var box: PixelRect,
    var firstEye: FacePoint,
    var secondEye: FacePoint,
    var nose: FacePoint,
)

internal enum class TurnPhase {
    position,
    left,
    center,
    right,
    done,
}

internal enum class TurnStatus {
    noFace,
    far,
    near,
    offCenter,
    lookStraight,
    hold,
    ok,
}

internal data class TurnUpdate(
    var status: TurnStatus = TurnStatus.noFace,
    var yaw: Double = 0.0,
    var countdown: Int? = null,
    var sweep: Double = 0.0,
    var distanceLevel: Int = 2,
    var shutterAvailable: Boolean = false,
    var capture: String? = null,
    var completed: Boolean = false,
)

internal class TurnChallenge {
    var phase = TurnPhase.position
        private set

    private val capturedRoles = mutableListOf<String>()
    val roles: List<String>
        get() = capturedRoles.toList()

    private var goodSince: Double? = null
    private var stallSince: Double? = null
    private var holdSince: Double? = null
    private var inFlight: String? = null
    private var doneAt: Double? = null
    private var completed = false

    /** Face containment follows the upload crop; pose normalization follows the preview. */
    fun update(
        face: FaceObservation?,
        visible: PixelRect,
        now: Double,
        captureBounds: PixelRect = visible,
    ): TurnUpdate {
        val u = TurnUpdate()
        if (phase == TurnPhase.done) {
            if (doneAt?.let { !completed && now - it >= finishDelay } == true) {
                completed = true
                u.completed = true
            }
            return u
        }
        if (face == null) {
            goodSince = null
            holdSince = null
            if (phase == TurnPhase.position) {
                if (stallSince == null) stallSince = now
                u.shutterAvailable = shutterAvailable(now)
            }
            return u
        }
        val circle = 2 * ringFraction * min(visible.width, visible.height)
        val faceWidth = face.box.width / circle
        val cx = (face.box.midX - visible.x) / visible.width
        val cy = (face.box.midY - visible.y) / visible.height
        val inFrame =
            face.box.x >= captureBounds.x - 4 &&
                face.box.y >= captureBounds.y - 4 &&
                face.box.x + face.box.width <= captureBounds.x + captureBounds.width + 4 &&
                face.box.y + face.box.height <= captureBounds.y + captureBounds.height + 4
        val centered = cx > 0.3 && cx < 0.7 && cy > 0.26 && cy < 0.74
        val distance =
            if (faceWidth < faceMin) TurnStatus.far
            else if (faceWidth > faceMax) TurnStatus.near else TurnStatus.ok
        u.distanceLevel =
            if (distance == TurnStatus.near) 3 else if (faceWidth < faceMin * 0.7) 1 else 2
        val good = distance == TurnStatus.ok && centered && inFrame
        val yaw = yaw(face)
        u.yaw = yaw
        u.status =
            if (!inFrame) TurnStatus.offCenter
            else if (distance != TurnStatus.ok) distance
            else if (!centered) TurnStatus.offCenter else TurnStatus.ok
        when (phase) {
            TurnPhase.position ->
                if (good && abs(yaw) < frontal) {
                    stallSince = null
                    if (goodSince == null) goodSince = now
                    val elapsed = now - goodSince!!
                    u.countdown = countdownValue(elapsed)
                    u.sweep = ((elapsed - settle) / countdown).coerceIn(0.0, 1.0)
                    if (elapsed >= settle + countdown) u.capture = request("center")
                    else u.status = TurnStatus.hold
                } else {
                    goodSince = null
                    if (stallSince == null) stallSince = now
                    u.shutterAvailable = shutterAvailable(now)
                    if (good) u.status = TurnStatus.lookStraight
                }
            TurnPhase.left -> u.capture = turn(good, yaw <= -side, "left", now)
            TurnPhase.center -> if (good && abs(yaw) < frontal) phase = TurnPhase.right
            TurnPhase.right -> u.capture = turn(good, yaw >= side, "right", now)
            TurnPhase.done -> Unit
        }
        return u
    }

    fun didCapture(role: String, at: Double = 0.0) {
        if (role != inFlight) return
        inFlight = null
        holdSince = null
        capturedRoles.add(role)
        phase =
            when (role) {
                "center" -> TurnPhase.left
                "left" -> TurnPhase.center
                else -> {
                    doneAt = at
                    TurnPhase.done
                }
            }
    }

    fun captureFailed() {
        inFlight = null
        holdSince = null
    }

    fun pressShutter(now: Double): String? =
        if (phase == TurnPhase.position && shutterAvailable(now)) request("center") else null

    fun reset() {
        phase = TurnPhase.position
        capturedRoles.clear()
        goodSince = null
        stallSince = null
        holdSince = null
        inFlight = null
        doneAt = null
        completed = false
    }

    private fun shutterAvailable(now: Double) =
        inFlight == null && stallSince?.let { now - it >= shutterEscape } == true

    private fun request(role: String): String? {
        if (inFlight != null) return null
        inFlight = role
        return role
    }

    private fun turn(good: Boolean, reached: Boolean, role: String, now: Double): String? {
        if (!good || !reached) {
            holdSince = null
            return null
        }
        if (holdSince == null) holdSince = now
        return if (now - holdSince!! >= hold) request(role) else null
    }

    companion object {
        const val side = 0.38
        const val frontal = 0.14
        const val faceMin = 0.42
        const val faceMax = 0.72
        const val hold = 0.7
        const val settle = 1.2
        const val countdown = 3.0
        const val shutterEscape = 8.0
        const val finishDelay = 0.9
        const val ringFraction = CaptureGeometry.ringFraction

        fun yaw(face: FaceObservation): Double {
            val mid = (face.firstEye.x + face.secondEye.x) / 2
            val inter = abs(face.firstEye.x - face.secondEye.x)
            return if (inter > 1e-4) -((face.nose.x - mid) / inter) else 0.0
        }

        fun countdownValue(elapsed: Double): Int? =
            if (elapsed >= settle && elapsed < settle + countdown)
                ceil(settle + countdown - elapsed).toInt()
            else null

        fun uploadOrder(roles: List<String>): List<String> =
            roles.filter { it != "center" } + roles.filter { it == "center" }
    }
}

internal object TimedTurnFallback {
    internal data class Capture(var role: String, var at: Double)

    const val duration = 6.0
    val captures =
        listOf(
            Capture("left", duration * 0.4),
            Capture("center", duration * 0.55),
            Capture("right", duration),
        )
}
