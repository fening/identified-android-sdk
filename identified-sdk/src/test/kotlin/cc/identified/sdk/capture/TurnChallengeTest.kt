package cc.identified.sdk.capture

import cc.identified.sdk.capture.CaptureFixtures.face
import cc.identified.sdk.capture.CaptureFixtures.feed
import cc.identified.sdk.capture.CaptureFixtures.firstCapture
import cc.identified.sdk.capture.CaptureFixtures.view
import cc.identified.sdk.capture.core.*
import org.junit.Assert.*
import org.junit.Test

class TurnChallengeTest {
    @Test
    fun computesYawLikeTheWebRegardlessOfEyeOrder() {
        for (yaw in listOf(-0.4, -0.2, 0.0, 0.4)) {
            val f = face(yaw)
            assertEquals(yaw, TurnChallenge.yaw(f), 1e-9)
            assertEquals(
                yaw,
                TurnChallenge.yaw(f.copy(firstEye = f.secondEye, secondEye = f.firstEye)),
                1e-9,
            )
        }
    }

    @Test
    fun coincidentOrAlmostCoincidentEyesProduceZeroYaw() {
        val f = face()
        for (delta in listOf(0.0, 0.00001)) {
            assertEquals(
                0.0,
                TurnChallenge.yaw(f.copy(secondEye = f.firstEye.copy(x = f.firstEye.x + delta))),
                0.0,
            )
        }
    }

    @Test
    fun capturesTheFrontalFrameAfterSettleAndCountdown() {
        val c = TurnChallenge()
        val settling = feed(c, face(), 0.0, 1.15)
        assertTrue(
            settling.all {
                it.status == TurnStatus.hold &&
                    it.countdown == null &&
                    it.capture == null &&
                    it.sweep == 0.0
            }
        )
        val counting = feed(c, face(), 1.2, 4.15)
        assertEquals(3, counting.first().countdown)
        assertTrue(counting.any { it.countdown == 2 })
        assertTrue(counting.any { it.countdown == 1 })
        assertNull(firstCapture(counting))
        val capture = c.update(face(), view, 4.25)
        assertEquals("center", capture.capture)
        assertEquals(1.0, capture.sweep, 0.0)
        assertNull(capture.countdown)
        assertEquals(TurnPhase.position, c.phase)
        assertTrue(c.roles.isEmpty())
    }

    @Test
    fun countdownSweepAndBoundaryValues() {
        val c = TurnChallenge()
        c.update(face(), view, 0.0)
        for ((time, count) in
            listOf(1.199 to null, 1.2 to 3, 2.2 to 2, 3.2 to 1, 4.199 to 1, 4.2 to null)) {
            assertEquals("countdown at $time", count, TurnChallenge.countdownValue(time))
        }
        assertEquals(0.5, c.update(face(), view, 2.7).sweep, 1e-12)
        assertEquals("center", c.update(face(), view, 4.2).capture)
    }

    @Test
    fun doesNotAskForASecondCaptureWhileOneIsInFlight() {
        val c = TurnChallenge()
        assertEquals("center", firstCapture(feed(c, face(), 0.0, 4.3)))
        assertNull(firstCapture(feed(c, face(), 4.35, 5.0)))
        assertNull(c.pressShutter(100.0))
    }

    @Test
    fun walksLeftCentreRightAndCompletesExactlyOnce() {
        val c = CaptureFixtures.leftPhase()
        assertEquals(TurnPhase.left, c.phase)
        assertNull(firstCapture(feed(c, face(-0.32), 5.0, 7.0)))
        assertEquals("left", firstCapture(feed(c, face(-0.4), 7.05, 8.0)))
        c.didCapture("left")
        assertEquals(TurnPhase.center, c.phase)
        assertNull(c.update(face(0.05), view, 8.5).capture)
        assertEquals(TurnPhase.right, c.phase)
        assertEquals("right", firstCapture(feed(c, face(0.4), 9.0, 10.0)))
        c.didCapture("right", 10.0)
        assertEquals(TurnPhase.done, c.phase)
        assertFalse(c.update(null, view, 10.5).completed)
        assertTrue(c.update(null, view, 10.95).completed)
        assertFalse(c.update(null, view, 11.0).completed)
        assertEquals(listOf("center", "left", "right"), c.roles)
        assertNull(c.update(face(), view, 100.0).capture)
    }

