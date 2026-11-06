package cc.identified.sdk.ui

import cc.identified.sdk.*
import cc.identified.sdk.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowViewModelTest {
    private class Fake : FlowApi {
        var session =
            HostedSession(
                "s",
                "u",
                "p",
                "active",
                requiredDocuments = listOf("government_id", "selfie"),
            )
        var current = VerificationCase("c", "draft")
        val calls = mutableListOf<String>()
        var fail: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun currentSession(): HostedSession {
            calls += "session"
            fail?.let { throw it }
            return session
        }

        override suspend fun saveMetadata(patch: JsonObject) {
            calls += "metadata:$patch"
            gate?.await()
            fail?.let { throw it }
        }

        override suspend fun recordConsent(scopes: List<String>) {
            calls += "consent:$scopes"
        }

        override suspend fun getOrCreateCase(session: HostedSession): VerificationCase {
            calls += "create"
            return current
        }

        override suspend fun getCase(id: String): VerificationCase {
            calls += "case"
            fail?.let { throw it }
            return current
        }

        override suspend fun uploadDocument(
            id: String,
            type: String,
            role: String?,
            data: ByteArray,
            mime: String,
        ) {
            calls += "upload:$type:$role"
            fail?.let { throw it }
        }

        override suspend fun runBiometricCheck(id: String) {
            calls += "biometric"
        }

        override suspend fun submitCase(id: String): String {
            calls += "submit"
            return "submitted"
        }

        override suspend fun resolveDigitalAddress(code: String) = DigitalAddress(code, 1.0, 2.0)

        override suspend fun encodeDigitalAddress(latitude: Double, longitude: Double) =
            DigitalAddress("GA1839124", latitude, longitude)
    }

    private fun resume(f: Fake) {
        f.session =
            f.session.copy(
                caseId = "c",
                caseStatus = "draft",
                consentRecorded = true,
                firstName = "Ama",
                lastName = "Mensah",
            )
    }

    private suspend fun TestScope.withModel(
        block: suspend TestScope.(FlowViewModel, Fake) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = Fake()
            block(FlowViewModel(f), f)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun firstRunConsentCreatesCaseAndAdvances() = runTest {
        withModel { m, f ->
            m.load()
            runCurrent()
            m.begin()
            assertEquals(Route.Consent, m.state.value.route)
            m.consent()
            runCurrent()
            assertEquals(Route.Step(Destination.Checklist), m.state.value.route)
            assertEquals(listOf("session", "consent:[identity, biometric]", "create"), f.calls)
        }
    }

    @Test
    fun resumedMetadataSaveReturnsToChecklistAfterRefetch() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.open(Destination.YourName)
            m.saveName("Ama", "", "Mensah")
            runCurrent()
            assertEquals(Route.Step(Destination.Checklist), m.state.value.route)
            assertEquals("session", f.calls.last())
        }
    }

    @Test
    fun partialSelfieNeverUploadsAndCenterIsLast() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.uploadSelfie(mapOf("center" to byteArrayOf(1)))
            runCurrent()
            assertFalse(f.calls.any { it.startsWith("upload") })
            m.uploadSelfie(listOf("center", "left", "right").associateWith { byteArrayOf(1) })
            runCurrent()
            assertEquals(
                listOf(
                    "upload:selfie:left",
                    "upload:selfie:right",
                    "upload:selfie:center",
                    "biometric",
                ),
                f.calls.takeLast(4),
            )
        }
    }

    @Test
    fun duplicateSaveIsIgnoredWhileBusy() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.open(Destination.YourName)
            f.gate = CompletableDeferred()
            m.saveName("Ama", "", "Mensah")
            m.saveName("Ama", "", "Mensah")
            runCurrent()
            assertEquals(Route.Step(Destination.YourName), m.state.value.route)
            assertEquals(1, f.calls.count { it.startsWith("metadata") })
            f.gate!!.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun systemBackWhileBusyDoesNotCancelOrNavigateAndSaveCanFinish() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.open(Destination.YourName)
            m.drafts.first = "Ama"
            f.gate = CompletableDeferred()
            m.saveName("Ama", "", "Mensah")
            runCurrent()
            assertTrue(m.state.value.busy)
            val path = m.state.value.path
            m.back()
            assertEquals(path, m.state.value.path)
            assertNull(m.state.value.result)
            assertTrue(m.state.value.busy)
            assertEquals("Ama", m.drafts.first)
            f.gate!!.complete(Unit)
            runCurrent()
            assertNull(m.state.value.result)
            assertFalse(m.state.value.busy)
            assertEquals(Route.Step(Destination.Checklist), m.state.value.route)
            assertEquals(1, f.calls.count { it.startsWith("metadata") })
        }
    }

    @Test
    fun submitStoresClearedEmailAndCloseEmitsOnceWithSubmittedStatus() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.submit(false, "old@example.com")
            runCurrent()
            m.close()
            m.close()
            assertTrue(
                f.calls.first { it.startsWith("metadata") }.contains("\"report_email\":\"\"")
            )
            assertEquals(IdentifiedResult.Completed("c", "submitted"), m.state.value.result)
        }
    }

    @Test
    fun addressIsSavedBeforeUploadAndPagesAreBounded() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.confirmAddress(StatedAddress("GA1839124", source = AddressSource.TYPED))
            runCurrent()
            repeat(5) {
                m.uploadAddressPage(byteArrayOf(1), "image/jpeg")
                runCurrent()
            }
            assertEquals(4, m.state.value.pages)
            assertEquals(
                listOf(
                    "upload:proof_of_address:null",
                    "upload:proof_of_address:page_2",
                    "upload:proof_of_address:page_3",
                    "upload:proof_of_address:page_4",
                ),
                f.calls.filter { it.startsWith("upload") },
            )
            assertTrue(
                f.calls.indexOfFirst { it.startsWith("metadata") } <
                    f.calls.indexOfFirst { it.startsWith("upload") }
            )
        }
    }

    @Test
    fun pollingStopsAtFinalStatus() = runTest {
        withModel { m, f ->
            resume(f)
            f.session = f.session.copy(caseStatus = "approved")
            f.current = f.current.copy(status = "approved")
            m.load()
            runCurrent()
            m.startPolling()
            runCurrent()
            advanceTimeBy(15000)
            runCurrent()
            assertEquals(1, f.calls.count { it == "case" })
        }
    }

    @Test
    fun closeCancelsSuspendedSaveWithoutLaterNavigation() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.open(Destination.YourName)
            f.gate = CompletableDeferred()
            m.saveName("Ama", "", "Mensah")
            runCurrent()
            m.close()
            f.gate!!.complete(Unit)
            runCurrent()
            assertEquals(IdentifiedResult.Cancelled, m.state.value.result)
            assertEquals(Route.Step(Destination.YourName), m.state.value.route)
            assertEquals(1, f.calls.count { it == "session" })
            assertEquals(IdentifiedResult.Cancelled, m.takeResult())
            assertNull(m.takeResult())
        }
    }

    @Test
    fun recreationDoesNotReloadOrLoseMemoryDrafts() = runTest {
        withModel { m, f ->
            m.loadIfNeeded()
            runCurrent()
            m.begin()
            m.drafts.first = "Ama"
            m.drafts.frames = mapOf("image" to byteArrayOf(1))
            m.loadIfNeeded()
            runCurrent()
            assertEquals(1, f.calls.count { it == "session" })
            assertEquals(Route.Consent, m.state.value.route)
            assertEquals("Ama", m.drafts.first)
            assertArrayEquals(byteArrayOf(1), m.drafts.frames!!["image"])
        }
    }

    @Test
    fun refusedExhaustedPageCannotBeRetried() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.confirmAddress(StatedAddress("GA1839124", source = AddressSource.TYPED))
            runCurrent()
            f.fail = ApiException(422, "Please contact support", true)
            m.uploadAddressPage(byteArrayOf(1), "image/jpeg")
            runCurrent()
            m.uploadAddressPage(byteArrayOf(2), "image/jpeg")
            runCurrent()
            assertTrue(m.state.value.exhausted)
            assertEquals("Please contact support", m.state.value.addressRefusal)
            assertEquals(1, f.calls.count { it.startsWith("upload") })
            assertEquals(0, m.state.value.pages)
        }
    }

    @Test
    fun invalidAndExpiredSessionsFinishWhileUnavailableCanRetry() = runTest {
        for ((code, message, expected) in
            listOf(
                Triple(404, "unknown", IdentifiedError.SessionInvalid),
                Triple(401, "session expired", IdentifiedError.SessionExpired),
            )) {
            withModel { m, f ->
                f.fail = ApiException(code, message)
                m.load()
                runCurrent()
                assertEquals(IdentifiedResult.Failed(expected), m.state.value.result)
            }
        }
        withModel { m, f ->
            f.fail = ApiException(503, "offline")
            m.load()
            runCurrent()
            assertEquals(Stage.UNAVAILABLE, m.state.value.stage)
            assertNull(m.state.value.result)
            f.fail = null
            m.load()
            runCurrent()
            assertEquals(Stage.READY, m.state.value.stage)
        }
    }

    @Test
    fun tooLargePreparedFileShowsErrorWithoutUploading() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.confirmAddress(StatedAddress("GA1839124", source = AddressSource.TYPED))
            runCurrent()
            m.importAddressPage {
                ByteArray(m.state.value.policy.maxSizeBytes + 1) to "application/pdf"
            }
            runCurrent()
            assertEquals("poa.too_large", m.state.value.errorKey)
            assertFalse(f.calls.any { it.startsWith("upload") })
        }
    }

    @Test
    fun exhaustedRefusalSurvivesAddressChangeResolveAndConfirmation() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.confirmAddress(StatedAddress("GA1839124", source = AddressSource.TYPED))
            runCurrent()
            f.fail =
                ApiException(422, "No attempts left. Contact the business that sent you.", true)
            m.uploadAddressPage(byteArrayOf(1), "image/jpeg")
            runCurrent()
            f.fail = null
            m.changeAddress()
            m.resolveAddress("GA-184-9124")
            runCurrent()
            assertEquals(
                "No attempts left. Contact the business that sent you.",
                m.state.value.addressRefusal,
            )
            m.confirmCandidate()
            runCurrent()
            assertEquals("GA1849124", m.state.value.address?.code)
            assertTrue(m.state.value.exhausted)
            assertEquals(
                "No attempts left. Contact the business that sent you.",
                m.state.value.addressRefusal,
            )
            m.uploadAddressPage(byteArrayOf(2), "image/jpeg")
            runCurrent()
            assertEquals(1, f.calls.count { it.startsWith("upload") })
        }
    }

    @Test
    fun failedReviewRefreshOffersRetryWithCachedMissingDocumentsAndRetryReplacesThem() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.refreshChecklist()
            runCurrent()
            m.open(Destination.Review)
            f.fail = ApiException(503, "Temporarily unavailable")
            m.loadCase()
            runCurrent()
            assertNotNull(m.state.value.current)
            assertTrue(m.state.value.current!!.documents.isEmpty())
            assertTrue(m.state.value.canRetryReview)
            assertEquals("Temporarily unavailable", m.state.value.error)
            f.fail = null
            f.current =
                f.current.copy(documents = listOf(VerificationDocument(docType = "government_id")))
            m.loadCase()
            runCurrent()
            assertFalse(m.state.value.canRetryReview)
            assertNull(m.state.value.error)
            assertTrue(FlowRules.hasDocument(m.state.value.current!!, "government_id"))
        }
    }

    @Test
    fun addressCameraPhotoWaitsForAcceptanceBeforeUploading() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.open(Destination.ProofOfAddress)
            m.confirmAddress(StatedAddress("GA1839124", source = AddressSource.TYPED))
            runCurrent()
            val photo = byteArrayOf(1, 2, 3)
            m.previewAddressPhoto(photo)
            runCurrent()
            assertSame(photo, m.drafts.addressPhoto)
            assertEquals(0, m.state.value.pages)
            assertFalse(f.calls.any { it.startsWith("upload") })
            m.useAddressPhoto()
            m.useAddressPhoto()
            runCurrent()
            assertNull(m.drafts.addressPhoto)
            assertEquals(1, m.state.value.pages)
            assertEquals(
                listOf("upload:proof_of_address:null"),
                f.calls.filter { it.startsWith("upload") },
            )
        }
    }

    @Test
    fun pendingAddressPhotoIsClearedOnLeavingAndFinishingAndLateCaptureIsIgnored() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.open(Destination.ProofOfAddress)
            m.confirmAddress(StatedAddress("GA1839124", source = AddressSource.TYPED))
            runCurrent()
            m.previewAddressPhoto(byteArrayOf(1))
            assertNotNull(m.drafts.addressPhoto)
            m.back()
            assertNull(m.drafts.addressPhoto)
            m.previewAddressPhoto(byteArrayOf(2))
            assertNull(m.drafts.addressPhoto)
            m.open(Destination.ProofOfAddress)
            m.confirmAddress(StatedAddress("GA1839124", source = AddressSource.TYPED))
            runCurrent()
            m.previewAddressPhoto(byteArrayOf(3))
            assertNotNull(m.drafts.addressPhoto)
            m.close()
            assertNull(m.drafts.addressPhoto)
            m.previewAddressPhoto(byteArrayOf(4))
            assertNull(m.drafts.addressPhoto)
            assertFalse(f.calls.any { it.startsWith("upload") })
        }
    }

    @Test
    fun acceptedAddressPhotoPreservesExhaustedRefusalAndCannotBeRestaged() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.open(Destination.ProofOfAddress)
            m.confirmAddress(StatedAddress("GA1839124", source = AddressSource.TYPED))
            runCurrent()
            m.previewAddressPhoto(byteArrayOf(1))
            f.fail = ApiException(422, "No attempts left", true)
            m.useAddressPhoto()
            runCurrent()
            assertNull(m.drafts.addressPhoto)
            assertTrue(m.state.value.exhausted)
            assertEquals("No attempts left", m.state.value.addressRefusal)
            m.previewAddressPhoto(byteArrayOf(2))
            m.useAddressPhoto()
            runCurrent()
            assertNull(m.drafts.addressPhoto)
            assertEquals(1, f.calls.count { it.startsWith("upload") })
        }
    }

    @Test
    fun acceptedPdfThumbnailSurvivesNavigationAndClearsAtFinish() = runTest {
        withModel { m, f ->
            resume(f)
            m.load()
            runCurrent()
            m.open(Destination.ProofOfAddress)
            m.confirmAddress(StatedAddress("GA1839124", source = AddressSource.TYPED))
            runCurrent()
            m.uploadAddressPage(byteArrayOf(1, 2), "application/pdf")
            runCurrent()
            assertTrue(m.acceptedCaptures.items.single().pdf)
            assertNull(m.acceptedCaptures.items.single().jpeg)
            m.open(Destination.Review)
            assertEquals(1, m.acceptedCaptures.items.size)
            m.close()
            assertTrue(m.acceptedCaptures.items.isEmpty())
        }
    }
}