    @Test
    fun aTurnMustBeHeld() {
        val c = CaptureFixtures.leftPhase()
        assertNull(firstCapture(feed(c, face(-0.4), 5.0, 5.5)))
        assertNull(firstCapture(feed(c, face(), 5.55, 6.0)))
        assertNull(firstCapture(feed(c, face(-0.4), 6.05, 6.6)))
        assertEquals("left", c.update(face(-0.4), view, 6.8).capture)
    }

    @Test
    fun losingFramingRestartsTheFrontalCountdown() {
        for (bad in
            listOf(null, face(size = 0.3), face(size = 0.8), face(cx = 0.2), face(yaw = 0.3))) {
            val c = TurnChallenge()
            feed(c, face(), 0.0, 3.0)
            c.update(bad, view, 3.05)
            assertNull(firstCapture(feed(c, face(), 3.1, 7.0)))
            assertEquals("center", firstCapture(feed(c, face(), 7.05, 7.5)))
        }
    }

    @Test
    fun reportsWhatIsWrongWithTheFraming() {
        data class Case(val face: FaceObservation?, val status: TurnStatus, val level: Int)
        val cases =
            listOf(
                Case(null, TurnStatus.noFace, 2),
                Case(face(size = 0.3), TurnStatus.far, 2),
                Case(face(size = 0.25), TurnStatus.far, 1),
                Case(face(size = 0.8), TurnStatus.near, 3),
                Case(face(cx = 0.2), TurnStatus.offCenter, 2),
                Case(face(cy = 0.8), TurnStatus.offCenter, 2),
                Case(face(yaw = 0.3), TurnStatus.lookStraight, 2),
            )
        for (case in cases) {
            val u = TurnChallenge().update(case.face, view, 0.0)
            assertEquals(case.status, u.status)
            assertEquals(case.level, u.distanceLevel)
            assertNull(u.capture)
        }
    }

    @Test
    fun framingIsRelativeToVisibleOriginAndOffFrameTakesPriority() {
        val f = face()
        val translated =
            f.copy(
                box = f.box.copy(x = f.box.x + 100, y = f.box.y + 200),
                firstEye = f.firstEye.copy(x = f.firstEye.x + 100, y = f.firstEye.y + 200),
                secondEye = f.secondEye.copy(x = f.secondEye.x + 100, y = f.secondEye.y + 200),
                nose = f.nose.copy(x = f.nose.x + 100, y = f.nose.y + 200),
            )
        assertEquals(
            TurnChallenge().update(f, view, 0.0),
            TurnChallenge().update(translated, view.copy(x = 100.0, y = 200.0), 0.0),
        )
        val offFrame = f.copy(box = f.box.copy(x = -5.0, width = 1000.0))
        val u = TurnChallenge().update(offFrame, view, 0.0)
        assertEquals(TurnStatus.offCenter, u.status)
        assertEquals(3, u.distanceLevel)
    }

    @Test
    fun rejectsFaceInsidePreviewButOutsideActualSelfieUploadSquare() {
        val visible = PixelRect(0.0, 0.0, 600.0, 1000.0)
        // A 500px square around the selfie guide at 42% of preview height.
        val captureBounds = PixelRect(50.0, 170.0, 500.0, 500.0)
        val observation =
            FaceObservation(
                PixelRect(200.0, 580.0, 200.0, 240.0),
                FacePoint(260.0, 660.0),
                FacePoint(340.0, 660.0),
                FacePoint(300.0, 700.0),
            )
        val broad = TurnChallenge()
        assertEquals(TurnStatus.hold, broad.update(observation, visible, 0.0).status)
        assertEquals("center", broad.update(observation, visible, 4.25).capture)

        val cropped = TurnChallenge()
        for (time in listOf(0.0, 1.2, 4.25, 7.0)) {
            val update = cropped.update(observation, visible, time, captureBounds = captureBounds)
            assertEquals(TurnStatus.offCenter, update.status)
            assertNull(update.capture)
            assertNull(update.countdown)
            assertEquals(0.0, update.sweep, 0.0)
            assertEquals(2, update.distanceLevel)
        }
        assertTrue(cropped.roles.isEmpty())
    }

    @Test
    fun acceptsCenteredFaceInsideActualSelfieUploadSquare() {
        val visible = PixelRect(0.0, 0.0, 600.0, 1000.0)
        val captureBounds = PixelRect(50.0, 170.0, 500.0, 500.0)
        val observation =
            FaceObservation(
                PixelRect(200.0, 300.0, 200.0, 240.0),
                FacePoint(260.0, 380.0),
                FacePoint(340.0, 380.0),
                FacePoint(300.0, 420.0),
            )
        val broad = TurnChallenge()
        val cropped = TurnChallenge()
        for (time in listOf(0.0, 1.2, 2.7, 4.25)) {
            // Normalized size, center, yaw and countdown still use the visible preview.
            assertEquals(
                broad.update(observation, visible, time),
                cropped.update(observation, visible, time, captureBounds = captureBounds),
            )
        }
        cropped.captureFailed()
        assertEquals("center", cropped.update(observation, visible, 4.3, captureBounds).capture)
    }

    @Test
    fun explicitVisibleCaptureBoundsPreserveDefaultBehavior() {
        val implicit = TurnChallenge()
        val explicit = TurnChallenge()
        for ((observation, time) in
            listOf(null to 0.0, face(size = 0.3) to 0.1, face() to 1.0, face() to 5.25)) {
            assertEquals(
                implicit.update(observation, view, time),
                explicit.update(observation, view, time, captureBounds = view),
            )
        }
    }

    @Test
    fun centerAndSideThresholdsHaveCorrectSignAndStrictness() {
        val left = CaptureFixtures.leftPhase()
        assertNull(firstCapture(feed(left, face(0.4), 5.0, 6.0)))
        assertNull(firstCapture(feed(left, face(-0.379), 6.1, 7.1)))
        assertEquals("left", firstCapture(feed(left, face(-0.381), 7.2, 8.2)))
        val right = CaptureFixtures.rightPhase()
        assertNull(firstCapture(feed(right, face(-0.4), 7.0, 8.0)))
        assertNull(firstCapture(feed(right, face(0.379), 8.1, 9.1)))
        assertEquals("right", firstCapture(feed(right, face(0.381), 9.2, 10.2)))
        for (yaw in listOf(-0.141, 0.141)) assertEquals(
            TurnStatus.lookStraight,
            TurnChallenge().update(face(yaw), view, 0.0).status,
        )
        for (yaw in listOf(-0.139, 0.139)) assertEquals(
            TurnStatus.hold,
            TurnChallenge().update(face(yaw), view, 0.0).status,
        )
    }

    @Test
    fun badFramingMidTurnRestartsHoldOnBothSides() {
        for (right in listOf(false, true)) {
            for (bad in listOf(null, face(size = 0.25), face(size = 0.8), face(cx = 0.2))) {

                val c = if (right) CaptureFixtures.rightPhase() else CaptureFixtures.leftPhase()
                val turned = face(if (right) 0.4 else -0.4)
                assertNull(c.update(turned, view, 10.0).capture)
                assertNull(c.update(turned, view, 10.6).capture)
                assertNull(c.update(bad, view, 10.65).capture)
                assertNull(c.update(turned, view, 10.7).capture)
                assertNull(c.update(turned, view, 11.3).capture)
                assertEquals(if (right) "right" else "left", c.update(turned, view, 11.45).capture)
            }
        }
    }

    @Test
    fun returningToCenterRequiresGoodFramingBeforeRightPhase() {
        val c = CaptureFixtures.leftPhase()
        c.update(face(-0.4), view, 5.0)
        c.update(face(-0.4), view, 5.75)
        c.didCapture("left")
        for (bad in listOf(null, face(yaw = 0.3), face(size = 0.25), face(cx = 0.2))) {
            assertNull(c.update(bad, view, 6.0).capture)
            assertEquals(TurnPhase.center, c.phase)
        }
        assertNull(c.update(face(0.05), view, 6.5).capture)
        assertEquals(TurnPhase.right, c.phase)
    }

    @Test
    fun offersTheShutterAfterEightSecondsOfFailedFraming() {
        val c = TurnChallenge()
        assertTrue(feed(c, null, 0.0, 7.9).all { !it.shutterAvailable })
        assertNull(c.pressShutter(7.9))
        assertTrue(c.update(null, view, 8.0).shutterAvailable)
        assertEquals("center", c.pressShutter(8.0))
        assertFalse(c.update(null, view, 8.05).shutterAvailable)
        assertNull(c.pressShutter(8.1))
        c.didCapture("center")
        assertNull(c.pressShutter(100.0))
    }

    @Test
    fun goodFramingClearsShutterStallAndNextBadPoseStartsANewTimer() {
        val c = TurnChallenge()
        assertNull(c.pressShutter(100.0)) // No failed frame observed yet.
        c.update(null, view, 0.0)
        assertFalse(c.update(face(), view, 7.0).shutterAvailable)
        c.update(face(yaw = 0.3), view, 7.1)
        assertFalse(c.update(null, view, 15.09).shutterAvailable)
        assertTrue(c.update(null, view, 15.11).shutterAvailable)
    }

    @Test
    fun aFailedFrontalCaptureCanBeRequestedAgainWithoutAnotherCountdown() {
        val c = TurnChallenge()
        feed(c, face(), 0.0, 4.3)
        c.captureFailed()
        assertEquals("center", firstCapture(feed(c, face(), 4.35, 4.4)))
        assertTrue(c.roles.isEmpty())
        assertEquals(TurnPhase.position, c.phase)
    }

    @Test
    fun failedSideCaptureMustBeHeldAgainAndDoesNotDuplicateRoles() {
        val c = CaptureFixtures.leftPhase()
        c.update(face(-0.4), view, 5.0)
        assertEquals("left", c.update(face(-0.4), view, 5.75).capture)
        c.captureFailed()
        assertNull(c.update(face(-0.4), view, 5.8).capture)
        assertNull(c.update(face(-0.4), view, 6.4).capture)
        assertEquals("left", c.update(face(-0.4), view, 6.55).capture)
        assertEquals(listOf("center"), c.roles)
        c.didCapture("left")
        c.didCapture("left")
        assertEquals(listOf("center", "left"), c.roles)
    }

    @Test
    fun acknowledgesOnlyTheRequestedRole() {
        val c = TurnChallenge()
        c.didCapture("center")
        assertEquals(TurnPhase.position, c.phase)
        c.update(face(), view, 0.0)
        c.update(face(), view, 4.25)
        c.didCapture("left")
        assertEquals(TurnPhase.position, c.phase)
        assertTrue(c.roles.isEmpty())
        assertNull(c.update(face(), view, 4.3).capture)
        c.didCapture("center")
        assertEquals(TurnPhase.left, c.phase)
    }

    @Test
    fun resetClearsEveryPhaseIncludingInFlightAndCompletionState() {
        val done = CaptureFixtures.rightPhase()
        done.update(face(0.4), view, 7.0)
        done.update(face(0.4), view, 7.75)
        done.didCapture("right", 8.0)
        assertTrue(done.update(null, view, 9.0).completed)
        val pending = TurnChallenge()
        feed(pending, face(), 0.0, 4.3)
        val stalled = TurnChallenge()
        stalled.update(null, view, 0.0)
        for (c in
            listOf(
                done,
                pending,
                stalled,
                CaptureFixtures.leftPhase(),
                CaptureFixtures.rightPhase(),
            )) {
            c.reset()
            assertEquals(TurnPhase.position, c.phase)
            assertTrue(c.roles.isEmpty())
            assertNull(c.pressShutter(100.0))
            assertFalse(c.update(face(), view, 100.0).completed)
            assertNull(c.update(face(), view, 104.1).capture)
            assertEquals("center", c.update(face(), view, 104.25).capture)
        }
    }

    @Test
    fun theFallbackTakesOneFrameAtTheEndOfEachPose() {
        assertEquals(6.0, TimedTurnFallback.duration, 0.0)
        assertEquals(listOf("left", "center", "right"), TimedTurnFallback.captures.map { it.role })
        for ((index, time) in listOf(2.4, 3.3, 6.0).withIndex()) assertEquals(
            time,
            TimedTurnFallback.captures[index].at,
            1e-9,
        )
    }

    @Test
    fun uploadsTheCentreFrameLastPreservingOtherOrderingAndDuplicates() {
        assertEquals(
            listOf("left", "right", "center"),
            TurnChallenge.uploadOrder(listOf("center", "left", "right")),
        )
        assertEquals(
            listOf("right", "left", "center", "center"),
            TurnChallenge.uploadOrder(listOf("center", "right", "center", "left")),
        )
        assertEquals(emptyList<String>(), TurnChallenge.uploadOrder(emptyList()))
    }
}
